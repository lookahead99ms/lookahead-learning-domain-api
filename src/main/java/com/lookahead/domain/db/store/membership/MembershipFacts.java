package com.lookahead.domain.db.store.membership;

import java.util.UUID;

// DLV-970 cross-lane: provided by lane C. The membership facts the owner's directory and the merge
// page show.
public interface MembershipFacts {
  /** An open purchase row (not superseded) exists for the account. */
  boolean hasOpenPurchases(UUID account);

  /** An active or scheduled paid membership that a merge or deletion would end. */
  boolean hasPaidMembership(UUID account);
}
