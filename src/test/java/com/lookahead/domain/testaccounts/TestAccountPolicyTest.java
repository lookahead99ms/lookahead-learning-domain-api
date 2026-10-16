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
}
