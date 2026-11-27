package com.lookahead.domain.config;

import com.lookahead.domain.client.PaymentMockStripeClient;
import com.lookahead.domain.client.PaymentOwnerApprovalClient;
import com.lookahead.domain.client.PaymentClient;
import com.lookahead.domain.db.store.membership.PaymentMock;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/** DLV-969 (owner, 2026-11-04): DEV purchases are owner-approved requests; Local pays the mock at once; PROD has none. */
class PaymentConfigurationTest {
    /** DLV-1016: the conditions on the bean methods choose the client, so the test asks a context, not a method. */
    private static ApplicationContextRunner context(String environment) {
        var runner = new ApplicationContextRunner().withUserConfiguration(PaymentConfiguration.class)
                .withBean(PaymentMock.class, () -> mock(PaymentMock.class, withSettings().mockMaker(MockMakers.SUBCLASS)));
        return environment == null ? runner : runner.withPropertyValues("app.deployment-environment=" + environment);
    }
    @Test void devAsksTheOwnerLocalPaysTheMockAndProdIsRefused() {
        context("dev").run(context -> { assertThat(context).hasSingleBean(PaymentClient.class); assertThat(context.getBean("paymentClient")).isInstanceOf(PaymentOwnerApprovalClient.class); });
        context("local").run(context -> { assertThat(context).hasSingleBean(PaymentClient.class); assertThat(context.getBean("paymentClient")).isInstanceOf(PaymentMockStripeClient.class); });
        context(null).run(context -> assertThat(context.getBean("paymentClient")).isInstanceOf(PaymentMockStripeClient.class));
        context("prod").run(context -> assertThat(context).hasFailed().getFailure().rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessage("Payments are approved only for Local/DEV mock operation"));
    }
}
