package com.lookahead.domain.security.token;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.client.SignInCognitoClient;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.security.config.SignInTokenTrust;
import com.lookahead.domain.validator.SignInTokenTrustValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SignInTokenVerifierTest {
    public static final SignInTokenTrust TRUST=new SignInTokenTrust("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example","us-east-2","syntheticclient123456","lookahead");
    public static final SignInBffSecret BFF_SECRET=new SignInBffSecret("synthetic-internal-gateway-secret-for-fixtures");
    SignInProviderFixture fixture=new SignInProviderFixture();
    CognitoIdentityProviderClient client=fixture.client;
    SignInTokenVerifier verifier=new SignInTokenVerifier(TRUST,new SignInCognitoClient(TRUST,client,client));
    Jwt token(Map<String,Object> overrides){return Jwt.withTokenValue("synthetic-access-token").header("alg","RS256").issuer(TRUST.issuer()).subject("synthetic-provider-subject")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("token_use","access").claim("client_id",TRUST.clientId()).claim("origin_jti","synthetic-family")
        .claim("auth_time",Instant.now()).claim("scope","aws.cognito.signin.user.admin lookahead/account lookahead/content lookahead/support")
        .claims(c->c.putAll(overrides)).build();}
    @Test void requiresAccessTokenExpectedClientScopesFamilyAndFreshProviderStatus(){
        for(var overrides:List.of(Map.<String,Object>of("token_use","id"),Map.<String,Object>of("client_id","wrong"),Map.<String,Object>of("scope","openid"),Map.<String,Object>of("origin_jti",""),Map.<String,Object>of("iss","https://wrong.invalid")))
            assertThatThrownBy(()->verifier.verify(token(overrides),false)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isZero();
    }
    /**
     * DLV-969 S37: Cognito's answer for one token is reused for up to a minute (never past the token's expiry), so a
     * revocation made inside Cognito alone applies within a minute; claim checks still run on every request.
     */
    @Test void validatesProviderSubjectAndReusesTheProviderAnswerForAMinuteAtMost(){
        var now=new AtomicReference<>(Instant.parse("2026-11-03T12:00:00Z"));
        var clock=new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var reusing=new SignInTokenVerifier(TRUST,new SignInCognitoClient(TRUST,client,client,clock));
        fixture.response=request->{if(fixture.calls.get()>1)throw NotAuthorizedException.builder().message("revoked").build();return GetUserResponse.builder().username("learner").userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();};
        assertThat(reusing.verifyForApiCall(token(Map.of()),false).subject()).isEqualTo("synthetic-provider-subject");
        now.set(now.get().plusSeconds(59));
        assertThat(reusing.verifyForApiCall(token(Map.of()),false).subject()).isEqualTo("synthetic-provider-subject");
        assertThat(fixture.calls.get()).isEqualTo(1);
        // Claim checks never use the kept answer.
        assertThatThrownBy(()->reusing.verifyForApiCall(token(Map.of("client_id","wrong")),false)).isInstanceOf(OAuth2AuthenticationException.class);
        // After a minute Cognito is asked again, and its revocation applies.
        now.set(now.get().plusSeconds(2));
        assertThatThrownBy(()->reusing.verifyForApiCall(token(Map.of()),false)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(2);
        // Sign-in routes never reuse: they always ask Cognito.
        assertThatThrownBy(()->reusing.verify(token(Map.of()),false)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(3);
        // A refusal is never kept: the next request asks again.
        assertThatThrownBy(()->reusing.verifyForApiCall(token(Map.of()),false)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(4);
    }
    @Test void reuseNeverOutlivesTheTokenAndEachTokenIsAskedOnItsOwn(){
        var now=new AtomicReference<>(Instant.now());
        var clock=new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var reusing=new SignInTokenVerifier(TRUST,new SignInCognitoClient(TRUST,client,client,clock));
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
        assertThat(verifier.verify(token(Map.of("email","forged@example.invalid")),false).verifiedEmail()).isEqualTo("verified@example.invalid");
        fixture.response=request->GetUserResponse.builder().username("typed@example.invalid").userAttributes(
            AttributeType.builder().name("sub").value("synthetic-provider-subject").build(),
            AttributeType.builder().name("email").value("typed@example.invalid").build(),
            AttributeType.builder().name("email_verified").value("false").build()).build();
        assertThat(verifier.verify(token(Map.of("email_verified",true)),false).verifiedEmail()).isNull();
    }
    @Test void providerOutageFailsClosedWithoutLeakingDetails(){
        fixture.response=request->{throw new IllegalStateException("synthetic secret");};
        assertThatThrownBy(()->verifier.verify(token(Map.of()),false)).isInstanceOf(SignInCheckUnavailableException.class).hasMessageNotContaining("synthetic secret");
    }
    @Test void requiresExplicitValidLifetimeBeforeCallingProvider(){
        for(String missing:List.of("iat","exp")){
            var jwt=Jwt.withTokenValue("synthetic-token").headers(h->h.putAll(token(Map.of()).getHeaders()))
                .claims(c->{c.putAll(token(Map.of()).getClaims());c.remove(missing);}).build();
            assertThatThrownBy(()->verifier.verify(jwt,false)).isInstanceOf(OAuth2AuthenticationException.class);
        }
        assertThatThrownBy(()->verifier.verify(token(Map.of("iat",Instant.now().plusSeconds(120))),false)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isZero();
    }
    /** DLV-969 decision B: API sign-in tokens carry only the Cognito admin scope; accepted only with Gateway proof. */
    @Test void apiSignInTokenNeedsGatewayProofOurClientAndExactlyTheAdminScope(){
        fixture.response=request->GetUserResponse.builder().username("priya@example.invalid").userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();
        var api=Map.<String,Object>of("scope",SignInTokenVerifier.ADMIN_SCOPE);
        assertThat(verifier.verify(token(api),true).subject()).isEqualTo("synthetic-provider-subject");
        assertThat(fixture.calls.get()).isEqualTo(1);
        // Without proof of the Gateway, an API sign-in token is refused before the provider is asked.
        assertThatThrownBy(()->verifier.verify(token(api),false)).isInstanceOf(OAuth2AuthenticationException.class);
        for(var bad:List.of(
                Map.<String,Object>of("scope",SignInTokenVerifier.ADMIN_SCOPE,"client_id","another-client"),
                Map.<String,Object>of("scope",SignInTokenVerifier.ADMIN_SCOPE,"token_use","id"),
                Map.<String,Object>of("scope",SignInTokenVerifier.ADMIN_SCOPE+" lookahead/account"),
                Map.<String,Object>of("scope",SignInTokenVerifier.ADMIN_SCOPE+" openid"),
                Map.<String,Object>of("scope","lookahead/account lookahead/content lookahead/support"),
                Map.<String,Object>of("scope",""),
                Map.<String,Object>of("scope",SignInTokenVerifier.ADMIN_SCOPE,"exp",Instant.now().minusSeconds(5),"iat",Instant.now().minusSeconds(60))))
            assertThatThrownBy(()->verifier.verify(token(bad),true)).as(bad.toString()).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(fixture.calls.get()).isEqualTo(1);
        // OAuth tokens keep today's rule, with or without the proof flag.
        assertThat(verifier.verify(token(Map.of()),false).subject()).isEqualTo("synthetic-provider-subject");
        assertThat(verifier.verify(token(Map.of()),true).subject()).isEqualTo("synthetic-provider-subject");
    }
    @Test void cloudSettingsCannotFallBackToLocal(){
        var env=new MockEnvironment().withProperty("app.deployment-environment","dev").withProperty("app.cognito.issuer",TRUST.issuer()).withProperty("app.cognito.region",TRUST.region())
            .withProperty("app.cognito.client-id",TRUST.clientId()).withProperty("app.cognito.scope-prefix",TRUST.scopePrefix()).withProperty("app.cognito.gateway-secret",BFF_SECRET.value());
        assertThat(SignInTokenTrustValidator.validated(env).trust().issuer()).isEqualTo(TRUST.issuer());
        env.withProperty("app.cognito.issuer","");assertThatThrownBy(()->SignInTokenTrustValidator.validated(env)).isInstanceOf(IllegalStateException.class);
    }
}
