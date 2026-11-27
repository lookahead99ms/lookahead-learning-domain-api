package com.lookahead.domain.db.store.dynamodb;

import com.lookahead.domain.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: one owner-scoped DynamoDB transaction for one StudyPlanService mutation. StudyPlanService keeps its validation,
 * policy and the many small repository calls of one {@code @Transactional} method; the stores do not
 * write when called but record the final state of every item they touch here, and {@link #commit()} sends all of it
 * as one conditional PartiQL transaction. The owner row lock of the retired PostgreSQL store ({@code SELECT … FOR UPDATE})
 * became the owner fence: {@code ACCOUNT#id / META} is read strongly once per unit and updated under its exact
 * revision, so two mutations of the same owner never interleave (the second one fails with
 * {@link DynamoWrites.Conflict} and is retried by its client through its durable receipt). The plan-number sequence
 * and the lifetime free-creation counter live on that same item and move in the same transaction.
 *
 * <p>The unit is bound to the thread by {@link OwnerTransactionManager}; a store called outside a transaction
 * opens a private unit around the single call ({@link #run}), so every repository method is a transaction on its own.
 */
public final class OwnerTransactionUnit {
    static final Object RESOURCE_KEY = OwnerTransactionUnit.class;
    public static final int SCHEMA = 1;

    /** The owner's META item as read at the start of the unit plus the counters this unit moves. */
    public final class OwnerFence {
        private final UUID owner;
        private final long revision;
        private long lastPlanNumber;
        private long freeCreations;
        private boolean dirty;
        OwnerFence(UUID owner, long revision, long lastPlanNumber, long freeCreations) {
            this.owner = owner; this.revision = revision; this.lastPlanNumber = lastPlanNumber; this.freeCreations = freeCreations;
        }
        public long lastPlanNumber() { return lastPlanNumber; }
        public long allocatePlanNumber() { dirty = true; return ++lastPlanNumber; }
        public long freeCreations() { return freeCreations; }
        public void countFreeCreation() { dirty = true; freeCreations++; }
        void bump(DynamoWrites tx) {
            tx.update(tables.userAccountDetailsTableName(), DynamoItems.key(DynamoItems.account(owner), "META"),
                    Map.of("revision", DynamoItems.n(revision + 1), "lastPlanNumber", DynamoItems.n(lastPlanNumber),
                            "freePlanCreations", DynamoItems.n(freeCreations)),
                    "revision=?", DynamoItems.n(revision));
        }
    }

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;
    private final UUID id = UUID.randomUUID();
    private final Map<UUID, OwnerFence> fences = new LinkedHashMap<>();
    private final Map<String, Consumer<DynamoWrites>> statements = new LinkedHashMap<>();
    private final Map<Object, Object> state = new HashMap<>();
    private boolean committed;

    public OwnerTransactionUnit(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The unit bound to this thread by the transaction manager, if any. */
    public static OwnerTransactionUnit current() {
        return (OwnerTransactionUnit) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
    }

    /** Runs {@code work} in the bound unit, or in a private unit committed right after the call. */
    public static <T> T run(DynamoDbClient client, DynamoTableNames tables, Clock clock, Function<OwnerTransactionUnit, T> work) {
        OwnerTransactionUnit bound = current();
        if (bound != null) return work.apply(bound);
        var unit = new OwnerTransactionUnit(client, tables, clock);
        T result = work.apply(unit);
        unit.commit();
        return result;
    }

    public UUID id() { return id; }
    public Instant now() { return clock.instant(); }

    /** Per-store scratch state (pending items) living exactly as long as the unit. */
    @SuppressWarnings("unchecked")
    public <T> T state(Object key, Supplier<T> initial) { return (T) state.computeIfAbsent(key, ignored -> initial.get()); }

    /**
     * The owner fence: strongly reads {@code ACCOUNT#id / META} once. An owner without META was never created by
     * the identity slice, so the request is refused the way the SQL support path refuses a missing subject.
     */
    public OwnerFence fence(UUID owner) {
        var fence = fences.get(owner);
        if (fence != null) return fence;
        Map<String, AttributeValue> item;
        try {
            item = DynamoItems.strongGet(client, tables.userAccountDetailsTableName(), DynamoItems.account(owner), "META");
        } catch (SdkException failure) {
            throw new OwnerReadUnavailable("GetItem", tables.userAccountDetailsTableName(), failure);
        }
        // Exception, decided 2026-11-25 (DLV-1016): a refusal inside a store. The fence is this read: an owner without a META item
        // has no revision to fence on, so the unit cannot go on and nothing is recorded. Answering a fact instead would have every
        // caller of fence() repeat the same refusal before its first statement.
        if (item.isEmpty()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
        DynamoItems.requireSchema(item, SCHEMA);
        fence = new OwnerFence(owner, DynamoItems.number(item, "revision"), optional(item, "lastPlanNumber"), optional(item, "freePlanCreations"));
        fences.put(owner, fence);
        return fence;
    }

    /** Records the statement for one item; a later statement for the same item replaces the earlier one. */
    public void statement(String table, String pk, String sk, Consumer<DynamoWrites> statement) {
        if (committed) throw new IllegalStateException("Unit already committed");
        statements.put(table + "|" + pk + "|" + sk, statement);
    }

    public boolean hasWrites() { return !statements.isEmpty() || fences.values().stream().anyMatch(fence -> fence.dirty); }

    /** All recorded statements, each owner fence first, in one PartiQL transaction; nothing when nothing was written or counted. */
    public void commit() {
        if (committed) throw new IllegalStateException("Unit already committed");
        if (!hasWrites()) { committed = true; return; }
        committed = true;
        var tx = DynamoWrites.transaction(client);
        for (var fence : fences.values()) fence.bump(tx);
        for (var statement : statements.values()) statement.accept(tx);
        tx.commit(id.toString());
    }

    private static long optional(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        return value == null || value.n() == null ? 0 : DynamoItems.number(item, name);
    }

    /**
     * A read that did not answer: outcome unknown. It is a {@link DynamoWrites.Unavailable} so the web layer answers
     * the same 503 ACCOUNT_STORAGE_UNAVAILABLE for a failed read as for a failed write, never a 500.
     */
    public static final class OwnerReadUnavailable extends DynamoWrites.Unavailable {
        public OwnerReadUnavailable(SdkException cause) { super("DynamoDB read unavailable: " + cause.getClass().getSimpleName(), cause); }
        /** Plain logs (owner, 2026-11-17): the read operation and the table, so the ERROR line can name them. */
        public OwnerReadUnavailable(String operation, String table, SdkException cause) {
            super("DynamoDB read unavailable: " + cause.getClass().getSimpleName(), cause, operation, table == null ? List.of() : List.of(table));
        }
    }
}
