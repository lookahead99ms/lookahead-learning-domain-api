package com.lookahead.domain.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lookahead.domain.client.SignInCognitoClient;
import com.lookahead.domain.model.SignInIdentity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;

/**
 * The internal sign-in routes: the Gateway BFF's secret is compared first, then the token is
 * verified.
 */
class SignInBffRequestConverterTest {
  final SignInProviderFixture provider = new SignInProviderFixture();
  final SignInTokenVerifier verifier =
      new SignInTokenVerifier(
          SignInTokenVerifierTest.TRUST,
          new SignInCognitoClient(SignInTokenVerifierTest.TRUST, provider.client, provider.client));
  final MockHttpServletRequest request = new MockHttpServletRequest();
  final SignInBffRequestConverter converter =
      new SignInBffRequestConverter(verifier, SignInTokenVerifierTest.BFF_SECRET, request);

  /**
   * A sign-in page token: only the admin scope, accepted because the secret proves the Gateway BFF.
   */
  static Jwt token() {
    return Jwt.withTokenValue("synthetic-access-token")
        .header("alg", "RS256")
        .issuer(SignInTokenVerifierTest.TRUST.issuer())
        .subject("synthetic-provider-subject")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("token_use", "access")
        .claim("client_id", SignInTokenVerifierTest.TRUST.clientId())
        .claim("origin_jti", "synthetic-family")
        .claim("auth_time", Instant.now())
        .claim("scope", SignInTokenVerifier.ADMIN_SCOPE)
        .build();
  }

  @Test
  void aMissingOrWrongSecretIsRefusedBeforeTheTokenIsLookedAt() {
    for (String secret :
        new String[] {
          null, "", "not-the-secret", SignInTokenVerifierTest.BFF_SECRET.value() + "x"
        }) {
      request.removeHeader("X-LookAhead-Gateway-Secret");
      if (secret != null) request.addHeader("X-LookAhead-Gateway-Secret", secret);
      assertThatThrownBy(() -> converter.convert(token()))
          .isInstanceOfSatisfying(
              OAuth2AuthenticationException.class,
              refused ->
                  assertThat(refused.getError().getDescription())
                      .isEqualTo("GATEWAY_SECRET_MISMATCH"));
    }
    assertThat(provider.calls.get())
        .as("the provider is not asked about a request without the secret")
        .isZero();
  }

  @Test
  void theRightSecretLetsTheVerifierDecideAndTheVerifiedIdentityBecomesThePrincipal() {
    request.addHeader("X-LookAhead-Gateway-Secret", SignInTokenVerifierTest.BFF_SECRET.value());
    provider.response =
        asked ->
            GetUserResponse.builder()
                .username("learner")
                .userAttributes(
                    AttributeType.builder().name("sub").value("synthetic-provider-subject").build())
                .build();
    var authentication = converter.convert(token());
    assertThat(authentication.isAuthenticated()).isTrue();
    assertThat(authentication.getAuthorities()).isEmpty();
    assertThat(authentication.getPrincipal())
        .isInstanceOfSatisfying(
            SignInIdentity.class,
            identity -> assertThat(identity.subject()).isEqualTo("synthetic-provider-subject"));
    assertThat(provider.calls.get()).isEqualTo(1);
  }

  @Test
  void theRightSecretDoesNotRescueATokenTheVerifierRefuses() {
    request.addHeader("X-LookAhead-Gateway-Secret", SignInTokenVerifierTest.BFF_SECRET.value());
    var wrongClient =
        Jwt.withTokenValue("synthetic-access-token")
            .header("alg", "RS256")
            .issuer(SignInTokenVerifierTest.TRUST.issuer())
            .subject("synthetic-provider-subject")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("token_use", "access")
            .claim("client_id", "wrong")
            .claim("origin_jti", "synthetic-family")
            .claim("auth_time", Instant.now())
            .claim("scope", SignInTokenVerifier.ADMIN_SCOPE)
            .build();
    assertThatThrownBy(() -> converter.convert(wrongClient))
        .isInstanceOfSatisfying(
            OAuth2AuthenticationException.class,
            refused ->
                assertThat(refused.getError().getDescription()).isEqualTo("CLIENT_MISMATCH"));
  }
}
