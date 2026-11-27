package com.lookahead.domain.config;

import com.lookahead.domain.client.PaymentMockStripeClient;
import com.lookahead.domain.client.PaymentOwnerApprovalClient;
import com.lookahead.domain.client.PaymentClient;
import com.lookahead.domain.db.store.membership.PaymentMock;
import com.lookahead.domain.validator.PaymentConfigValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/**
 * The payment client. DLV-1016: which client an environment gets is decided by the conditions on the two bean methods
 * (DEV: purchases wait for the owner's approval; everywhere else the mock provider directly), not inside one method.
 * Both are the bean {@code paymentClient}. The checked values are a bean of their own; {@link PaymentConfigValidator}
 * refuses anything but Local or DEV with the mock provider.
 */
@Configuration
public class PaymentConfiguration {
    static final String DEV = "'${app.deployment-environment:local}' == 'dev'";
    static final String NOT_DEV = "'${app.deployment-environment:local}' != 'dev'";

    @Bean PaymentConfigValues paymentConfigValues(Environment environment) { return PaymentConfigValidator.validated(environment); }

    /** DLV-970: the mock provider's records come from the store ({@link com.lookahead.domain.db.store.membership.PaymentMockStore}). */
    @Bean("paymentClient") @ConditionalOnExpression(NOT_DEV)
    PaymentClient paymentMockClient(PaymentMock store, PaymentConfigValues values) { return new PaymentMockStripeClient(store, values.mockScenario()); }

    /** DEV: the same mock provider behind the owner's approval. */
    @Bean("paymentClient") @ConditionalOnExpression(DEV)
    PaymentClient paymentOwnerApprovalClient(PaymentMock store, PaymentConfigValues values) {
        return PaymentOwnerApprovalClient.dev(new PaymentMockStripeClient(store, values.mockScenario()));
    }
}
