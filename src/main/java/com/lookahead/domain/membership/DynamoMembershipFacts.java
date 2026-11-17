package com.lookahead.domain.membership;

import com.lookahead.domain.access.MembershipErasure;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.MembershipTopicSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970 lane G: lane C's memberships for the other lanes, in dynamodb mode. {@link MembershipTopicSource} (lane A's
 * AccountRepository) is {@link DynamoMembershipGrants}; {@link com.lookahead.domain.access.MembershipFacts} and
 * {@link MembershipErasure} (the owner's directory, merge page, downgrade and removal) read the account's purchase items
 * through {@link MembershipItems} and end open purchases through {@link DynamoMembershipStore}'s fenced transaction.
 */
@Component
public class DynamoMembershipFacts implements MembershipTopicSource, MembershipErasure, com.lookahead.domain.access.MembershipFacts {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper json;
    private final DynamoMembershipGrants grants;
    private final DynamoMembershipStore store;
    private final Clock clock;

    @Autowired
    public DynamoMembershipFacts(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, DynamoMembershipGrants grants, DynamoMembershipStore store) {
        this(client, tables, json, grants, store, Clock.systemUTC());
    }
    public DynamoMembershipFacts(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, DynamoMembershipGrants grants, DynamoMembershipStore store, Clock clock) {
        this.client = Objects.requireNonNull(client); this.tables = Objects.requireNonNull(tables); this.json = Objects.requireNonNull(json);
        this.grants = Objects.requireNonNull(grants); this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
    }

    @Override public Set<String> topics(UUID account) { return grants.topics(account); }

    private List<ObjectNode> purchases(UUID account) { return MembershipItems.purchases(client, tables, json, account); }
    private static boolean open(ObjectNode purchase) { return MembershipItems.unset(purchase.path("supersededAt")); }

    /** The SQL {@code superseded_at IS NULL} row: any purchase not yet superseded, whatever its status. */
    @Override public boolean hasOpenPurchases(UUID account) { return purchases(account).stream().anyMatch(DynamoMembershipFacts::open); }

    /** A succeeded or scheduled purchase still running, not superseded, that was paid for or whose plan is priced (the merge page's check). */
    @Override public boolean hasPaidMembership(UUID account) {
        Instant now = clock.instant();
        for (var p : purchases(account)) {
            if (!open(p) || !Set.of("succeeded", "scheduled").contains(p.path("status").asText())) continue;
            if (!Instant.parse(p.path("endsAt").asText()).isAfter(now)) continue;
            if (p.path("pricePaidCents").asInt() > 0) return true;
            var plan = store.definition(p.path("planId").asText());
            if (plan != null && plan.path("priceCents").asInt() > 0) return true;
        }
        return false;
    }

    /** The SQL {@code UPDATE membership_purchases SET superseded_at=now() WHERE account_id=? AND superseded_at IS NULL}: one fenced transaction; nothing when nothing is open. */
    @Override public void endMemberships(UUID account) {
        var open = purchases(account).stream().filter(DynamoMembershipFacts::open).map(p -> UUID.fromString(p.path("id").asText())).toList();
        if (open.isEmpty()) return;
        Instant now = clock.instant();
        try {
            store.transact(null, work -> {
                work.lockAccount(account);
                for (UUID purchase : open) work.supersede(account, purchase, now);
                return null;
            });
        } catch (AccountFailure noSubject) {
            if (noSubject.status() != 404) throw noSubject; // no META: the account was never created, so there is nothing to end
        }
    }
}
