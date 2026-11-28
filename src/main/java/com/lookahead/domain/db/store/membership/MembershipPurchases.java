package com.lookahead.domain.db.store.membership;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: reads of an account's purchases outside a purchase transaction. DLV-1016: split from the
 * former membership store interface.
 */
public interface MembershipPurchases {
  /**
   * The purchase row the API answers with ({@code fingerprint} included), or null when the account
   * has none by that id.
   */
  ObjectNode purchase(UUID account, UUID id);

  /** Ids of the account's succeeded, scheduled and expired purchases, newest first. */
  List<UUID> accessIds(UUID account);

  /**
   * The account's test-account registry row as stored, or null when it has none. The service
   * decides what the row means.
   */
  ObjectNode testAccountRegistry(UUID account);

  /** Pending or scheduled purchases that replace {@code replacesId}. */
  int replacementsScheduled(UUID replacesId);
}
