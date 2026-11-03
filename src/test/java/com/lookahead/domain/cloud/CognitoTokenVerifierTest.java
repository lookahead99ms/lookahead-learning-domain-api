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
    /**
     * DLV-969 S37: Cognito's answer for one token is reused for up to a minute (never past the token's expiry), so a
     * revocation made inside Cognito alone applies within a minute; claim checks still run on every request.
     */
    @Test void validatesProviderSubjectAndReusesTheProviderAnswerForAMinuteAtMost(){
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-03T12:00:00Z"));
        var clock=new java.time.Clock(){
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var reusing=new CognitoTokenVerifier(SETTINGS,client,clock);
        fixture.response=request->{if(fixture.calls.get()>1)throw NotAuthorizedException.builder().message("revoked").build();return GetUserResponse.builder().username("learner").userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();};
        assertThat(reusing.verifyForApiCall(token(Map.of()),false).subject()).isEqualTo("synthetic-provider-subject");
        now.set(now.get().plusSeconds(59));
        assertThat(reusing.verifyForApiCall(token(Map.of()),false).subject()).isEqualTo("synthetic-provider-subject");
        assertThat(fixture.calls.get()).isEqualTo(1);
        // Claim checks never use the kept answer.
        assertThatThrownBy(()->reusing.verifyForApiCall(token(Map.of("client_id","wrong")),false)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        // After a minute Cognito is asked again, and its revocation applies.
        now.set(now.get().plusSeconds(2));
        assertThatThrownBy(()->reusing.verifyForApiCall(token(Map.of()),false)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(2);
        // Sign-in routes never reuse: they always ask Cognito.
        assertThatThrownBy(()->reusing.verify(token(Map.of()))).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(3);
        // A refusal is never kept: the next request asks again.
        assertThatThrownBy(()->reusing.verifyForApiCall(token(Map.of()),false)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(4);
    }
    @Test void reuseNeverOutlivesTheTokenAndEachTokenIsAskedOnItsOwn(){
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        var clock=new java.time.Clock(){
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var reusing=new CognitoTokenVerifier(SETTINGS,client,clock);
        fixture.response=request->GetUserResponse.builder().username("learner").userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();
        var shortLived=Jwt.withTokenValue("short-lived-token").headers(h->h.putAll(token(Map.of()).getHeaders()))
            .claims(c->{c.putAll(token(Map.of()).getClaims());c.put("exp",now.get().plusSeconds(20));}).build();
        reusing.verifyForApiCall(shortLived,false);
        now.set(now.get().plusSeconds(10));
        reusing.verifyForApiCall(shortLived,false);
        assertThat(fixture.calls.get()).isEqualTo(1);
        // Kept only until the token's own expiry (20 s), not the full minute: past it, Cognito is asked again.
        now.set(now.get().plusSeconds(11));
        reusing.verifyForApiCall(shortLived,false);
        assertThat(fixture.calls.get()).isEqualTo(2);
        // Each token gets its own answer.
        reusing.verifyForApiCall(token(Map.of()),false);
        var other=Jwt.withTokenValue("another-access-token").headers(h->h.putAll(token(Map.of()).getHeaders())).claims(c->c.putAll(token(Map.of()).getClaims())).build();
        reusing.verifyForApiCall(other,false);
        assertThat(fixture.calls.get()).isEqualTo(4);
    }
    @Test void invitationProofUsesProviderVerifiedEmailNotTokenClaimsOrUsername() {
        fixture.response=request->GetUserResponse.builder().username("typed@example.invalid").userAttributes(
            AttributeType.builder().name("sub").value("synthetic-provider-subject").build(),
            AttributeType.builder().name("email").value("Verified@Example.invalid").build(),
            AttributeType.builder().name("email_verified").value("true").build()).build();
        assertThat(verifier.verify(token(Map.of("email","forged@example.invalid"))).verifiedEmail()).isEqualTo("verified@example.invalid");
        fixture.response=request->GetUserResponse.builder().username("typed@example.invalid").userAttributes(
            AttributeType.builder().name("sub").value("synthetic-provider-subject").build(),
            AttributeType.builder().name("email").value("typed@example.invalid").build(),
            AttributeType.builder().name("email_verified").value("false").build()).build();
        assertThat(verifier.verify(token(Map.of("email_verified",true))).verifiedEmail()).isNull();
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
    /** DLV-969 decision B: API sign-in tokens carry only the Cognito admin scope; accepted only with Gateway proof. */
    @Test void apiSignInTokenNeedsGatewayProofOurClientAndExactlyTheAdminScope(){
        fixture.response=request->GetUserResponse.builder().username("priya@example.invalid").userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();
        var api=Map.<String,Object>of("scope",CognitoTokenVerifier.ADMIN_SCOPE);
        assertThat(verifier.verify(token(api),true).subject()).isEqualTo("synthetic-provider-subject");
        assertThat(fixture.calls.get()).isEqualTo(1);
        // Without proof of the Gateway, an API sign-in token is refused before the provider is asked.
        assertThatThrownBy(()->verifier.verify(token(api))).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThatThrownBy(()->verifier.verify(token(api),false)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        for(var bad:java.util.List.of(
                Map.<String,Object>of("scope",CognitoTokenVerifier.ADMIN_SCOPE,"client_id","another-client"),
                Map.<String,Object>of("scope",CognitoTokenVerifier.ADMIN_SCOPE,"token_use","id"),
                Map.<String,Object>of("scope",CognitoTokenVerifier.ADMIN_SCOPE+" lookahead/account"),
                Map.<String,Object>of("scope",CognitoTokenVerifier.ADMIN_SCOPE+" openid"),
                Map.<String,Object>of("scope","lookahead/account lookahead/content lookahead/support"),
                Map.<String,Object>of("scope",""),
                Map.<String,Object>of("scope",CognitoTokenVerifier.ADMIN_SCOPE,"exp",Instant.now().minusSeconds(5),"iat",Instant.now().minusSeconds(60))))
            assertThatThrownBy(()->verifier.verify(token(bad),true)).as(bad.toString()).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(1);
        // OAuth tokens keep today's rule, with or without the proof flag.
        assertThat(verifier.verify(token(Map.of()),false).subject()).isEqualTo("synthetic-provider-subject");
        assertThat(verifier.verify(token(Map.of()),true).subject()).isEqualTo("synthetic-provider-subject");
    }
    @Test void cloudSettingsCannotFallBackToLocal(){
        var env=new MockEnvironment().withProperty("app.deployment-environment","dev").withProperty("app.cognito.issuer",SETTINGS.issuer()).withProperty("app.cognito.region",SETTINGS.region())
            .withProperty("app.cognito.client-id",SETTINGS.clientId()).withProperty("app.cognito.scope-prefix",SETTINGS.scopePrefix()).withProperty("app.cognito.gateway-secret",SETTINGS.gatewaySecret());
        assertThat(CognitoSettings.from(env).issuer()).isEqualTo(SETTINGS.issuer());
        env.withProperty("app.cognito.issuer","");assertThatThrownBy(()->CognitoSettings.from(env)).isInstanceOf(IllegalStateException.class);
    }
}
