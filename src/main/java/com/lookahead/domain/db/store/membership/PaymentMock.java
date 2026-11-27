package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.client.PaymentClient;

/** DLV-970: the mock provider's durable, independent payment records ({@code membership_mock_payments}). */
public interface PaymentMock {
    /** The payment under this idempotency key, or null. */
    PaymentClient.Result findByKey(String key);
    /** Records the payment once (an existing key keeps its first record) and returns what is stored under the key. */
    PaymentClient.Result record(String key, String paymentId, int amountCents, String currency, String status);
    /** The payment with this provider id, or null. */
    PaymentClient.Result findById(String paymentId);
}
