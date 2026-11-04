package com.lookahead.domain.membership;

import java.util.Objects;

/**
 * DLV-969 (owner, 2026-11-04): DEV checkout. A test account's purchase is a request: nothing is paid and no access is
 * given while it waits ({@link #AWAITING}, no provider payment id). When the owner or an admin approves it, the mock
 * provider takes the payment under the request's own key ({@link #approve}), so approving twice never pays twice.
 * Local and production never use this gateway ({@link PaymentConfiguration}).
 */
public final class OwnerApprovalPaymentGateway implements PaymentGateway {
    /** provider_status of a request that waits for a decision. */
    public static final String AWAITING = "awaiting_owner_approval";
    /** provider_status of a declined request (status failed). */
    public static final String DECLINED = "declined_by_owner";
    private final PaymentGateway provider;
    public OwnerApprovalPaymentGateway(PaymentGateway provider) { this.provider = Objects.requireNonNull(provider); }
    /** A request: no payment id and no charge, the amount the account agreed to. */
    @Override public Result create(String key, int amountCents, String currency) { return new Result(null, AWAITING, amountCents, currency); }
    /** Approved: the provider pays now, idempotently under the request's key. */
    public Result approve(String key, int amountCents, String currency) { return provider.create(key, amountCents, currency); }
    @Override public Result confirm(String id, String key) { return provider.confirm(id, key); }
    @Override public Result status(String id) { return provider.status(id); }
}
