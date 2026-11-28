package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.util.MembershipRules;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What other features read about an account's memberships. The topics a membership gives are asked
 * of {@link MembershipGrantsStore} directly (DLV-1016: this class used to forward that one question
 * under a second, identical interface). {@link MembershipFacts} and {@link MembershipErasure} (the
 * owner's directory, merge page, downgrade and removal) read the account's purchase items through
 * {@link MembershipItems} and end open purchases through {@link MembershipStore}'s fenced
 * transaction.
 */
@Component
public class MembershipFactsStore implements MembershipErasure, MembershipFacts {
  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final ObjectMapper json;
  private final MembershipStore store;
  private final Clock clock;

  @Autowired
  public MembershipFactsStore(
      DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, MembershipStore store) {
    this(client, tables, json, store, Clock.systemUTC());
  }

  public MembershipFactsStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      ObjectMapper json,
      MembershipStore store,
      Clock clock) {
    this.client = Objects.requireNonNull(client);
    this.tables = Objects.requireNonNull(tables);
    this.json = Objects.requireNonNull(json);
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  private List<ObjectNode> purchases(UUID account) {
    return MembershipItems.purchases(client, tables, json, account);
  }

  private static boolean open(ObjectNode purchase) {
    return MembershipItems.unset(purchase.path("supersededAt"));
  }

  /**
   * The SQL {@code superseded_at IS NULL} row: any purchase not yet superseded, whatever its
   * status.
   */
  @Override
  public boolean hasOpenPurchases(UUID account) {
    return purchases(account).stream().anyMatch(MembershipFactsStore::open);
  }

  /**
   * Any purchase that is a paid membership now ({@link MembershipRules#paid}); a plan definition is
   * read only for a running purchase nothing was paid for.
   */
  @Override
  public boolean hasPaidMembership(UUID account) {
    Instant now = clock.instant();
    for (var p : purchases(account))
      if (MembershipRules.paid(p, now, store::definition)) return true;
    return false;
  }

  /**
   * The SQL {@code UPDATE membership_purchases SET superseded_at=now() WHERE account_id=? AND
   * superseded_at IS NULL}: one fenced transaction; nothing when nothing is open.
   */
  @Override
  public void endMemberships(UUID account) {
    var open =
        purchases(account).stream()
            .filter(MembershipFactsStore::open)
            .map(p -> UUID.fromString(p.path("id").asText()))
            .toList();
    if (open.isEmpty()) return;
    Instant now = clock.instant();
    store.transact(work -> {
      if (!work.lockAccount(account))
        return null; // no META: the account was never created, so there is nothing to end
      for (UUID purchase : open) work.supersede(account, purchase, now);
      return null;
    });
  }
}
