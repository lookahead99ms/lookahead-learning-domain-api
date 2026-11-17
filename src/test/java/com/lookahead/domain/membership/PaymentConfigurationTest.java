package com.lookahead.domain.membership;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/** DLV-969 (owner, 2026-11-04): DEV purchases are owner-approved requests; Local pays the mock at once; PROD has none. */
class PaymentConfigurationTest {
    private static PaymentGateway gateway(String environment) {
        var env = new MockEnvironment();
        if (environment != null) env.setProperty("app.deployment-environment", environment);
        return new PaymentConfiguration().paymentGateway(mock(MockPaymentStore.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)), env);
    }
    @Test void devAsksTheOwnerLocalPaysTheMockAndProdIsRefused() {
        assertThat(gateway("dev")).isInstanceOf(OwnerApprovalPaymentGateway.class);
        assertThat(gateway("local")).isInstanceOf(MockStripePaymentGateway.class);
        assertThat(gateway(null)).isInstanceOf(MockStripePaymentGateway.class);
        assertThatThrownBy(() -> gateway("prod")).isInstanceOf(IllegalStateException.class);
    }
}
