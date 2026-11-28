package com.lookahead.domain.client;

public interface PaymentClient {
  record Result(String id, String status, int amountCents, String currency) {}

  /** provider_status of a purchase request that waits for a decision. */
  String AWAITING = "awaiting_owner_approval";

  /** provider_status of a declined request (status failed). */
  String DECLINED = "declined_by_owner";

  Result create(String key, int amountCents, String currency);

  Result confirm(String id, String key);

  Result status(String id);

  /**
   * True for the client that holds a purchase back as a request until the owner or an admin
   * approves it (DEV).
   */
  default boolean holdsRequests() {
    return false;
  }

  /**
   * An approved request: the payment is taken now, under the request's own key. Only a client that
   * holds requests has it.
   */
  default Result approve(String key, int amountCents, String currency) {
    throw new UnsupportedOperationException("This payment client holds no purchase requests");
  }

  final class Failure extends RuntimeException {
    private final boolean transientFailure;

    public Failure(String category, boolean transientFailure) {
      super(category);
      this.transientFailure = transientFailure;
    }

    public boolean transientFailure() {
      return transientFailure;
    }
  }
}
