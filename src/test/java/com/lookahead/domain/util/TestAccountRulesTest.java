package com.lookahead.domain.util;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** DLV-1016: the mode rules that were written in TestAccountModeService; the policy bean and the two start-up validators ask the same functions. */
class TestAccountRulesTest {
    private static MockEnvironment local() {
        var environment = new MockEnvironment().withProperty("app.deployment-environment", "local").withProperty("app.test-accounts.enabled", "true");
        environment.setActiveProfiles("accounts", "local-test");
        return environment;
    }

    @Test void theLocalRuleNeedsALocalDeploymentTheFixtureProfilesAndTheSwitch() {
        assertThat(TestAccountRules.local(local())).isTrue();
        assertThatCode(() -> TestAccountRules.requireLocal(local())).doesNotThrowAnyException();
        var dev = local(); dev.setActiveProfiles("accounts", "local-test", "dev");
        var off = local().withProperty("app.test-accounts.enabled", "false");
        var cloud = local().withProperty("app.deployment-environment", "dev");
        var plain = local(); plain.setActiveProfiles("accounts");
        for (var environment : new MockEnvironment[] {dev, off, cloud, plain, new MockEnvironment()}) {
            assertThat(TestAccountRules.local(environment)).isFalse();
            assertThat(new TestAccountModeService(environment).local()).isFalse();
            var refused = catchThrowableOfType(AccountFailure.class, () -> TestAccountRules.requireLocal(environment));
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.code()).isEqualTo("TEST_ACCOUNTS_DISABLED");
            assertThat(refused).hasMessage("Test account maintenance is disabled.");
        }
    }

    @Test void anUnknownIdentityRefusesTheStartWithTheSameWordsForTheRuleAndThePolicyBean() {
        assertThat(TestAccountRules.identity(new MockEnvironment())).isEqualTo("local");
        assertThatCode(() -> TestAccountRules.requireKnownIdentity(new MockEnvironment().withProperty("app.test-accounts.identity", "dev"))).doesNotThrowAnyException();
        var unknown = new MockEnvironment().withProperty("app.test-accounts.identity", "prod");
        assertThatThrownBy(() -> TestAccountRules.requireKnownIdentity(unknown)).isInstanceOf(IllegalStateException.class).hasMessage("app.test-accounts.identity must be local or dev");
        assertThatThrownBy(() -> new TestAccountModeService(unknown)).isInstanceOf(IllegalStateException.class).hasMessage("app.test-accounts.identity must be local or dev");
    }
}
