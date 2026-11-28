package com.lookahead.domain.security.token;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.client.SignInCognitoClient;
import com.lookahead.domain.db.store.signin.DevAccessCleanup;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.job.DevAccessCleanupJob;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.util.AccountMergeRules;
import java.net.ConnectException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;

/**
 * DLV-969 (owner, 2026-11-04): a refused or failed sign-in check carries its reason to the log (the
 * 401 entry point logs refusals by their code; failures are logged where they happen), never a
 * token, claim or address.
 */
class SignInTokenVerifierLoggingTest {
  final SignInProviderFixture fixture = new SignInProviderFixture();
  final SignInTokenVerifier verifier =
      new SignInTokenVerifier(
          SignInTokenVerifierTest.TRUST,
          new SignInCognitoClient(SignInTokenVerifierTest.TRUST, fixture.client, fixture.client));

  Jwt token(Map<String, Object> overrides) {
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
        .claim(
            "scope",
            "aws.cognito.signin.user.admin lookahead/account lookahead/content lookahead/support")
        .claims(c -> c.putAll(overrides))
        .build();
  }

  private String refusal(Map<String, Object> overrides) {
    try {
      verifier.verify(token(overrides), false);
      throw new AssertionError("expected a refusal");
    } catch (OAuth2AuthenticationException refused) {
      assertThat(refused.getError().getErrorCode()).isEqualTo("invalid_token");
      return refused.getError().getDescription();
    }
  }

  @Test
  void eachClaimRefusalCarriesItsReasonCode() {
    assertThat(refusal(Map.of("aud", "synthetic-client"))).isEqualTo("AUDIENCE_PRESENT");
    assertThat(
            refusal(Map.of("iss", "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Other")))
        .isEqualTo("ISSUER_MISMATCH");
    assertThat(refusal(Map.of("token_use", "id"))).isEqualTo("NOT_ACCESS_TOKEN");
    assertThat(refusal(Map.of("client_id", "wrongclient123"))).isEqualTo("CLIENT_MISMATCH");
    assertThat(refusal(Map.of("scope", "openid"))).isEqualTo("SCOPES_MISSING");
    assertThat(refusal(Map.of("auth_time", Instant.now().plusSeconds(600))))
        .isEqualTo("TOKEN_TIMES_INVALID");
    assertThat(refusal(Map.of("origin_jti", ""))).isEqualTo("CLAIM_INVALID");
    fixture.response =
        request ->
            GetUserResponse.builder()
                .username("learner")
                .userAttributes(
                    AttributeType.builder().name("sub").value("another-subject").build())
                .build();
    assertThat(refusal(Map.of())).isEqualTo("SUBJECT_MISMATCH");
  }

  @Test
  void aMalformedTokenAndAnUnusableProviderAnswerAreLoggedOnceWithTheirTypes() {
    try (var log = LogCapture.start()) {
      var noIssuer =
          Jwt.withTokenValue("synthetic-access-token")
              .header("alg", "RS256")
              .subject("synthetic-provider-subject")
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(300))
              .claim("token_use", "access")
              .build();
      for (int i = 0; i < 2; i++)
        assertThatThrownBy(() -> verifier.verify(noIssuer, false))
            .isInstanceOfSatisfying(
                OAuth2AuthenticationException.class,
                refused ->
                    assertThat(refused.getError().getDescription()).isEqualTo("TOKEN_MALFORMED"));
      fixture.response =
          request ->
              GetUserResponse.builder()
                  .username(null)
                  .userAttributes(
                      AttributeType.builder()
                          .name("sub")
                          .value("synthetic-provider-subject")
                          .build())
                  .build();
      assertThatThrownBy(() -> verifier.verify(token(Map.of()), false))
          .isInstanceOf(OAuth2AuthenticationException.class);
      fixture.response =
          request -> {
            throw new IllegalStateException("provider said secret-thing");
          };
      assertThatThrownBy(() -> verifier.verify(token(Map.of()), false))
          .isInstanceOf(SignInCheckUnavailableException.class);
      assertThat(log.lines("sign-in.check"))
          .extracting(line -> line.getFormattedMessage())
          .containsExactly(
              "Sign-in token check failed: the token's claims could not be checked; refused with"
                  + " 401 (NullPointerException)",
              "Sign-in token check failed: Cognito's answer could not be used; answered 503"
                  + " (IllegalStateException)");
      assertThat(log.lines("sign-in.check"))
          .allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
      log.containsNone("secret-thing", "synthetic-access-token");
    }
  }

  @Test
  void theApiConverterPassesRefusalCodesOnAndLogsFailuresOnce() {
    var signInService = mock(SignInService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var request = new MockHttpServletRequest();
    request.addHeader(SignInTokenConverter.PROOF_HEADER, "p".repeat(43));
    var converter = new SignInTokenConverter(verifier, signInService, request);
    fixture.response =
        r ->
            GetUserResponse.builder()
                .username("learner")
                .userAttributes(
                    AttributeType.builder().name("sub").value("synthetic-provider-subject").build())
                .build();
    var api =
        Jwt.withTokenValue("synthetic-access-token")
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
    try (var log = LogCapture.start()) {
      when(signInService.authenticate(any(), anyString()))
          .thenThrow(new AccountFailure(401, "SIGN_IN_REVOKED", "Sign in again."));
      assertThatThrownBy(() -> converter.convert(api))
          .isInstanceOfSatisfying(
              OAuth2AuthenticationException.class,
              refused ->
                  assertThat(refused.getError().getDescription()).isEqualTo("SIGN_IN_REVOKED"));
      reset(signInService);
      when(signInService.authenticate(any(), anyString()))
          .thenThrow(new AccountFailure(503, "ACCOUNT_STORAGE_UNAVAILABLE", "Retry."));
      assertThatThrownBy(() -> converter.convert(api))
          .isInstanceOf(SignInCheckUnavailableException.class);
      reset(signInService);
      when(signInService.authenticate(any(), anyString()))
          .thenThrow(
              SdkClientException.create(
                  "db.internal password=hunter2", new ConnectException("refused")));
      assertThatThrownBy(() -> converter.convert(api))
          .isInstanceOf(SignInCheckUnavailableException.class);
      assertThatThrownBy(() -> converter.convert(api))
          .isInstanceOf(SignInCheckUnavailableException.class);
      assertThat(log.lines("sign-in.check"))
          .extracting(line -> line.getFormattedMessage())
          .containsExactly(
              "API call answered 503: the sign-in check could not complete"
                  + " (ACCOUNT_STORAGE_UNAVAILABLE)",
              "API call answered 503: the sign-in check could not complete (SdkClientException <"
                  + " ConnectException)");
      assertThat(LogCapture.keys(log.lines("sign-in.check").getLast()))
          .containsEntry("reason", "UNEXPECTED")
          .containsEntry("error.type", "SdkClientException < ConnectException");
      log.containsNone("hunter2", "db.internal", "synthetic-access-token");
    }
  }

  @Test
  void anUnreadablePlanCardOnTheMergePageAndACleanupDatabaseFailureAreExplained() {
    try (var log = LogCapture.start()) {
      assertThat(AccountMergeRules.percent("{not json", "{}")).isZero();
      assertThat(AccountMergeRules.percent(null, "{}")).isZero();
      var line = log.only("merge.candidates");
      assertThat(line.getLevel()).isEqualTo(Level.WARN);
      assertThat(line.getFormattedMessage())
          .startsWith("A study plan's progress could not be read for the merge page (")
          .endsWith("); it shows 0%");
      var store = mock(DevAccessCleanup.class, withSettings().mockMaker(MockMakers.SUBCLASS));
      when(store.now())
          .thenThrow(SdkClientException.create("password=hunter2", new ConnectException("down")));
      new DevAccessCleanupJob(store).run();
      assertThat(log.only("admission.cleanup").getFormattedMessage())
          .isEqualTo(
              "Admission cleanup skipped this run: SdkClientException < ConnectException; the next"
                  + " run tries again");
      log.containsNone("hunter2");
    }
  }
}
