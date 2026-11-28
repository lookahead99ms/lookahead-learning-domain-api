package com.lookahead.domain.db.store.membership;

import java.time.Instant;
import java.util.UUID;

/**
 * DLV-970: the DEV purchase requests that wait for, or carry, a decision. DLV-1016: split from the
 * former membership store interface.
 */
public interface MembershipRequests {
  /** The oldest DEV request waiting for a decision (not superseded), or null. */
  PendingRow pendingRequest(UUID account, String awaiting);

  record PendingRow(UUID id, Instant createdAt) {}

  /**
   * Who sent a DEV request: the {@code requestedBy} its purchase-created audit row names, or null.
   */
  UUID requestedBy(UUID purchase);

  /**
   * The latest purchase-approved audit row after the account's latest reset whose purchase was paid
   * or scheduled, or null.
   */
  Approval approvedRequest(UUID account);

  record Approval(UUID purchaseId, UUID approvedBy, Instant approvedAt) {}
}
