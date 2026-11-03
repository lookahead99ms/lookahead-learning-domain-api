package com.lookahead.domain.cloud;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-969 decision B at the API boundary: the per-sign-in proof header is what admits an API sign-in token. */
class CloudDomainTokenConverterTest {
    final CognitoFixture fixture=new CognitoFixture();
    final CognitoTokenVerifier verifier=new CognitoTokenVerifier(CognitoTokenVerifierTest.SETTINGS,fixture.client);
    final CloudSignInRegistry registry=mock(CloudSignInRegistry.class);
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final CloudDomainTokenConverter converter=new CloudDomainTokenConverter(verifier,registry,request);
    Jwt apiToken(){return Jwt.withTokenValue("synthetic-access-token").header("alg","RS256").issuer(CognitoTokenVerifierTest.SETTINGS.issuer()).subject("synthetic-provider-subject")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("token_use","access").claim("client_id",CognitoTokenVerifierTest.SETTINGS.clientId())
        .claim("origin_jti","synthetic-family").claim("auth_time",Instant.now()).claim("scope",CognitoTokenVerifier.ADMIN_SCOPE).claims(c->c.putAll(Map.of())).build();}

    @Test void apiSignInTokenWithoutProofIsRefusedBeforeTheRegistry(){
        assertThatThrownBy(()->converter.convert(apiToken())).isInstanceOf(OAuth2AuthenticationException.class);
        request.addHeader(CloudDomainTokenConverter.PROOF_HEADER,"   ");
        assertThatThrownBy(()->converter.convert(apiToken())).isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(registry);
        assertThat(fixture.calls.get()).isZero();
    }

    @Test void apiSignInTokenWithProofIsBoundToThatSignInAndGetsTheThreeScopes(){
        fixture.response=r->GetUserResponse.builder().username("priya@example.invalid").userAttributes(AttributeType.builder().name("sub").value("synthetic-provider-subject").build()).build();
        request.addHeader(CloudDomainTokenConverter.PROOF_HEADER,"p".repeat(43));
        var principal=new com.lookahead.learning.content.security.AccountPrincipal(java.util.UUID.randomUUID(),"priya@example.invalid","Priya",true,false,null);
        when(registry.authenticate(any(),eq("p".repeat(43)))).thenReturn(principal);
        var result=converter.convert(apiToken());
        assertThat(result.getAuthorities()).extracting(Object::toString).containsExactlyInAnyOrder("SCOPE_account","SCOPE_content","SCOPE_support");
        assertThat(result.getPrincipal()).isSameAs(principal);
        // A proof that does not match the sign-in is refused by the registry, not by the token rule.
        when(registry.authenticate(any(),eq("p".repeat(43)))).thenThrow(new com.lookahead.learning.content.exception.AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in again."));
        assertThatThrownBy(()->converter.convert(apiToken())).isInstanceOf(OAuth2AuthenticationException.class);
        // An over-long proof never unlocks the API sign-in path.
        var longProof=new MockHttpServletRequest();longProof.addHeader(CloudDomainTokenConverter.PROOF_HEADER,"p".repeat(513));
        assertThatThrownBy(()->new CloudDomainTokenConverter(verifier,registry,longProof).convert(apiToken())).isInstanceOf(OAuth2AuthenticationException.class);
    }
}
