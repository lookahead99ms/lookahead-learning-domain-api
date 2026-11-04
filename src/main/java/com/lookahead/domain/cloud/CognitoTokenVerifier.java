package com.lookahead.domain.cloud;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import com.lookahead.domain.security.IdentityUnavailableException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Called after JWT signature/issuer/time validation; checks provider revocation on every request.
 *
 * <p>Two token shapes are accepted, both only for our application client:
 * <ul>
 *   <li>authorization-server (OAuth) tokens carrying {@code aws.cognito.signin.user.admin} and the three Look Ahead
 *   scopes, exactly as before;</li>
 *   <li>DLV-969 decision B: Cognito API sign-in tokens (our own sign-in page, {@code InitiateAuth}), whose scope is
 *   exactly {@code aws.cognito.signin.user.admin} because Cognito cannot add custom scopes to them, and only when the
 *   caller has already proven the request comes from our Gateway ({@code gatewayProven}: the Gateway service secret
 *   on the internal sign-in routes, the per-sign-in proof on API calls). Such a token is treated as holding the same
 *   three logical scopes. Any other scope mix is refused.</li>
 * </ul>
 */
public final class CognitoTokenVerifier {
    public static final String ADMIN_SCOPE="aws.cognito.signin.user.admin";
    /**
     * DLV-969 S37 (user, 2026-11-03): Cognito's answer for one access token is reused for up to a minute, never past the
     * token's expiry. Every claim check still runs on each request, and the Domain's own sign-in record (sign-out,
     * Disable, Delete) is still read on each request; only a revocation made inside Cognito alone waits up to a minute.
     */
    static final java.time.Duration REUSE=java.time.Duration.ofSeconds(60);
    static final int REUSE_LIMIT=10_000;
    private record Answer(String username,java.util.Map<String,String> attributes,Instant until) {}
    private final java.util.concurrent.ConcurrentHashMap<String,Answer> answers=new java.util.concurrent.ConcurrentHashMap<>();
    private final CognitoSettings settings;
    private final CognitoIdentityProviderClient client;
    private final java.time.Clock clock;
    public CognitoTokenVerifier(CognitoSettings settings,CognitoIdentityProviderClient client){this(settings,client,java.time.Clock.systemUTC());}
    CognitoTokenVerifier(CognitoSettings settings,CognitoIdentityProviderClient client,java.time.Clock clock){this.settings=settings;this.client=client;this.clock=clock;}
    /** OAuth tokens only; API sign-in tokens are refused without proof of the Gateway. */
    public CognitoIdentity verify(Jwt jwt) { return verify(jwt,false); }
    public CognitoIdentity verify(Jwt jwt,boolean gatewayProven) { return verify(jwt,gatewayProven,false); }
    /**
     * DLV-969 S37: for ordinary API calls ({@link CloudDomainTokenConverter}) Cognito's answer for this token may be
     * reused for up to a minute; sign-in and its internal routes always ask Cognito afresh.
     */
    public CognitoIdentity verifyForApiCall(Jwt jwt,boolean gatewayProven) { return verify(jwt,gatewayProven,true); }
    private CognitoIdentity verify(Jwt jwt,boolean gatewayProven,boolean reuse) {
        try { return verifyClaims(jwt,gatewayProven,reuse); }
        catch(OAuth2AuthenticationException|IdentityUnavailableException expected){throw expected;}
        catch(RuntimeException malformed){throw invalid();}
    }
    /** The full OAuth scope set, or exactly the API sign-in scope when the Gateway is proven. */
    boolean scopesAccepted(Set<String> scopes,boolean gatewayProven){
        if(scopes.containsAll(Set.of(ADMIN_SCOPE,settings.scopePrefix()+"/account",settings.scopePrefix()+"/content",settings.scopePrefix()+"/support")))return true;
        return gatewayProven && scopes.equals(Set.of(ADMIN_SCOPE));
    }
    private CognitoIdentity verifyClaims(Jwt jwt,boolean gatewayProven,boolean reuse) {
        Set<String> scopes=Set.of();
        String scope=jwt.getClaimAsString("scope");
        if(scope!=null)scopes=Arrays.stream(scope.split(" ")).filter(s->!s.isEmpty()).collect(Collectors.toSet());
        if(jwt.hasClaim("aud") || jwt.getTokenValue().length()>16384 || !settings.issuer().equals(jwt.getIssuer().toString())
                || !"access".equals(jwt.getClaimAsString("token_use")) || !settings.clientId().equals(jwt.getClaimAsString("client_id"))
                || !scopesAccepted(scopes,gatewayProven))throw invalid();
        String subject=bounded(jwt.getSubject(),256),family=bounded(jwt.getClaimAsString("origin_jti"),256);
        Instant now=Instant.now(),auth=jwt.getClaimAsInstant("auth_time");
        if(jwt.getIssuedAt()==null || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(now)
                || jwt.getIssuedAt().isAfter(now.plusSeconds(60)) || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                || auth==null || auth.isAfter(now.plusSeconds(60)))throw invalid();
        try {
            var answer=providerAnswer(jwt,reuse);
            var attributes=answer.attributes();
            if(!subject.equals(attributes.get("sub")))throw invalid();
            String username=bounded(answer.username(),320);
            String display=attributes.getOrDefault("name",username);
            if(display.length()>160)display=username.substring(0,Math.min(username.length(),160));
            String verifiedEmail=null;
            if("true".equals(attributes.get("email_verified")) && attributes.get("email")!=null) {
                try {verifiedEmail=com.lookahead.domain.access.StaffAccessService.normalizeEmail(attributes.get("email"));}
                catch(com.lookahead.learning.content.exception.AccountFailure invalidEmail){throw invalid();}
            }
            return new CognitoIdentity(settings.issuer(),subject,family,username,display,auth,verifiedEmail);
        } catch(NotAuthorizedException|UserNotFoundException rejected){throw invalid();}
        catch(OAuth2AuthenticationException rejected){throw rejected;}
        catch(RuntimeException unavailable){throw new IdentityUnavailableException();}
    }
    /** Cognito's GetUser for this token; with {@code reuse}, asked at most once a minute per token. Only successes are kept. */
    private Answer providerAnswer(Jwt jwt,boolean reuse){
        String key=digest(jwt.getTokenValue());
        Instant at=clock.instant();
        var kept=reuse?answers.get(key):null;
        if(kept!=null && at.isBefore(kept.until()))return kept;
        long started=com.lookahead.domain.Events.started();
        software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse user;
        try {user=client.getUser(r->r.accessToken(jwt.getTokenValue()));}
        catch(NotAuthorizedException|UserNotFoundException rejected){com.lookahead.domain.Events.upstreamRefused("Cognito","GetUser",started,"TOKEN_REJECTED");throw rejected;}
        catch(RuntimeException failure){com.lookahead.domain.Events.upstreamFailed("Cognito","GetUser",started,failure);throw failure;}
        com.lookahead.domain.Events.upstreamDone("Cognito","GetUser",started);
        var attributes=user.userAttributes().stream().collect(Collectors.toMap(a->a.name(),a->a.value(),(a,b)->a));
        Instant until=at.plus(REUSE);
        if(jwt.getExpiresAt()!=null && jwt.getExpiresAt().isBefore(until))until=jwt.getExpiresAt();
        var answer=new Answer(user.username(),java.util.Map.copyOf(attributes),until);
        if(answers.size()>=REUSE_LIMIT)answers.entrySet().removeIf(entry->!at.isBefore(entry.getValue().until()));
        if(answers.size()<REUSE_LIMIT)answers.put(key,answer);
        return answer;
    }
    private static String digest(String token){
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String bounded(String value,int size){if(value==null||value.isBlank()||value.length()>size||value.codePoints().anyMatch(Character::isISOControl))throw invalid();return value;}
    public static OAuth2AuthenticationException invalid(){return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),"Invalid access token");}
}
