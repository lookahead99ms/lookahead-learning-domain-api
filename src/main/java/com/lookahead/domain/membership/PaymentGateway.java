package com.lookahead.domain.membership;

public interface PaymentGateway {
    record Result(String id, String status, int amountCents, String currency) {}
    Result create(String key, int amountCents, String currency);
    Result confirm(String id, String key);
    Result status(String id);
    default void refund(String id) { throw new UnsupportedOperationException("Refund workflow is not enabled"); }
    final class Failure extends RuntimeException {
        private final boolean transientFailure;
        public Failure(String category, boolean transientFailure) { super(category); this.transientFailure=transientFailure; }
        public boolean transientFailure() { return transientFailure; }
    }
}
