package com.lookahead.domain.db.store.testaccounts;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DLV-970: the test-account registry as it is read outside a transaction: the active rows, the
 * registered and deleted roster ids and the roster status. DLV-1016: split from the test-account
 * store interface; the transaction is {@link TestAccount}.
 */
public interface TestAccountRegistryRows {
  record RegistryRow(
      UUID accountId,
      String username,
      String initialization,
      String creationState,
      UUID creatorId,
      String lastChangedBy,
      Instant lastChangedAt,
      long revision) {}

  /**
   * One roster row with what the store knows about it: when it was last reset (null: never), when
   * its registry row last changed, the latest write to its purchases, plans or staff access (null:
   * none), and whether it has a plan.
   */
  record RosterFacts(
      UUID accountId,
      String creationState,
      boolean hasPlan,
      Instant lastResetAt,
      Instant lastChangedAt,
      Instant latestWriteAt) {}

  /**
   * Active registry rows by username; {@code cloud}: only created accounts and the owner-created
   * roster.
   */
  List<RegistryRow> activeRegistry(boolean cloud);

  Set<UUID> registeredIds();

  /** Deleted owner-created roster rows. */
  Set<UUID> deletedRosterIds();

  /**
   * The facts of every roster row. Whether a row counts as changed since its last reset is the
   * caller's rule.
   */
  List<RosterFacts> rosterFacts();
}
