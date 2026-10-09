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

/** Called after JWT signature/issuer/time validation; checks provider revocation on every request. */
public final class CognitoTokenVerifier {
    private final CognitoSettings settings;
    private final CognitoIdentityProviderClient client;
    public CognitoTokenVerifier(CognitoSettings settings,CognitoIdentityProviderClient client){this.settings=settings;this.client=client;}
    public CognitoIdentity verify(Jwt jwt) {
        try { return verifyClaims(jwt); }
        catch(OAuth2AuthenticationException|IdentityUnavailableException expected){throw expected;}
        catch(RuntimeException malformed){throw invalid();}
    }
    private CognitoIdentity verifyClaims(Jwt jwt) {
        Set<String> scopes=Set.of();
        String scope=jwt.getClaimAsString("scope");
        if(scope!=null)scopes=Arrays.stream(scope.split(" ")).collect(Collectors.toSet());
        if(jwt.hasClaim("aud") || jwt.getTokenValue().length()>16384 || !settings.issuer().equals(jwt.getIssuer().toString())
                || !"access".equals(jwt.getClaimAsString("token_use")) || !settings.clientId().equals(jwt.getClaimAsString("client_id"))
                || !scopes.containsAll(Set.of("aws.cognito.signin.user.admin",settings.scopePrefix()+"/account",settings.scopePrefix()+"/content",settings.scopePrefix()+"/support")))throw invalid();
        String subject=bounded(jwt.getSubject(),256),family=bounded(jwt.getClaimAsString("origin_jti"),256);
        Instant now=Instant.now(),auth=jwt.getClaimAsInstant("auth_time");
        if(jwt.getIssuedAt()==null || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(now)
                || jwt.getIssuedAt().isAfter(now.plusSeconds(60)) || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                || auth==null || auth.isAfter(now.plusSeconds(60)))throw invalid();
        try {
            var user=client.getUser(r->r.accessToken(jwt.getTokenValue()));
            var attributes=user.userAttributes().stream().collect(Collectors.toMap(a->a.name(),a->a.value(),(a,b)->a));
            if(!subject.equals(attributes.get("sub")))throw invalid();
            String username=bounded(user.username(),320);
            String display=attributes.getOrDefault("name",username);
            if(display.length()>160)display=username.substring(0,Math.min(username.length(),160));
            return new CognitoIdentity(settings.issuer(),subject,family,username,display,auth);
        } catch(NotAuthorizedException|UserNotFoundException rejected){throw invalid();}
        catch(OAuth2AuthenticationException rejected){throw rejected;}
        catch(RuntimeException unavailable){throw new IdentityUnavailableException();}
    }
    private static String bounded(String value,int size){if(value==null||value.isBlank()||value.length()>size||value.codePoints().anyMatch(Character::isISOControl))throw invalid();return value;}
    public static OAuth2AuthenticationException invalid(){return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),"Invalid access token");}
}
