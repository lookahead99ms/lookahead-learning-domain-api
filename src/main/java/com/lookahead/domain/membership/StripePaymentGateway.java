package com.lookahead.domain.membership;

import com.lookahead.domain.Events;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentConfirmParams;

/** Adapter candidate only: not registered as a Spring bean; Local/DEV always selects the mock. */
public final class StripePaymentGateway implements PaymentGateway {
    private final StripeClient client;
    public StripePaymentGateway(StripeClient client){this.client=client;}
    private Result result(PaymentIntent p){return new Result(p.getId(),p.getStatus(),Math.toIntExact(p.getAmount()),p.getCurrency().toUpperCase(java.util.Locale.ROOT));}
    private Failure failure(StripeException e){Integer status=e.getStatusCode();return new Failure("stripe-error",e instanceof com.stripe.exception.ApiConnectionException||status!=null&&(status==429||status>=500));}
    /** One lookahead.upstream line per Stripe call: the exception type, HTTP status and Stripe's error code only. */
    private interface Call { PaymentIntent run() throws StripeException; }
    private Result call(String operation,Call call){
        long started=Events.started();
        try {var result=result(call.run());Events.upstreamDone("Stripe",operation,started);return result;}
        catch(StripeException e){Events.upstreamFailed("Stripe",operation,started,e,e.getStatusCode(),Events.safe(e.getCode()));throw failure(e);}
    }
    public Result create(String key,int amount,String currency){return call("CreatePaymentIntent",()->client.v1().paymentIntents().create(PaymentIntentCreateParams.builder().setAmount((long)amount).setCurrency(currency.toLowerCase(java.util.Locale.ROOT)).build(),RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build()));}
    public Result confirm(String id,String key){return call("ConfirmPaymentIntent",()->client.v1().paymentIntents().confirm(id,PaymentIntentConfirmParams.builder().build(),RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build()));}
    public Result status(String id){return call("RetrievePaymentIntent",()->client.v1().paymentIntents().retrieve(id));}
}
