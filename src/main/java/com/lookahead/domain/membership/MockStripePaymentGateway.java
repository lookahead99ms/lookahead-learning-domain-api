package com.lookahead.domain.membership;

import java.util.UUID;

/** Local provider simulator. Independent, durable payment records model an external payment's durability. */
public final class MockStripePaymentGateway implements PaymentGateway {
    private final MockPaymentStore store;
    private final String scenario;
    public MockStripePaymentGateway(MockPaymentStore store, String scenario) {
        this.store=store; this.scenario=scenario;
        if(!java.util.Set.of("success","decline","requires-action","network","rate-limit","server-error","timeout-after-success").contains(scenario))
            throw new IllegalArgumentException("Unsupported mock payment scenario");
        com.lookahead.domain.Events.success(com.lookahead.domain.Events.PAYMENT,"payment.provider").addKeyValue("provider.scenario",scenario)
            .log("Payments use the mock provider with scenario {}: no real charge is ever made",scenario);
    }
    public Result create(String key,int amount,String currency) {
        Result existing=findByKey(key);
        if(existing!=null) {
            if(existing.amountCents()!=amount||!existing.currency().equals(currency))throw new Failure("idempotency-mismatch",false);
            return existing;
        }
        if(java.util.Set.of("network","rate-limit","server-error").contains(scenario))throw new Failure(scenario,true);
        String state=switch(scenario){case "decline"->"requires_payment_method";case "requires-action"->"requires_action";default->"succeeded";};
        Result result=store.record(key,"pi_mock_"+UUID.randomUUID().toString().replace("-",""),amount,currency,state);
        if(scenario.equals("timeout-after-success"))throw new Failure("timeout",true);
        return result;
    }
    private Result findByKey(String key) { return store.findByKey(key); }
    public Result status(String id) {
        Result result=store.findById(id);
        if(result==null)throw new Failure("not-found",false);
        return result;
    }
    public Result confirm(String id,String key) { return status(id); }
}
