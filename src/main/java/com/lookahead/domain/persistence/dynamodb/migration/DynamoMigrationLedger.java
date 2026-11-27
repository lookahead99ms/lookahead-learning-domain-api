package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.db.store.dynamodb.DynamoDataVersionStore;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * DLV-970: the applied-step history and the CURRENT watermark in Protected Owner Identity, namespace
 * {@code DATA_VERSION#DOMAIN}. Each applied step is an immutable {@code STEP#<ten digits>} item with its
 * checksum; CURRENT is the highest contiguous verified version. One transaction inserts the step, advances
 * CURRENT from the exact previous version and checks the runner still holds its fenced lock, so a crash never
 * leaves CURRENT ahead of a recorded step and a stale runner records nothing. Only the migration task writes
 * here, as PartiQL through {@link DynamoWrites}; the running app reads through {@link DynamoDataVersionStore}.
 */
public final class DynamoMigrationLedger {
    public static final String PK = "DATA_VERSION#DOMAIN";
    static final int FORMAT = 1;

    public record AppliedStep(int version, String name, String checksum, Instant appliedAt, String runId) {
        public AppliedStep {
            if (version < 1) throw new IllegalArgumentException("Invalid applied version");
            if (name == null || name.isBlank() || checksum == null || !checksum.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid applied step");
            Objects.requireNonNull(appliedAt, "appliedAt");
            DynamoMigrationLock.requireRunId(runId);
        }
        String sortKey() { return sortKey(version); }
        static String sortKey(int version) { return String.format("STEP#%010d", version); }
    }

    /** The ledger does not match what this runner expected: another runner advanced it, or it is damaged. */
    public static final class Conflict extends IllegalStateException {
        Conflict(String message) { super(message); }
    }

    private final DynamoDbClient client;
    private final String table;
    private final DynamoDataVersionStore reader;

    public DynamoMigrationLedger(DynamoDbClient client, DynamoTableNames tables) {
        this.client = Objects.requireNonNull(client, "client");
        this.table = Objects.requireNonNull(tables, "tables").protectedOwnerIdentityTableName();
        this.reader = new DynamoDataVersionStore(client, tables);
    }

    /** The watermark: zero on an empty store; malformed metadata fails closed. */
    public long current() { return reader.currentVersion(); }

    /** Every applied step in version order, strongly read; a malformed record fails closed. */
    public List<AppliedStep> history() {
        var steps = new ArrayList<AppliedStep>();
        Map<String, AttributeValue> cursor = Map.of();
        int pages = 0;
        do {
            if (++pages > 1000) throw new IllegalStateException("Migration history page budget exhausted");
            var response = client.query(QueryRequest.builder().tableName(table).consistentRead(true)
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                    .expressionAttributeValues(Map.of(":pk", s(PK), ":prefix", s("STEP#")))
                    .exclusiveStartKey(cursor.isEmpty() ? null : cursor).build());
            for (var item : response.items()) steps.add(parse(item));
            cursor = response.lastEvaluatedKey() == null ? Map.of() : response.lastEvaluatedKey();
        } while (!cursor.isEmpty());
        for (int index = 1; index < steps.size(); index++)
            if (steps.get(index).version() <= steps.get(index - 1).version()) throw new IllegalStateException("Migration history out of order");
        return List.copyOf(steps);
    }

    public java.util.Optional<AppliedStep> step(int version) {
        var item = client.getItem(GetItemRequest.builder().tableName(table).consistentRead(true)
                .key(Map.of("pk", s(PK), "sk", s(AppliedStep.sortKey(version)))).build()).item();
        return item.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(parse(item));
    }

    /**
     * Records {@code step} and moves CURRENT from {@code step.version() - 1} to {@code step.version()} while
     * {@code lease} still owns the lock. A lost reply is reconciled by reading the step back: the same checksum
     * and run under a CURRENT at or past the version means it was recorded.
     */
    public void record(AppliedStep step, DynamoMigrationLock.Lease lease) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(lease, "lease");
        long previous = step.version() - 1L;
        var stepItem = Map.of("pk", s(PK), "sk", s(step.sortKey()), "ledgerFormatVersion", n(Integer.toString(FORMAT)),
                "version", n(Integer.toString(step.version())), "name", s(step.name()), "checksum", s(step.checksum()),
                "appliedAt", s(step.appliedAt().toString()), "runId", s(step.runId()));
        var currentKey = Map.of("pk", s(PK), "sk", s("CURRENT"));
        var current = Map.of("version", n(Integer.toString(step.version())), "ledgerFormatVersion", n(Integer.toString(FORMAT)),
                "updatedAt", s(step.appliedAt().toString()), "runId", s(step.runId()));
        var tx = DynamoWrites.transaction(client).insertOnly(table, stepItem);
        if (previous == 0) {
            var first = new java.util.HashMap<>(current);
            first.putAll(currentKey);
            tx.insertOnly(table, first);
        } else {
            tx.update(table, currentKey, current, "\"version\"=? AND ledgerFormatVersion=?", n(Long.toString(previous)), n(Integer.toString(FORMAT)));
        }
        tx.check(table, DynamoMigrationLock.KEY, 1, "runId=? AND fence=?", s(lease.runId()), n(Long.toString(lease.fence())));
        try {
            tx.commit();
        } catch (DynamoWrites.Conflict | DynamoWrites.Unavailable failure) {
            try {
                if (recorded(step)) return;
            } catch (RuntimeException reconciliation) {
                failure.addSuppressed(reconciliation);
            }
            throw new Conflict("Migration step " + step.version() + " was not recorded: " + failure.getClass().getSimpleName());
        }
    }

    private boolean recorded(AppliedStep step) {
        var stored = step(step.version());
        return stored.isPresent() && stored.get().checksum().equals(step.checksum()) && stored.get().runId().equals(step.runId())
                && current() >= step.version();
    }

    static AppliedStep parse(Map<String, AttributeValue> item) {
        try {
            if (!n(Integer.toString(FORMAT)).equals(item.get("ledgerFormatVersion"))) throw new IllegalStateException();
            int version = Integer.parseInt(item.get("version").n());
            if (!AppliedStep.sortKey(version).equals(item.get("sk").s())) throw new IllegalStateException();
            return new AppliedStep(version, item.get("name").s(), item.get("checksum").s(),
                    Instant.parse(item.get("appliedAt").s()), item.get("runId").s());
        } catch (RuntimeException malformed) {
            // Do not echo item contents: the record is damaged and verify must say so without quoting it.
            throw new IllegalStateException("Malformed migration history record");
        }
    }

    static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    static AttributeValue n(String value) { return AttributeValue.builder().n(value).build(); }
}
