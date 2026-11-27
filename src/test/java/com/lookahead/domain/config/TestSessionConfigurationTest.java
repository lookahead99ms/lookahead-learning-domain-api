package com.lookahead.domain.config;

import com.lookahead.domain.db.store.testaccounts.TestAccountIdentities;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.security.config.SignInTokenTrust;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import com.lookahead.domain.service.testaccounts.TestAccountUpgradeMarkerService;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** DLV-969 S35, DLV-1016: the two test-session service beans exist in DEV only and are built from the checked sign-in settings. */
class TestSessionConfigurationTest {
    static final String SECRET = "synthetic-gateway-secret-of-at-least-32-characters";
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(TestSessionConfiguration.class)
            .withBean(TestAccountIdentities.class, () -> mock(TestAccountIdentities.class))
            .withBean(TestAccountModeService.class, () -> new TestAccountModeService(new MockEnvironment()))
            .withBean(SignInTokenTrust.class, () -> new SignInTokenTrust("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example", "us-east-2", "syntheticclient123456", "lookahead"))
            .withBean(SignInBffSecret.class, () -> new SignInBffSecret(SECRET));

    @Test void inDevTheServiceComparesTheGatewayBffSecretOfTheSignInSettings() {
        context.withPropertyValues("app.deployment-environment=dev").run(application -> {
            assertThat(application).hasNotFailed().hasBean("testSessionService").hasBean("testSessionUpgradeMarkers")
                    .hasSingleBean(TestSessionService.class).hasSingleBean(TestAccountUpgradeMarkerService.class);
            var sessions = application.getBean(TestSessionService.class);
            assertThat(sessions.secretMatches(SECRET)).isTrue();
            assertThat(sessions.secretMatches(SECRET + "x")).isFalse();
            assertThat(sessions.secretMatches(null)).isFalse();
        });
    }

    @ParameterizedTest @ValueSource(strings = {"local", "prod", ""})
    void outsideDevNeitherBeanExists(String mode) {
        context.withPropertyValues("app.deployment-environment=" + mode).run(application -> assertThat(application).hasNotFailed()
                .doesNotHaveBean(TestSessionService.class).doesNotHaveBean(TestAccountUpgradeMarkerService.class).doesNotHaveBean(TestSessionConfiguration.class));
    }
}
