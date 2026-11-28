package com.lookahead.domain.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** The operator's credentials on the internal test-account routes. */
class TestAccountOperatorVerifierTest {
  static final String SECRET = "synthetic-test-account-operator-credential-only";
  final TestAccountModeService policy = mock(TestAccountModeService.class);
  final TestAccountOperatorVerifier verifier =
      new TestAccountOperatorVerifier(policy, SECRET.getBytes(StandardCharsets.UTF_8));

  static UsernamePasswordAuthenticationToken basic(String name, String secret) {
    return UsernamePasswordAuthenticationToken.unauthenticated(name, secret);
  }

  @Test
  void theOperatorNameAndSecretGiveTheOperatorRole() {
    var verified = verifier.authenticate(basic("lookahead-test-account-operator", SECRET));
    assertThat(verified.isAuthenticated()).isTrue();
    assertThat(verified.getName()).isEqualTo("lookahead-test-account-operator");
    assertThat(verified.getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_TEST_ACCOUNT_OPERATOR");
    assertThat(verifier.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
  }

  @Test
  void aWrongNameOrSecretIsRefused() {
    assertThatThrownBy(() -> verifier.authenticate(basic("someone-else", SECRET)))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Invalid operator credentials");
    assertThatThrownBy(
            () -> verifier.authenticate(basic("lookahead-test-account-operator", SECRET + "x")))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void aSecretShorterThan32CharactersNeverVerifies() {
    var weak = new TestAccountOperatorVerifier(policy, "short".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> weak.authenticate(basic("lookahead-test-account-operator", "short")))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void outsideLocalNothingVerifies() {
    doThrow(new AccountFailure(404, "TEST_ACCOUNTS_DISABLED", "disabled"))
        .when(policy)
        .requireLocal();
    assertThatThrownBy(
            () -> verifier.authenticate(basic("lookahead-test-account-operator", SECRET)))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Test account maintenance is disabled");
  }
}
