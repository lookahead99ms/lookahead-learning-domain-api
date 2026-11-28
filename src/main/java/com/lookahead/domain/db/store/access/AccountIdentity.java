package com.lookahead.domain.db.store.access;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// DLV-970 cross-lane: provided by lane A
/**
 * Reads of lane A's account, identity and staff items that DEV test sessions and test-account lists
 * need.
 */
public interface AccountIdentity {
  /**
   * An admitted, enabled account that is not disabled or deleted, with its owner flag; null for
   * anyone else.
   */
  record Holder(UUID accountId, boolean authorAccess) {}

  /**
   * An account's admitted record: its issuer, display name and enabled flag; null when it has none.
   */
  record Admitted(String issuer, String displayName, boolean enabled, boolean admitted) {}

  /** The account any linked identity (issuer, subject) names, or null. */
  UUID accountOf(String issuer, String subject);

  Holder holder(String issuer, String subject);

  Admitted admitted(UUID account);

  /** The account's staff roles in stored order; empty without an assignment. */
  List<String> staffRoles(UUID account);

  /** The display name of an admitted account, or null. */
  String displayName(UUID account);

  /** When the account's staff assignment last changed, or null (the roster's "changed" check). */
  Instant staffAccessUpdatedAt(UUID account);
}
