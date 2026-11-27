package com.lookahead.domain.security.token;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.security.config.SignInTokenTrust;
import com.lookahead.domain.service.access.StaffAccessService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import com.lookahead.domain.client.SignInCognitoClient;

/**
 * Called after JWT signature/issuer/time validation; checks provider revocation on every request.
 *
 * <p>Two token shapes are accepted, both only for our application client:
 * <ul>
 *   <li>authorization-server (OAuth) tokens carrying {@code aws.cognito.signin.user.admin} and the three Look Ahead
 *   scopes, exactly as before;</li>
 *   <li>DLV-969 decision B: Cognito API sign-in tokens (our own sign-in page, {@code InitiateAuth}), whose scope is
 *   exactly {@code aws.cognito.signin.user.admin} because Cognito cannot add custom scopes to them, and only when the
 *   caller has already proven the request comes from our Gateway BFF ({@code bffProven}: the Gateway BFF's service secret
 *   on the internal sign-in routes, the per-sign-in proof on API calls). Such a token is treated as holding the same
 *   three logical scopes. Any other scope mix is refused.</li>
 * </ul>
 */
public final class SignInTokenVerifier {
    public static final String ADMIN_SCOPE="aws.cognito.signin.user.admin";
    private final SignInTokenTrust trust;
    private final SignInCognitoClient cognito;
    public SignInTokenVerifier(SignInTokenTrust trust,SignInCognitoClient cognito){this.trust=trust;this.cognito=cognito;}
    /** Without proof of the Gateway ({@code bffProven} false) only OAuth tokens are accepted; API sign-in tokens are refused. */
    public SignInIdentity verify(Jwt jwt,boolean bffProven) { return verify(jwt,bffProven,false); }
    /**
     * DLV-969 S37: for ordinary API calls ({@link SignInTokenConverter}) Cognito's answer for this token may be
     * reused for up to a minute; sign-in and its internal routes always ask Cognito afresh.
     */
    public SignInIdentity verifyForApiCall(Jwt jwt,boolean bffProven) { return verify(jwt,bffProven,true); }
    private SignInIdentity verify(Jwt jwt,boolean bffProven,boolean reuse) {
        try { return verifyClaims(jwt,bffProven,reuse); }
        catch(OAuth2AuthenticationException|SignInCheckUnavailableException expected){throw expected;}
        catch(RuntimeException malformed){unexpected("TOKEN_MALFORMED","the token's claims could not be checked; refused with 401",malformed);throw invalid("TOKEN_MALFORMED");}
    }
    /** The full OAuth scope set, or exactly the API sign-in scope when the Gateway is proven. */
    boolean scopesAccepted(Set<String> scopes,boolean bffProven){
        if(scopes.containsAll(Set.of(ADMIN_SCOPE,trust.scopePrefix()+"/account",trust.scopePrefix()+"/content",trust.scopePrefix()+"/support")))return true;
        return bffProven && scopes.equals(Set.of(ADMIN_SCOPE));
    }
    private SignInIdentity verifyClaims(Jwt jwt,boolean bffProven,boolean reuse) {
        Set<String> scopes=Set.of();
        String scope=jwt.getClaimAsString("scope");
        if(scope!=null)scopes=Arrays.stream(scope.split(" ")).filter(s->!s.isEmpty()).collect(Collectors.toSet());
        if(jwt.hasClaim("aud"))throw invalid("AUDIENCE_PRESENT");
        if(jwt.getTokenValue().length()>16384)throw invalid("TOKEN_TOO_LONG");
        if(!trust.issuer().equals(jwt.getIssuer().toString()))throw invalid("ISSUER_MISMATCH");
        if(!"access".equals(jwt.getClaimAsString("token_use")))throw invalid("NOT_ACCESS_TOKEN");
        if(!trust.clientId().equals(jwt.getClaimAsString("client_id")))throw invalid("CLIENT_MISMATCH");
        if(!scopesAccepted(scopes,bffProven))throw invalid("SCOPES_MISSING");
        String subject=bounded(jwt.getSubject(),256),family=bounded(jwt.getClaimAsString("origin_jti"),256);
        Instant now=Instant.now(),auth=jwt.getClaimAsInstant("auth_time");
        if(jwt.getIssuedAt()==null || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(now)
                || jwt.getIssuedAt().isAfter(now.plusSeconds(60)) || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                || auth==null || auth.isAfter(now.plusSeconds(60)))throw invalid("TOKEN_TIMES_INVALID");
        try {
            var answer=cognito.tokenUser(jwt,reuse);
            var attributes=answer.attributes();
            if(!subject.equals(attributes.get("sub")))throw invalid("SUBJECT_MISMATCH");
            String username=bounded(answer.username(),320);
            String display=attributes.getOrDefault("name",username);
            if(display.length()>160)display=username.substring(0,Math.min(username.length(),160));
            String verifiedEmail=null;
            if("true".equals(attributes.get("email_verified")) && attributes.get("email")!=null) {
                try {verifiedEmail=StaffAccessService.normalizeEmail(attributes.get("email"));}
                catch(AccountFailure invalidEmail){throw invalid("EMAIL_INVALID");}
            }
            return new SignInIdentity(trust.issuer(),subject,family,username,display,auth,verifiedEmail);
        } catch(SignInCognitoClient.TokenRejected rejected){throw invalid("TOKEN_REJECTED");}
        catch(OAuth2AuthenticationException rejected){throw rejected;}
        catch(RuntimeException unavailable){
            // A failed GetUser already has its lookahead.upstream line; anything else would leave the 503 unexplained.
            if(!(unavailable instanceof SignInCognitoClient.TokenCheckUnavailable))
                unexpected("IDENTITY_CHECK_FAILED","Cognito's answer could not be used; answered 503",unavailable);
            throw new SignInCheckUnavailableException();
        }
    }
    private static String bounded(String value,int size){if(value==null||value.isBlank()||value.length()>size||value.codePoints().anyMatch(Character::isISOControl))throw invalid("CLAIM_INVALID");return value;}
    /**
     * DLV-969 (owner, 2026-11-04): the refusal of a token, carrying a stable reason code (the error's description) that
     * the 401 entry point logs ({@link com.lookahead.domain.log.EventLog#authenticationRefused}); the caller never sees it.
     */
    public static OAuth2AuthenticationException invalid(String reason){return new OAuth2AuthenticationException(new OAuth2Error("invalid_token",reason,null),"Invalid access token");}
    private static final LogThrottle UNEXPECTED=new LogThrottle();
    private static void unexpected(String reason,String words,RuntimeException failure){
        String types=EventLog.types(failure);
        if(UNEXPECTED.due(reason+"|"+types))EventLog.failed(EventLog.EVENT,"sign-in.check",reason)
            .addKeyValue("error.type",types).log("Sign-in token check failed: {} ({})",words,types);
    }
}
