package com.lookahead.domain.membership;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PaymentConfiguration {
    @Bean PaymentGateway paymentGateway(JdbcTemplate jdbc,PlatformTransactionManager manager,Environment environment) {
        String deployment=environment.getProperty("app.deployment-environment", "local");
        String provider=environment.getProperty("app.payments.provider","mock");
        if(!java.util.Set.of("local","dev").contains(deployment)||!provider.equals("mock"))
            throw new IllegalStateException("Payments are approved only for Local/DEV mock operation");
        return new MockStripePaymentGateway(jdbc,manager,environment.getProperty("app.payments.mock-scenario","success"));
    }
}
