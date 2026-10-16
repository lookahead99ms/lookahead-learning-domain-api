package com.lookahead.domain.membership;

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
    public Result create(String key,int amount,String currency){try{return result(client.v1().paymentIntents().create(PaymentIntentCreateParams.builder().setAmount((long)amount).setCurrency(currency.toLowerCase(java.util.Locale.ROOT)).build(),RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build()));}catch(StripeException e){throw failure(e);}}
    public Result confirm(String id,String key){try{return result(client.v1().paymentIntents().confirm(id,PaymentIntentConfirmParams.builder().build(),RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build()));}catch(StripeException e){throw failure(e);}}
    public Result status(String id){try{return result(client.v1().paymentIntents().retrieve(id));}catch(StripeException e){throw failure(e);}}
}
