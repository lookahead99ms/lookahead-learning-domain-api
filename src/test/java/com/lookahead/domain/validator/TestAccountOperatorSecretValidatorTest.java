package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The operator secret is checked at start only when test accounts are enabled. */
class TestAccountOperatorSecretValidatorTest {
    static final String SECRET = "synthetic-test-account-operator-credential-only";

    /** Test accounts enabled where the Local rule holds: a local deployment under the accounts and local-test profiles. */
    static MockEnvironment on() {
        var environment = new MockEnvironment().withProperty("app.deployment-environment", "local").withProperty("app.test-accounts.enabled", "true");
        environment.setActiveProfiles("accounts", "local-test");
        return environment;
    }

    @Test void withTestAccountsOffTheSecretIsReturnedUnchecked() {
        // Neither environment is Local: with test accounts off the Local rule is not asked, so nothing is refused.
        assertThat(TestAccountOperatorSecretValidator.validated(new MockEnvironment())).isEmpty();
        assertThat(TestAccountOperatorSecretValidator.validated(new MockEnvironment().withProperty("app.test-accounts.operator-secret", "short"))).isEqualTo("short");
    }

    @Test void withTestAccountsOnASeparateSecretOfAtLeast32CharactersIsRequired() {
        assertThat(TestAccountOperatorSecretValidator.validated(on().withProperty("app.test-accounts.operator-secret", SECRET))).isEqualTo(SECRET);
        // The Local rule is asked first: the same good secret outside Local is refused as disabled, not as a bad secret.
        var outsideLocal = new MockEnvironment().withProperty("app.test-accounts.enabled", "true").withProperty("app.test-accounts.operator-secret", SECRET);
        var disabled = catchThrowableOfType(AccountFailure.class, () -> TestAccountOperatorSecretValidator.validated(outsideLocal));
        assertThat(disabled.status()).isEqualTo(403);
        assertThat(disabled.code()).isEqualTo("TEST_ACCOUNTS_DISABLED");
        assertThat(disabled).hasMessage("Test account maintenance is disabled.");
        for (var bad : new MockEnvironment[] {
                on().withProperty("app.test-accounts.operator-secret", "short"),
                on().withProperty("app.test-accounts.operator-secret", SECRET).withProperty("app.identity.verifier-secret", SECRET),
                on().withProperty("app.test-accounts.operator-secret", SECRET).withProperty("app.oauth.client-secret", SECRET)})
            assertThatThrownBy(() -> TestAccountOperatorSecretValidator.validated(bad)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Test account maintenance requires a separate operator secret.");
    }
}
