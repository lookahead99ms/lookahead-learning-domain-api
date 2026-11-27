package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.db.store.dynamodb.OwnerTransactionReads;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionUnit;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: free-plan ledgers in DynamoDB. Lifetime creations are {@code FREECREATE#planId} receipts plus the
 * {@code freePlanCreations} counter on the owner META item (moved in the same transaction, never decremented by the
 * runtime; account removal keeps both, the test-account erasure deletes the receipts and resets the counter, as the
 * SQL twins do); deletions are {@code FREEDELETE#time#planId} receipts queried strongly for the rolling window. Allowance
 * checks read committed state: StudyPlanService checks before it writes, so pending writes of the same unit are not needed
 * for the counts, while this store's own pending receipts are still visible to a later check in the same unit.
 */
@Repository
@Profile("accounts")
public class StudyPlanFreeLedgerStore implements StudyPlanFreeLedger {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    @Autowired
    public StudyPlanFreeLedgerStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    StudyPlanFreeLedgerStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.client = client; this.tables = tables; this.clock = clock; }

    static final class Pending { final Set<UUID> creations = new HashSet<>(); final List<Instant> deletions = new ArrayList<>(); }
    private Pending pending(OwnerTransactionUnit unit) { return unit.state(this, Pending::new); }
    private String table() { return tables.userAccountDetailsTableName(); }

    @Override public void recordCreation(UUID owner, UUID planId) {
        OwnerTransactionUnit.run(client, tables, clock, unit -> {
            var fence = unit.fence(owner);
            if (pending(unit).creations.contains(planId) || !OwnerTransactionReads.get(client, table(), DynamoItems.account(owner), "FREECREATE#" + planId).isEmpty()) return null;
            pending(unit).creations.add(planId);
            fence.countFreeCreation();
            unit.statement(table(), DynamoItems.account(owner), "FREECREATE#" + planId, tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(owner),
                    "FREECREATE#" + planId, OwnerTransactionUnit.SCHEMA, Map.of("planId", DynamoItems.s(planId.toString()), "createdAt", DynamoItems.s(unit.now().toString())))));
            return null;
        });
    }

    @Override public int creations(UUID owner) {
        return OwnerTransactionUnit.run(client, tables, clock, unit -> (int) unit.fence(owner).freeCreations());
    }

    @Override public boolean creationRecorded(UUID owner, UUID planId) {
        var unit = OwnerTransactionUnit.current();
        if (unit != null && pending(unit).creations.contains(planId)) return true;
        return !OwnerTransactionReads.get(client, table(), DynamoItems.account(owner), "FREECREATE#" + planId).isEmpty();
    }

    private List<Instant> deletions(UUID owner, Instant since) {
        var result = new ArrayList<Instant>();
        for (var item : OwnerTransactionReads.queryAll(client, table(), DynamoItems.account(owner), "FREEDELETE#")) {
            var at = DynamoItems.instant(item, "deletedAt");
            if (at.isAfter(since)) result.add(at);
        }
        var unit = OwnerTransactionUnit.current();
        if (unit != null) for (var at : pending(unit).deletions) if (at.isAfter(since)) result.add(at);
        return result;
    }

    @Override public int deletionsSince(UUID owner, Instant since) { return deletions(owner, since).size(); }

    @Override public Instant oldestDeletionSince(UUID owner, Instant since) {
        return deletions(owner, since).stream().min(Instant::compareTo).orElse(null);
    }

    @Override public void recordDeletion(UUID owner, UUID planId) {
        OwnerTransactionUnit.run(client, tables, clock, unit -> {
            unit.fence(owner);
            Instant now = unit.now();
            pending(unit).deletions.add(now);
            String sk = DynamoItems.timeKey("FREEDELETE", now, planId);
            unit.statement(table(), DynamoItems.account(owner), sk, tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(owner), sk, OwnerTransactionUnit.SCHEMA,
                    Map.of("planId", DynamoItems.s(planId.toString()), "deletedAt", DynamoItems.s(now.toString())))));
            return null;
        });
    }

    /** The stored plan item with its lifecycle item (null when there is none); nothing is parsed before it is asked for. */
    private record Stored(Map<String, AttributeValue> plan, Map<UUID, Map<String, AttributeValue>> lifecycle) implements PlanRow {
        private Map<String, AttributeValue> row() { return lifecycle.get(id()); }
        @Override public UUID id() { return DynamoItems.uuid(plan, "planId"); }
        @Override public String state() { var row = row(); return row == null ? null : DynamoItems.string(row, "state"); }
        @Override public LocalDate startDate() { return LocalDate.parse(DynamoItems.string(row(), "startDate")); }
        @Override public long dayCount() { return DynamoItems.number(row(), "dayCount"); }
        @Override public UUID currentVersion() { return DynamoItems.uuid(plan, "currentVersion"); }
    }
    @Override public List<PlanRow> plans(UUID owner) {
        var lifecycle = new HashMap<UUID, Map<String, AttributeValue>>();
        for (var item : OwnerTransactionReads.queryAll(client, table(), DynamoItems.account(owner), "LIFECYCLE#")) lifecycle.put(DynamoItems.uuid(item, "planId"), item);
        var rows = new ArrayList<PlanRow>();
        for (var plan : OwnerTransactionReads.queryAll(client, table(), DynamoItems.account(owner), "PLAN#"))
            if (!DynamoItems.flag(plan, "deleted")) rows.add(new Stored(plan, lifecycle));
        return rows;
    }
    @Override public Set<String> contentIds(UUID owner, UUID plan, UUID version) {
        var item = OwnerTransactionReads.get(client, table(), DynamoItems.plan(owner, plan), "VERSION#" + version, OwnerTransactionReads.CONTENT_IDS);
        if (item.isEmpty()) throw new IllegalStateException("Plan has no current version");
        return new HashSet<>(DynamoItems.stringSet(item, "contentIds"));
    }
}
