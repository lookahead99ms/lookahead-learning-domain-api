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
        var mock=new MockStripePaymentGateway(jdbc,manager,environment.getProperty("app.payments.mock-scenario","success"));
        if(!deployment.equals("dev"))return mock;
        // DLV-969 (owner, 2026-11-04): in DEV a test account's purchase is a request the owner or an admin decides.
        com.lookahead.domain.Events.success(com.lookahead.domain.Events.PAYMENT,"payment.provider").addKeyValue("provider.mode","owner-approval")
            .log("DEV purchases are requests from test accounts: nothing is paid until the owner or an admin approves one");
        return new OwnerApprovalPaymentGateway(mock);
    }
}
