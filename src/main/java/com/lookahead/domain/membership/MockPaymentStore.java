package com.lookahead.domain.membership;

/** DLV-970: the mock provider's durable, independent payment records ({@code membership_mock_payments}). */
public interface MockPaymentStore {
    /** The payment under this idempotency key, or null. */
    PaymentGateway.Result findByKey(String key);
    /** Records the payment once (an existing key keeps its first record) and returns what is stored under the key. */
    PaymentGateway.Result record(String key, String paymentId, int amountCents, String currency, String status);
    /** The payment with this provider id, or null. */
    PaymentGateway.Result findById(String paymentId);
}
