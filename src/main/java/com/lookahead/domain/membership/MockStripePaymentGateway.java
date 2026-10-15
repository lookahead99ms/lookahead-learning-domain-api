package com.lookahead.domain.membership;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Local provider simulator. Independent transactions model an external payment's durability. */
public final class MockStripePaymentGateway implements PaymentGateway {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String scenario;
    public MockStripePaymentGateway(JdbcTemplate jdbc, PlatformTransactionManager manager, String scenario) {
        this.jdbc=jdbc; this.scenario=scenario;
        if(!java.util.Set.of("success","decline","requires-action","network","rate-limit","server-error","timeout-after-success").contains(scenario))
            throw new IllegalArgumentException("Unsupported mock payment scenario");
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Result create(String key,int amount,String currency) {
        Result existing=findByKey(key);
        if(existing!=null) {
            if(existing.amountCents()!=amount||!existing.currency().equals(currency))throw new Failure("idempotency-mismatch",false);
            return existing;
        }
        if(java.util.Set.of("network","rate-limit","server-error").contains(scenario))throw new Failure(scenario,true);
        String state=switch(scenario){case "decline"->"requires_payment_method";case "requires-action"->"requires_action";default->"succeeded";};
        Result result=tx.execute(s->{
            jdbc.update("INSERT INTO membership_mock_payments VALUES (?,?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING",key,"pi_mock_"+UUID.randomUUID().toString().replace("-",""),amount,currency,state);
            return findByKey(key);
        });
        if(scenario.equals("timeout-after-success"))throw new Failure("timeout",true);
        return result;
    }
    private Result findByKey(String key) {
        return jdbc.query("SELECT * FROM membership_mock_payments WHERE idempotency_key=?",(r,n)->new Result(r.getString("payment_id"),r.getString("status"),r.getInt("amount_cents"),r.getString("currency")),key).stream().findFirst().orElse(null);
    }
    public Result status(String id) {
        return jdbc.query("SELECT * FROM membership_mock_payments WHERE payment_id=?",(r,n)->new Result(r.getString("payment_id"),r.getString("status"),r.getInt("amount_cents"),r.getString("currency")),id).stream().findFirst().orElseThrow(()->new Failure("not-found",false));
    }
    public Result confirm(String id,String key) { return status(id); }
}
