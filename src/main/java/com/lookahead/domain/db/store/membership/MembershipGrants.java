package com.lookahead.domain.db.store.membership;

import java.util.Set;
import java.util.UUID;

/** Purchases snapshot catalog scope, but resolve courses dynamically on every access check. */
public interface MembershipGrants {
  /**
   * The catalog scopes (the part of a topic ID before the colon, for example {@code learn}) of the
   * account's purchases granting access now. DLV-1016: the store used to expand them into topics
   * with the account catalog, a service; the caller does that now ({@code MembershipRules.topics}).
   */
  Set<String> catalogScopes(UUID account);
}
