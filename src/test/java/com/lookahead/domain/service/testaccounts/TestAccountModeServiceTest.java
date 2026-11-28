package com.lookahead.domain.service.testaccounts;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.db.store.testaccounts.TestAccountScenarioPurchase;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.TestAccountScenario;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class TestAccountModeServiceTest {
  static MockEnvironment local() {
    var env =
        new MockEnvironment()
            .withProperty("app.deployment-environment", "local")
            .withProperty("app.test-accounts.enabled", "true");
    env.setActiveProfiles("accounts", "local-test");
    return env;
  }

  @Test
  void scenarioWritesRefuseBeforeAnyDatabaseCallWhenDisabled() {
    var jdbc =
        Mockito.mock(
            TestAccountScenarioPurchase.class,
            Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    var fixture =
        new TestAccountScenarioPurchaseService(
            jdbc, new ObjectMapper(), new TestAccountModeService(new MockEnvironment()));
    assertThatThrownBy(() ->
            fixture.populate(
                UUID.randomUUID(), UUID.randomUUID(), TestAccountScenario.of(17), Instant.now()))
        .isInstanceOf(AccountFailure.class);
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void onlyExplicitLocalFixtureConfigurationIsEnabled() {
    assertThatCode(() -> new TestAccountModeService(local()).requireEnabled())
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new TestAccountModeService(new MockEnvironment()).requireEnabled())
        .isInstanceOf(AccountFailure.class);
    for (String value : new String[] {"dev", "prod", "production", "LOCAL", ""})
      assertThatThrownBy(() ->
              new TestAccountModeService(local().withProperty("app.deployment-environment", value))
                  .requireEnabled())
          .isInstanceOf(AccountFailure.class);
    for (String profile : new String[] {"prod", "production", "dev"}) {
      var env = local();
      env.addActiveProfile(profile);
      assertThatThrownBy(() -> new TestAccountModeService(env).requireEnabled())
          .isInstanceOf(AccountFailure.class);
    }
    var noAccounts = local();
    noAccounts.setActiveProfiles("local-test");
    assertThatThrownBy(() -> new TestAccountModeService(noAccounts).requireEnabled())
        .isInstanceOf(AccountFailure.class);
    assertThatThrownBy(() ->
            new TestAccountModeService(local().withProperty("app.test-accounts.enabled", "false"))
                .requireEnabled())
        .isInstanceOf(AccountFailure.class);
  }

  static MockEnvironment cloud(String mode) {
    return new MockEnvironment()
        .withProperty("app.deployment-environment", mode)
        .withProperty("app.test-accounts.identity", "dev");
  }

  /**
   * DLV-969 S35: database-only DEV test accounts need exactly dev + identity dev; cognito is no
   * longer a mode.
   */
  @Test
  void modeMatrixEnablesTheCloudPathOnlyForDevWithIdentityDev() {
    var dev = new TestAccountModeService(cloud("dev"));
    assertThat(dev.enabled()).isTrue();
    assertThat(dev.cloud()).isTrue();
    assertThat(dev.local()).isFalse();
    assertThatCode(dev::requireEnabled).doesNotThrowAnyException();
    assertThatCode(dev::requireCloud).doesNotThrowAnyException();
    assertThatThrownBy(dev::requireLocal)
        .isInstanceOf(AccountFailure.class)
        .hasMessageContaining("disabled");
    for (String mode : new String[] {"prod", "production", "local", "", "DEV"}) {
      var policy = new TestAccountModeService(cloud(mode));
      assertThat(policy.cloud()).as(mode).isFalse();
      assertThat(policy.enabled()).as(mode).isFalse();
      assertThatThrownBy(policy::requireCloud).isInstanceOf(AccountFailure.class);
    }
    var devLocal =
        new TestAccountModeService(
            new MockEnvironment()
                .withProperty("app.deployment-environment", "dev")
                .withProperty("app.test-accounts.identity", "local"));
    assertThat(devLocal.cloud()).isFalse();
    assertThat(devLocal.enabled()).isFalse();
    // The Local rule is unchanged by the identity property, and an unknown identity (cognito
    // included) never starts.
    var localDev = local().withProperty("app.test-accounts.identity", "dev");
    var policy = new TestAccountModeService(localDev);
    assertThat(policy.local()).isTrue();
    assertThat(policy.cloud()).isFalse();
    assertThat(policy.enabled()).isTrue();
    assertThat(
            new TestAccountModeService(local().withProperty("app.test-accounts.identity", "local"))
                .local())
        .isTrue();
    for (String unknown : new String[] {"cognito", "Dev"})
      assertThatThrownBy(() ->
              new TestAccountModeService(
                  local().withProperty("app.test-accounts.identity", unknown)))
          .isInstanceOf(IllegalStateException.class);
  }
}
