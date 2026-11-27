package com.lookahead.domain.validator;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DLV-1016: the payment check, moved from PaymentConfigValues.from: the same one refusal with the same text. */
class PaymentConfigValidatorTest {
    private static MockEnvironment settings(String deployment, String provider) {
        var environment = new MockEnvironment();
        if (deployment != null) environment.withProperty("app.deployment-environment", deployment);
        if (provider != null) environment.withProperty("app.payments.provider", provider);
        return environment;
    }

    @Test void localAndDevWithTheMockProviderGiveTheScenario() {
        assertThat(PaymentConfigValidator.validated(settings(null, null)).mockScenario()).as("unset: Local, the mock provider, success").isEqualTo("success");
        assertThat(PaymentConfigValidator.validated(settings("local", "mock")).mockScenario()).isEqualTo("success");
        assertThat(PaymentConfigValidator.validated(settings("dev", "mock").withProperty("app.payments.mock-scenario", "declined")).mockScenario()).isEqualTo("declined");
    }

    @Test void anyOtherEnvironmentOrProviderRefusesStartupWithTheOneFixedText() {
        for (String[] wrong : new String[][] {{"prod", "mock"}, {"prod", null}, {"", "mock"}, {"test", "mock"}, {"local", "stripe"}, {"dev", "stripe"}, {"dev", "Mock"}, {"prod", "stripe"}})
            assertThatThrownBy(() -> PaymentConfigValidator.validated(settings(wrong[0], wrong[1]))).as(wrong[0] + " / " + wrong[1])
                    .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Payments are approved only for Local/DEV mock operation");
    }
}
