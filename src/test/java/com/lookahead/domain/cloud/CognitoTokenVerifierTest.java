package com.lookahead.domain.cloud;

import com.lookahead.domain.security.IdentityUnavailableException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CognitoTokenVerifierTest {
    static final CognitoSettings SETTINGS=new CognitoSettings("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example","us-east-2","syntheticclient123456","lookahead","synthetic-internal-gateway-secret-for-fixtures");
    CognitoFixture fixture=new CognitoFixture();
    CognitoIdentityProviderClient client=fixture.client;
    CognitoTokenVerifier verifier=new CognitoTokenVerifier(SETTINGS,client);
    Jwt token(Map<String,Object> overrides){return Jwt.withTokenValue("synthetic-access-token").header("alg","RS256").issuer(SETTINGS.issuer()).subject("synthetic-provider-subject")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("token_use","access").claim("client_id",SETTINGS.clientId()).claim("origin_jti","synthetic-family")
        .claim("auth_time",Instant.now()).claim("scope","aws.cognito.signin.user.admin lookahead/account lookahead/content lookahead/support")
        .claims(c->c.putAll(overrides)).build();}
    @Test void requiresAccessTokenExpectedClientScopesFamilyAndFreshProviderStatus(){
        for(var overrides:java.util.List.of(Map.<String,Object>of("token_use","id"),Map.<String,Object>of("client_id","wrong"),Map.<String,Object>of("scope","openid"),Map.<String,Object>of("origin_jti",""),Map.<String,Object>of("iss","https://wrong.invalid")))
            assertThatThrownBy(()->verifier.verify(token(overrides))).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isZero();
    }
    @Test void validatesProviderSubjectAndDoesNotCacheRevocation(){
        fixture.response=request->{if(fixture.calls.get()>1)throw NotAuthorizedException.builder().message("revoked").build();return GetUserResponse.builder().username("learner").userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();};
        assertThat(verifier.verify(token(Map.of())).subject()).isEqualTo("synthetic-provider-subject");
        assertThatThrownBy(()->verifier.verify(token(Map.of()))).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(2);
    }
    @Test void providerOutageFailsClosedWithoutLeakingDetails(){
        fixture.response=request->{throw new IllegalStateException("synthetic secret");};
        assertThatThrownBy(()->verifier.verify(token(Map.of()))).isInstanceOf(IdentityUnavailableException.class).hasMessageNotContaining("synthetic secret");
    }
    @Test void requiresExplicitValidLifetimeBeforeCallingProvider(){
        for(String missing:java.util.List.of("iat","exp")){
            var jwt=Jwt.withTokenValue("synthetic-token").headers(h->h.putAll(token(Map.of()).getHeaders()))
                .claims(c->{c.putAll(token(Map.of()).getClaims());c.remove(missing);}).build();
            assertThatThrownBy(()->verifier.verify(jwt)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        }
        assertThatThrownBy(()->verifier.verify(token(Map.of("iat",Instant.now().plusSeconds(120))))).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isZero();
    }
    @Test void cloudSettingsCannotFallBackToLocal(){
        var env=new MockEnvironment().withProperty("app.deployment-environment","dev").withProperty("app.cognito.issuer",SETTINGS.issuer()).withProperty("app.cognito.region",SETTINGS.region())
            .withProperty("app.cognito.client-id",SETTINGS.clientId()).withProperty("app.cognito.scope-prefix",SETTINGS.scopePrefix()).withProperty("app.cognito.gateway-secret",SETTINGS.gatewaySecret());
        assertThat(CognitoSettings.from(env).issuer()).isEqualTo(SETTINGS.issuer());
        env.withProperty("app.cognito.issuer","");assertThatThrownBy(()->CognitoSettings.from(env)).isInstanceOf(IllegalStateException.class);
    }
}
