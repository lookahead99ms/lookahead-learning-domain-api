package com.lookahead.domain.testaccounts;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class TestAccountPolicyTest {
    static MockEnvironment local() {
        var env = new MockEnvironment().withProperty("app.deployment-environment", "local")
                .withProperty("app.test-accounts.enabled", "true");
        env.setActiveProfiles("accounts", "local-test");
        return env;
    }
    @Test void scenarioWritesRefuseBeforeAnyDatabaseCallWhenDisabled() {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var fixture=new ScenarioPurchases(jdbc,new tools.jackson.databind.ObjectMapper(),new TestAccountPolicy(new MockEnvironment()));
        assertThatThrownBy(()->fixture.populate(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),LearnerScenario.of(17),java.time.Instant.now()))
                .isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
    @Test void onlyExplicitLocalFixtureConfigurationIsEnabled() {
        assertThatCode(() -> new TestAccountPolicy(local()).requireEnabled()).doesNotThrowAnyException();
        assertThatThrownBy(() -> new TestAccountPolicy(new MockEnvironment()).requireEnabled())
                .isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        for (String value : new String[]{"dev", "prod", "production", "LOCAL", ""})
            assertThatThrownBy(() -> new TestAccountPolicy(local().withProperty("app.deployment-environment", value)).requireEnabled())
                    .isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        for (String profile : new String[]{"prod", "production", "dev"}) {
            var env = local(); env.addActiveProfile(profile);
            assertThatThrownBy(() -> new TestAccountPolicy(env).requireEnabled())
                    .isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        }
        var noAccounts = local(); noAccounts.setActiveProfiles("local-test");
        assertThatThrownBy(() -> new TestAccountPolicy(noAccounts).requireEnabled())
                .isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        assertThatThrownBy(() -> new TestAccountPolicy(local().withProperty("app.test-accounts.enabled", "false")).requireEnabled())
                .isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
    }
    static MockEnvironment cloud(String mode) {
        return new MockEnvironment().withProperty("app.deployment-environment", mode)
                .withProperty("app.test-accounts.identity", "cognito")
                .withProperty("app.test-accounts.pool-id", "us-east-2_Example");
    }
    @Test void modeMatrixEnablesTheCloudPathOnlyForDevCognitoWithAPoolAndNeverTheRosterThere() {
        var dev = new TestAccountPolicy(cloud("dev"));
        assertThat(dev.enabled()).isTrue(); assertThat(dev.cloud()).isTrue(); assertThat(dev.local()).isFalse();
        assertThatCode(dev::requireEnabled).doesNotThrowAnyException();
        assertThatThrownBy(dev::requireLocal).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class)
                .hasMessageContaining("disabled");
        for (String mode : new String[]{"prod", "production", "local", "", "DEV"}) {
            var policy = new TestAccountPolicy(cloud(mode));
            assertThat(policy.cloud()).as(mode).isFalse(); assertThat(policy.enabled()).as(mode).isFalse();
        }
        for (String identity : new String[]{"local", "cognito"}) {
            var policy = new TestAccountPolicy(new MockEnvironment().withProperty("app.deployment-environment", "dev")
                    .withProperty("app.test-accounts.identity", identity));
            assertThat(policy.cloud()).as("dev without a pool, identity " + identity).isFalse();
            assertThat(policy.enabled()).isFalse();
        }
        assertThat(new TestAccountPolicy(cloud("dev").withProperty("app.test-accounts.pool-id", " ")).cloud()).isFalse();
        // The Local rule is unchanged by the identity property, and an unknown identity never starts.
        var localCognito = local().withProperty("app.test-accounts.identity", "cognito");
        var policy = new TestAccountPolicy(localCognito);
        assertThat(policy.local()).isTrue(); assertThat(policy.cloud()).isFalse(); assertThat(policy.enabled()).isTrue();
        assertThat(new TestAccountPolicy(local().withProperty("app.test-accounts.identity", "local")).local()).isTrue();
        assertThatThrownBy(() -> new TestAccountPolicy(local().withProperty("app.test-accounts.identity", "Cognito")))
                .isInstanceOf(IllegalStateException.class);
    }
}
