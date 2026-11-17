package com.lookahead.learning.content.service;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.learning.content.repository.DynamoOwnerReads;
import com.lookahead.learning.content.repository.DynamoOwnerUnit;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: free-plan ledgers in DynamoDB. Lifetime creations are {@code FREECREATE#planId} receipts plus the
 * {@code freePlanCreations} counter on the owner META item (moved in the same transaction, never decremented by the
 * runtime; account removal keeps both, the test-account erasure deletes the receipts and resets the counter, as the
 * SQL twins do); deletions are {@code FREEDELETE#time#planId} receipts queried strongly for the rolling window. Allowance
 * checks read committed state: PlanService checks before it writes, so pending writes of the same unit are not needed
 * for the counts, while this store's own pending receipts are still visible to a later check in the same unit.
 */
@Repository
@Profile("accounts")
public class DynamoFreePlanLedgerStore implements FreePlanLedgerStore {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoFreePlanLedgerStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    DynamoFreePlanLedgerStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.client = client; this.tables = tables; this.clock = clock; }

    static final class Pending { final Set<UUID> creations = new HashSet<>(); final List<Instant> deletions = new ArrayList<>(); }
    private Pending pending(DynamoOwnerUnit unit) { return unit.state(this, Pending::new); }
    private String table() { return tables.userAccountDetailsTableName(); }

    @Override public void recordCreation(UUID owner, UUID planId) {
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            var fence = unit.fence(owner);
            if (pending(unit).creations.contains(planId) || !DynamoOwnerReads.get(client, table(), DynamoItems.account(owner), "FREECREATE#" + planId).isEmpty()) return null;
            pending(unit).creations.add(planId);
            fence.countFreeCreation();
            unit.statement(table(), DynamoItems.account(owner), "FREECREATE#" + planId, tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(owner),
                    "FREECREATE#" + planId, DynamoOwnerUnit.SCHEMA, Map.of("planId", DynamoItems.s(planId.toString()), "createdAt", DynamoItems.s(unit.now().toString())))));
            return null;
        });
    }

    @Override public int creations(UUID owner) {
        return DynamoOwnerUnit.run(client, tables, clock, unit -> (int) unit.fence(owner).freeCreations());
    }

    @Override public boolean creationRecorded(UUID owner, UUID planId) {
        var unit = DynamoOwnerUnit.current();
        if (unit != null && pending(unit).creations.contains(planId)) return true;
        return !DynamoOwnerReads.get(client, table(), DynamoItems.account(owner), "FREECREATE#" + planId).isEmpty();
    }

    private List<Instant> deletions(UUID owner, Instant since) {
        var result = new ArrayList<Instant>();
        for (var item : DynamoOwnerReads.queryAll(client, table(), DynamoItems.account(owner), "FREEDELETE#")) {
            var at = DynamoItems.instant(item, "deletedAt");
            if (at.isAfter(since)) result.add(at);
        }
        var unit = DynamoOwnerUnit.current();
        if (unit != null) for (var at : pending(unit).deletions) if (at.isAfter(since)) result.add(at);
        return result;
    }

    @Override public int deletionsSince(UUID owner, Instant since) { return deletions(owner, since).size(); }

    @Override public Instant oldestDeletionSince(UUID owner, Instant since) {
        return deletions(owner, since).stream().min(Instant::compareTo).orElse(null);
    }

    @Override public void recordDeletion(UUID owner, UUID planId) {
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            unit.fence(owner);
            Instant now = unit.now();
            pending(unit).deletions.add(now);
            String sk = DynamoItems.timeKey("FREEDELETE", now, planId);
            unit.statement(table(), DynamoItems.account(owner), sk, tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(owner), sk, DynamoOwnerUnit.SCHEMA,
                    Map.of("planId", DynamoItems.s(planId.toString()), "deletedAt", DynamoItems.s(now.toString())))));
            return null;
        });
    }

    @Override public int activeFreePlans(UUID owner, UUID excludedPlan, LocalDate today, Predicate<Set<String>> freeContent) {
        var lifecycle = new HashMap<UUID, Map<String, AttributeValue>>();
        for (var item : DynamoOwnerReads.queryAll(client, table(), DynamoItems.account(owner), "LIFECYCLE#")) lifecycle.put(DynamoItems.uuid(item, "planId"), item);
        int count = 0;
        for (var plan : DynamoOwnerReads.queryAll(client, table(), DynamoItems.account(owner), "PLAN#")) {
            if (DynamoItems.flag(plan, "deleted")) continue;
            UUID id = DynamoItems.uuid(plan, "planId");
            var row = lifecycle.get(id);
            String state = row == null ? null : DynamoItems.string(row, "state");
            if (id.equals(excludedPlan) || "archived".equals(state) || "completed".equals(state)) continue;
            if ("active".equals(state) && LocalDate.parse(DynamoItems.string(row, "startDate"))
                    .plusDays(DynamoItems.number(row, "dayCount") + 90L).compareTo(today) <= 0) continue;
            var version = DynamoOwnerReads.get(client, table(), DynamoItems.plan(owner, id), "VERSION#" + DynamoItems.uuid(plan, "currentVersion"), DynamoOwnerReads.CONTENT_IDS);
            if (version.isEmpty()) throw new IllegalStateException("Plan has no current version");
            if (freeContent.test(new HashSet<>(DynamoItems.stringSet(version, "contentIds")))) count++;
        }
        return count;
    }
}
