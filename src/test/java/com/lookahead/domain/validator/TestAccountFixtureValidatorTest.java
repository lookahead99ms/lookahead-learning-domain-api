package com.lookahead.domain.validator;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class TestAccountFixtureValidatorTest {
    @Test void fixturePermissionsNeverRequirePasswordSecretsAndCannotEscapeLocal() {
        var env = IdentityConnectionValidatorTest.local().withProperty("app.local-test.seed-enabled", "true").withProperty("app.local-test.author-enabled", "true");
        assertThatThrownBy(() -> TestAccountFixtureValidator.validate(env)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("accounts", "local-test");
        assertThatCode(() -> TestAccountFixtureValidator.validate(env)).doesNotThrowAnyException();
        env.withProperty("app.deployment-environment", "prod");
        assertThatThrownBy(() -> TestAccountFixtureValidator.validate(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void managedMaintenanceDoesNotRequireLegacyAuthorWrites() {
        var env=new MockEnvironment().withProperty("app.deployment-environment","local").withProperty("app.test-accounts.enabled","true").withProperty("app.local-test.author-enabled","true");
        env.setActiveProfiles("accounts","local-test");
        assertThatCode(()->TestAccountFixtureValidator.validate(env)).doesNotThrowAnyException();
        env.setActiveProfiles("accounts","local-test","dev");
        assertThatThrownBy(()->TestAccountFixtureValidator.validate(env)).isInstanceOf(RuntimeException.class);
    }
    /** DLV-1016: the bean still runs the same check when it is built; that is what refuses a start where the seed job does not exist. */
    @Test void theBeanRunsTheSameCheckWhenItIsBuilt() {
        var prod = new MockEnvironment().withProperty("app.deployment-environment", "prod").withProperty("app.local-test.author-enabled", "true");
        assertThatThrownBy(() -> new TestAccountFixtureValidator(prod)).isInstanceOf(IllegalStateException.class).hasMessage("Domain fixtures require local-test and local deployment only");
        assertThatThrownBy(() -> TestAccountFixtureValidator.validate(prod)).isInstanceOf(IllegalStateException.class).hasMessage("Domain fixtures require local-test and local deployment only");
        assertThatCode(() -> new TestAccountFixtureValidator(new MockEnvironment().withProperty("app.deployment-environment", "prod"))).doesNotThrowAnyException();
    }
}
