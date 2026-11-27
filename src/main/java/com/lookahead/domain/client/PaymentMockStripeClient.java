package com.lookahead.domain.client;

import com.lookahead.domain.db.store.membership.PaymentMock;
import com.lookahead.domain.log.EventLog;
import java.util.Set;
import java.util.UUID;

/** Local provider simulator. Independent, durable payment records model an external payment's durability. */
// Exception, decided 2026-11-25 (DLV-1016): a client that calls no payment provider and depends on a store. It is the
// stand-in for the provider in Local and DEV, the only environments the payment settings allow (PaymentConfigValidator
// refuses start-up in any other): it keeps its own payment records in PaymentMock and answers from them as a provider
// would, so the membership service runs the code a real provider client would get. PaymentStripeClient, the real
// client, is not a bean yet.
public final class PaymentMockStripeClient implements PaymentClient {
    private final PaymentMock store;
    private final String scenario;
    public PaymentMockStripeClient(PaymentMock store, String scenario) {
        this.store=store; this.scenario=scenario;
        if(!Set.of("success","decline","requires-action","network","rate-limit","server-error","timeout-after-success").contains(scenario))
            throw new IllegalArgumentException("Unsupported mock payment scenario");
        EventLog.success(EventLog.PAYMENT,"payment.provider").addKeyValue("provider.scenario",scenario)
            .log("Payments use the mock provider with scenario {}: no real charge is ever made",scenario);
    }
    public Result create(String key,int amount,String currency) {
        Result existing=findByKey(key);
        if(existing!=null) {
            if(existing.amountCents()!=amount||!existing.currency().equals(currency))throw new Failure("idempotency-mismatch",false);
            return existing;
        }
        if(Set.of("network","rate-limit","server-error").contains(scenario))throw new Failure(scenario,true);
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
