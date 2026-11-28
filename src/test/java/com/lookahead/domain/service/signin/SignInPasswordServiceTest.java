package com.lookahead.domain.service.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.lookahead.domain.client.SignInCognitoClient;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.SignInIdentity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.mockito.Mockito;

/**
 * A person's own password change: rules first, then SignInService around the provider change, then
 * the provider sign-out.
 */
class SignInPasswordServiceTest {
  static final String CURRENT = "current-password-123", NEXT = "a-new-password-4567";
  final SignInService signInService =
      mock(SignInService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
  final SignInCognitoClient cognito =
      mock(SignInCognitoClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));
  final SignInPasswordService passwords = new SignInPasswordService(signInService, cognito);
  final SignInIdentity identity =
      new SignInIdentity(
          "issuer",
          "subject",
          "family",
          "learner",
          "Learner",
          Instant.parse("2026-11-25T10:00:00Z"),
          null);

  @Test
  void aPasswordThatBreaksTheRulesIsRefusedBeforeTheTokenIsReadOrAnythingIsCalled() {
    for (String[] pair :
        new String[][] {
          {"", NEXT}, {"x".repeat(129), NEXT}, {CURRENT, "too-short"}, {CURRENT, "y".repeat(129)}
        })
      assertThatThrownBy(() ->
              passwords.change(
                  identity,
                  "proof",
                  pair[0],
                  pair[1],
                  () -> {
                    throw new AssertionError("the token must not be read");
                  }))
          .isInstanceOfSatisfying(
              AccountFailure.class,
              f -> {
                assertThat(f.status()).isEqualTo(422);
                assertThat(f.code()).isEqualTo("INVALID_PASSWORD");
              });
    assertThatThrownBy(() ->
            passwords.change(
                identity,
                "proof",
                NEXT,
                NEXT,
                () -> {
                  throw new AssertionError("the token must not be read");
                }))
        .isInstanceOfSatisfying(
            AccountFailure.class,
            f -> {
              assertThat(f.status()).isEqualTo(422);
              assertThat(f.code()).isEqualTo("PASSWORD_UNCHANGED");
            });
    verifyNoInteractions(signInService, cognito);
  }

  @Test
  void signInServiceRunsTheProviderChangeAndTheProviderSignOutComesLast() {
    passwords.change(identity, "proof", CURRENT, NEXT, () -> "synthetic-access-token");
    var change = ArgumentCaptor.forClass(Runnable.class);
    var order = inOrder(signInService, cognito);
    order.verify(signInService).changeCredentials(eq(identity), eq("proof"), change.capture());
    order.verify(cognito).globalSignOut("synthetic-access-token");
    verify(cognito, never()).changePassword(any(), any(), any());
    change.getValue().run();
    verify(cognito).changePassword("synthetic-access-token", CURRENT, NEXT);
  }

  @Test
  void aRefusalFromSignInServiceStopsBeforeTheProviderSignOut() {
    Mockito.doThrow(new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in again."))
        .when(signInService)
        .changeCredentials(any(), any(), any());
    assertThatThrownBy(() ->
            passwords.change(identity, "proof", CURRENT, NEXT, () -> "synthetic-access-token"))
        .isInstanceOf(AccountFailure.class);
    verify(cognito, never()).globalSignOut(any());
  }
}
