package com.lookahead.domain.util;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.model.SignInIdentity;
import org.junit.jupiter.api.Test;

/**
 * DLV-969 S36 identity rules shared by the registry and the import; the store itself is proven by
 * SignInStoreDatabaseTest.
 */
class SignInIdentityRulesTest {
  @Test
  void theProviderComesFromTheIssuerAndTheCognitoUsername() {
    assertThat(SignInIdentityRules.provider(SignInIdentity.TEST_ISSUER, "Google_1"))
        .isEqualTo("test");
    assertThat(SignInIdentityRules.provider("https://issuer.invalid", "Google_1"))
        .isEqualTo("google");
    assertThat(SignInIdentityRules.provider("https://issuer.invalid", "google_1"))
        .isEqualTo("google");
    assertThat(SignInIdentityRules.provider("https://issuer.invalid", "googler@example.invalid"))
        .isEqualTo("cognito");
    assertThat(SignInIdentityRules.provider("https://issuer.invalid", null)).isEqualTo("cognito");
  }

  @Test
  void emailsAreComparedInLowerCase() {
    assertThat(SignInIdentity.normalizeEmail("Paula.X@Gmail.COM")).isEqualTo("paula.x@gmail.com");
    assertThat(SignInIdentity.normalizeEmail("plain@example.invalid"))
        .isEqualTo("plain@example.invalid");
  }
}
