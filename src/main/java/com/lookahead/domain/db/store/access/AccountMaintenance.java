package com.lookahead.domain.db.store.access;

import java.util.UUID;

/**
 * DLV-970: the writes of the owner's downgrade and of the Local learner deletion ({@code
 * AccountMaintenanceService}). DLV-1016: what the service reads before it writes is {@link
 * AccountMaintenanceFacts}; one class implements both.
 */
public interface AccountMaintenance {
  void ensureSubject(UUID id);

  // The single writes of a downgrade and of a learner deletion; the service runs them in its order
  // (DLV-1016: the two sequences were store methods).
  /**
   * A staff assignment with roles, topic grants or the full curriculum is replaced by an empty one,
   * audited as a downgrade; nothing when there is none.
   */
  void resetStaff(UUID actor, UUID id);

  /** Every course grant of the account is deleted. */
  void deleteGrants(UUID id);

  /** The account's open memberships are ended. */
  void endMemberships(UUID id);

  /** The account's plans, free ledgers and receipts are erased. */
  void erasePlans(UUID id);

  /**
   * The maintenance audit event ({@code action}); for a deletion also the completion marker that
   * {@link AccountMaintenanceFacts#alreadyDeleted} reads.
   */
  void record(UUID actor, UUID id, String action, boolean deletion);
}
