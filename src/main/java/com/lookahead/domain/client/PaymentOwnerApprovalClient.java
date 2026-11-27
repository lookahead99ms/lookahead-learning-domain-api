package com.lookahead.domain.client;

import com.lookahead.domain.log.EventLog;
import java.util.Objects;

/**
 * DLV-969 (owner, 2026-11-04): DEV checkout. A test account's purchase is a request: nothing is paid and no access is
 * given while it waits ({@link #AWAITING}, no provider payment id). When the owner or an admin approves it, the mock
 * provider takes the payment under the request's own key ({@link #approve}), so approving twice never pays twice.
 * Local and production never use this client ({@link com.lookahead.domain.config.PaymentConfiguration}).
 */
// Exception, decided 2026-11-25 (DLV-1016): a client that makes no outside call of its own. It is the payment client of
// DEV (Local has the mock provider directly): it holds a purchase back as a request and forwards approve, confirm and
// status to the client it wraps (the mock provider). The rule stays here and not in MembershipService because the
// service asks one PaymentClient in every environment (holdsRequests, approve) and has no environment switch of its
// own, and because the start-up line of dev() is written when this bean is created.
public final class PaymentOwnerApprovalClient implements PaymentClient {
    private final PaymentClient provider;
    /** The DEV client, announced once in the log when the configuration selects it. */
    public static PaymentOwnerApprovalClient dev(PaymentClient mock) {
        // DLV-969 (owner, 2026-11-04): in DEV a test account's purchase is a request the owner or an admin decides.
        EventLog.success(EventLog.PAYMENT,"payment.provider").addKeyValue("provider.mode","owner-approval")
            .log("DEV purchases are requests from test accounts: nothing is paid until the owner or an admin approves one");
        return new PaymentOwnerApprovalClient(mock);
    }
    public PaymentOwnerApprovalClient(PaymentClient provider) { this.provider = Objects.requireNonNull(provider); }
    /** A request: no payment id and no charge, the amount the account agreed to. */
    @Override public Result create(String key, int amountCents, String currency) { return new Result(null, AWAITING, amountCents, currency); }
    @Override public boolean holdsRequests() { return true; }
    /** Approved: the provider pays now, idempotently under the request's key. */
    @Override public Result approve(String key, int amountCents, String currency) { return provider.create(key, amountCents, currency); }
    @Override public Result confirm(String id, String key) { return provider.confirm(id, key); }
    @Override public Result status(String id) { return provider.status(id); }
}
