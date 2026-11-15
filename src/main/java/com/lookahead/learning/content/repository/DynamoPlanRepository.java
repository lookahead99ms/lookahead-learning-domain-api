package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.model.MutationReceipt;
import com.lookahead.learning.content.model.PlanRecord;
import com.lookahead.learning.content.model.PlanVersion;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: plans in DynamoDB. Items (User Account Details unless noted): {@code ACCOUNT#owner / META} owner fence and
 * counters; {@code ACCOUNT#owner / PLAN#id} the plan (progress inline, retained as a tombstone after deletion);
 * {@code ACCOUNT#owner / PLANLIST#createdAt#id} the list projection ({@code created_at DESC, id DESC});
 * {@code PLAN#owner#id / VERSION#versionId} immutable version metadata with the document inline (≤ 200 KiB) or published
 * through {@link DynamoPlanDocuments}; {@code PLAN#owner#id / ACTIVITY#revision#unit} the events of one mutation;
 * {@code ACCOUNT#owner / MUTATION#key} the replay receipt with its exact request hash (response inline or as a document)
 * plus a {@code PLAN#owner#id / RECEIPT#key} projection for the purge; {@code WORK#PLAN-PURGE#00 / …} the deletion job.
 * Every write of one PlanService transaction lands in one PartiQL transaction through {@link DynamoOwnerUnit}.
 *
 * <p>Documents above the inline budget are published (COMMITTED manifest, chunks, pointer, audit) before the owner
 * transaction that references them; PlanService therefore decides every refusal it can before {@link #insertVersion}.
 * What remains is the commit itself: a {@link DynamoWrites.Conflict} at commit (another mutation of the owner moved
 * first) leaves that COMMITTED document without a version or receipt item pointing at it. It is not read by anyone,
 * the retried mutation publishes under a new version ID, and the owner's erasure retires it with the other pointers of
 * this lane; no cleanup job is built for it.
 */
@Repository
@Profile("accounts")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = com.lookahead.domain.persistence.PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoPlanRepository implements PlanRepository {
    static final int INLINE_BYTES = 200 * 1024;
    static final int PROGRESS_BYTES = 300 * 1024;
    static final String PURGE_PARTITION = "WORK#PLAN-PURGE#00";

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final DynamoPlanDocuments documents;
    private com.lookahead.learning.content.service.ProtectedContentPolicy publication;
    private com.lookahead.domain.membership.MembershipGrants memberships;
    private AccountRepository accounts;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void publication(com.lookahead.learning.content.service.ProtectedContentPolicy value){publication=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void memberships(com.lookahead.domain.membership.MembershipGrants value){memberships=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void accounts(AccountRepository value){accounts=value;}

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoPlanRepository(DynamoDbClient client, DynamoTableNames tables, ObjectMapper mapper) { this(client, tables, mapper, Clock.systemUTC()); }
    DynamoPlanRepository(DynamoDbClient client, DynamoTableNames tables, ObjectMapper mapper, Clock clock) {
        this.client = client; this.tables = tables; this.mapper = mapper; this.clock = clock;
        this.documents = new DynamoPlanDocuments(client, tables, clock);
    }

    // ---- pending state of one unit ------------------------------------------------------------------------------

    /** A plan as loaded at the start of the unit and as it will be written at commit. */
    static final class PlanState {
        PlanRecord loaded; PlanRecord pending; boolean created, deleted;
    }
    static final class Pending {
        final Map<UUID, PlanState> plans = new HashMap<>();
        final Map<String, PlanVersion> versions = new HashMap<>();
        final Map<UUID, Set<String>> versionContent = new HashMap<>();
        final Map<String, List<Map<String, AttributeValue>>> activity = new HashMap<>();
    }
    private Pending pending(DynamoOwnerUnit unit) { return unit.state(this, Pending::new); }

    private String accountsTable() { return tables.userAccountDetailsTableName(); }
    private static String planPk(UUID owner, UUID id) { return DynamoItems.plan(owner, id); }
    private static String versionKey(UUID id, UUID version) { return id + "#" + version; }

    // ---- grants ---------------------------------------------------------------------------------------------------

    @Override public Set<String> grants(UUID account) {
        // The SQL account_grants read belongs to the identity slice: AccountRepository.findTopicGrants already includes
        // grants, staff assignment, owner check and memberships in dynamodb mode. A staff scope never authorizes plan content.
        var result = new HashSet<String>();
        if (accounts != null) result.addAll(com.lookahead.learning.content.util.CatalogIds.curriculumGrants(accounts.findTopicGrants(account)));
        else if (memberships != null) result.addAll(memberships.topics(account));
        if (publication != null && AccountRepository.currentIdentityEnabled(account))
            publication.contentGrants(Set.copyOf(result)).forEach(id -> result.add("content:" + id));
        return result;
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    /**
     * One strong read of the {@code PLANLIST#} projection ({@code created_at DESC, id DESC}, paged until offset + limit
     * rows) and one strong batch of the plan items. The owner fence is read first so a missing owner is refused (401)
     * the way every other call refuses it; PlanService.list runs in a bound unit, where that fence is read once and
     * cached, so no revision re-check after the batch could ever differ and none is made. A plan whose item is missing
     * or tombstoned between the two reads is dropped from the page (see {@link #batch}).
     */
    @Override public List<PlanRecord> list(UUID account, int limit, int offset) {
        if (limit < 0 || offset < 0) throw new IllegalArgumentException("Invalid page");
        DynamoOwnerUnit.run(client, tables, clock, unit -> unit.fence(account));
        var ids = new ArrayList<UUID>();
        Map<String, AttributeValue> start = null;
        int skipped = 0;
        do {
            var query = QueryRequest.builder().tableName(accountsTable()).consistentRead(true).scanIndexForward(false)
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                    .expressionAttributeValues(Map.of(":pk", DynamoItems.s(DynamoItems.account(account)), ":prefix", DynamoItems.s("PLANLIST#")))
                    .limit(Math.max(1, offset + limit - skipped - ids.size()));
            if (start != null) query.exclusiveStartKey(start);
            var response = read(() -> client.query(query.build()));
            for (var item : response.items()) {
                if (skipped < offset) { skipped++; continue; }
                if (ids.size() < limit) ids.add(DynamoItems.uuid(item, "planId"));
            }
            start = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty() ? response.lastEvaluatedKey() : null;
        } while (start != null && ids.size() < limit);
        return batch(account, ids);
    }

    /**
     * Strongly consistent BatchGetItem (100 keys per call) in the order of the list projection. A projection row whose
     * plan item is absent or tombstoned (a deletion committed between the two reads: the projection row goes in that
     * same transaction, so only this read order can see it) is dropped, never retried: the next list is consistent.
     */
    private List<PlanRecord> batch(UUID account, List<UUID> ids) {
        var records = new HashMap<UUID, PlanRecord>();
        for (int from = 0; from < ids.size(); from += 100) {
            var keys = ids.subList(from, Math.min(ids.size(), from + 100)).stream()
                    .map(id -> DynamoItems.key(DynamoItems.account(account), "PLAN#" + id)).toList();
            var request = BatchGetItemRequest.builder().requestItems(Map.of(accountsTable(),
                    KeysAndAttributes.builder().keys(keys).consistentRead(true).build())).build();
            var response = read(() -> client.batchGetItem(request));
            for (var item : response.responses().getOrDefault(accountsTable(), List.of()))
                if (!DynamoItems.flag(item, "deleted")) { var record = toRecord(item); records.put(record.id(), record); }
            if (response.hasUnprocessedKeys() && !response.unprocessedKeys().isEmpty()) throw new DynamoOwnerUnit.DynamoOwnerUnavailable(
                    software.amazon.awssdk.core.exception.SdkClientException.create("Unprocessed keys in a plan list read"));
        }
        return ids.stream().map(records::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override public Optional<PlanRecord> findPlan(UUID owner, UUID id, boolean lock) {
        var unit = DynamoOwnerUnit.current();
        if (unit != null) {
            var state = pending(unit).plans.get(id);
            if (state != null) return state.deleted ? Optional.empty() : Optional.of(state.pending);
            if (lock) unit.fence(owner);
        }
        var item = get(accountsTable(), DynamoItems.account(owner), "PLAN#" + id);
        if (item.isEmpty() || DynamoItems.flag(item, "deleted")) return Optional.empty();
        var record = toRecord(item);
        if (unit != null && lock) { var state = new PlanState(); state.loaded = record; state.pending = record; pending(unit).plans.put(id, state); }
        return Optional.of(record);
    }

    private PlanRecord toRecord(Map<String, AttributeValue> item) {
        DynamoItems.requireSchema(item, DynamoOwnerUnit.SCHEMA);
        return new PlanRecord(DynamoItems.uuid(item, "planId"), DynamoItems.number(item, "revision"), DynamoItems.uuid(item, "currentVersion"),
                DynamoItems.string(item, "goal"), DynamoItems.json(item.get("progress"), mapper), DynamoItems.string(item, "createdAt"),
                DynamoItems.string(item, "updatedAt"), DynamoItems.string(item, "name"), DynamoItems.number(item, "planNumber"));
    }

    @Override public Optional<PlanVersion> findVersion(UUID owner, UUID id, UUID version) {
        var unit = DynamoOwnerUnit.current();
        if (unit != null) {
            var state = pending(unit).plans.get(id);
            if (state != null && state.deleted) return Optional.empty();
            var cached = pending(unit).versions.get(versionKey(id, version));
            if (cached != null) return Optional.of(cached);
        }
        if (unit == null || !pending(unit).plans.containsKey(id)) {
            var plan = get(accountsTable(), DynamoItems.account(owner), "PLAN#" + id);
            if (plan.isEmpty() || DynamoItems.flag(plan, "deleted")) return Optional.empty();
        }
        var item = get(accountsTable(), planPk(owner, id), "VERSION#" + version);
        if (item.isEmpty()) return Optional.empty();
        DynamoItems.requireSchema(item, DynamoOwnerUnit.SCHEMA);
        var inline = item.get("document");
        byte[] bytes = inline != null && inline.b() != null ? inline.b().asByteArray() : documents.read(owner, DynamoPlanDocuments.Reference.from(item));
        return Optional.of(decodeVersion(version, bytes));
    }

    private PlanVersion decodeVersion(UUID version, byte[] bytes) {
        JsonNode node;
        try { node = mapper.readTree(bytes); } catch (RuntimeException malformed) { throw new IllegalStateException("Stored plan version is malformed"); }
        if (!node.isObject()) throw new IllegalStateException("Stored plan version is malformed");
        return new PlanVersion(version, node.path("snapshot"), node.path("provenance"), node.path("recovery"), node.path("membership"));
    }

    // ---- plan writes ------------------------------------------------------------------------------------------------

    @Override public void createPlan(UUID owner, UUID id, UUID version, String goal, JsonNode progress, String name, long planNumber) {
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            unit.fence(owner);
            String now = unit.now().toString();
            var state = new PlanState();
            state.created = true;
            state.pending = new PlanRecord(id, 1, version, goal, progress, now, now, name, planNumber);
            pending(unit).plans.put(id, state);
            planStatements(unit, owner, state);
            unit.statement(accountsTable(), DynamoItems.account(owner), listKey(state.pending), tx -> tx.insertOnly(accountsTable(),
                    DynamoItems.item(DynamoItems.account(owner), listKey(state.pending), DynamoOwnerUnit.SCHEMA, Map.of("planId", DynamoItems.s(id.toString())))));
            return null;
        });
    }

    private static String listKey(PlanRecord plan) { return DynamoItems.timeKey("PLANLIST", Instant.parse(plan.createdAt()), plan.id()); }

    /** One statement for the plan item: INSERT for a plan created in this unit, else UPDATE under the loaded revision. */
    private void planStatements(DynamoOwnerUnit unit, UUID owner, PlanState state) {
        unit.statement(accountsTable(), DynamoItems.account(owner), "PLAN#" + state.pending.id(), tx -> {
            var plan = state.pending;
            var progressBytes = mapper.writeValueAsBytes(plan.progress());
            if (progressBytes.length > PROGRESS_BYTES) throw new IllegalStateException("Plan progress exceeds the DynamoDB item budget");
            var attributes = new HashMap<String, AttributeValue>(Map.of("revision", DynamoItems.n(plan.revision()),
                    "currentVersion", DynamoItems.s(plan.version().toString()), "goal", DynamoItems.s(plan.goal()),
                    "progress", DynamoItems.json(plan.progress()), "updatedAt", DynamoItems.s(plan.updatedAt()), "name", DynamoItems.s(plan.name())));
            if (state.created) {
                attributes.putAll(Map.of("planId", DynamoItems.s(plan.id().toString()), "createdAt", DynamoItems.s(plan.createdAt()),
                        "planNumber", DynamoItems.n(plan.planNumber()), "deleted", DynamoItems.bool(false)));
                tx.insertOnly(accountsTable(), DynamoItems.item(DynamoItems.account(owner), "PLAN#" + plan.id(), DynamoOwnerUnit.SCHEMA, attributes));
            } else {
                tx.updateAtRevision(accountsTable(), DynamoItems.key(DynamoItems.account(owner), "PLAN#" + plan.id()), attributes, state.loaded.revision());
            }
        });
    }

    private PlanState locked(DynamoOwnerUnit unit, UUID owner, UUID id) {
        var state = pending(unit).plans.get(id);
        if (state == null) {
            state = new PlanState();
            state.loaded = findPlan(owner, id, false).orElseThrow(() -> new IllegalStateException("Plan missing"));
            state.pending = state.loaded;
            unit.fence(owner);
            pending(unit).plans.put(id, state);
        }
        if (state.deleted) throw new IllegalStateException("Plan deleted in this unit");
        return state;
    }

    private void change(UUID owner, UUID id, long revision, java.util.function.BiFunction<PlanRecord, String, PlanRecord> next) {
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            var state = locked(unit, owner, id);
            var plan = next.apply(state.pending, unit.now().toString());
            // A server invariant (PlanService computes revision + 1 from the locked plan), never a client payload fault.
            if (!state.created && revision != state.loaded.revision() + 1) throw new IllegalStateException("Plan update must carry revision + 1");
            state.pending = new PlanRecord(plan.id(), revision, plan.version(), plan.goal(), plan.progress(), plan.createdAt(), plan.updatedAt(), plan.name(), plan.planNumber());
            planStatements(unit, owner, state);
            return null;
        });
    }

    @Override public void rename(UUID owner, UUID id, String name, long revision) {
        change(owner, id, revision, (p, now) -> new PlanRecord(p.id(), p.revision(), p.version(), p.goal(), p.progress(), p.createdAt(), now, name, p.planNumber()));
    }

    @Override public void updateProgress(UUID owner, UUID id, JsonNode progress, long revision) {
        change(owner, id, revision, (p, now) -> new PlanRecord(p.id(), p.revision(), p.version(), p.goal(), progress, p.createdAt(), now, p.name(), p.planNumber()));
    }

    @Override public void activateVersion(UUID owner, UUID id, UUID version, long revision, String goal) {
        change(owner, id, revision, (p, now) -> new PlanRecord(p.id(), p.revision(), version, goal, p.progress(), p.createdAt(), now, p.name(), p.planNumber()));
    }

    @Override public long nextPlanNumber(UUID owner) {
        return DynamoOwnerUnit.run(client, tables, clock, unit -> unit.fence(owner).lastPlanNumber() + 1);
    }

    @Override public long allocatePlanNumber(UUID owner) {
        return DynamoOwnerUnit.run(client, tables, clock, unit -> unit.fence(owner).allocatePlanNumber());
    }

    // ---- versions and activity -------------------------------------------------------------------------------------

    @Override public void insertVersion(UUID owner, UUID id, UUID version, UUID parent, JsonNode snapshot, String digest,
                                        JsonNode pins, JsonNode recovery, String reason, JsonNode membership) {
        ObjectNode document = mapper.createObjectNode();
        document.set("snapshot", snapshot); document.set("provenance", pins); document.set("recovery", recovery); document.set("membership", membership);
        byte[] bytes = mapper.writeValueAsBytes(document);
        var content = new TreeSet<String>();
        membership.properties().forEach(entry -> content.add(entry.getValue().asText()));
        DynamoPlanDocuments.Reference reference = bytes.length > INLINE_BYTES
                ? documents.publish(owner, version, bytes, "plan-version:" + id + ":" + version, "plan-version-publication", "plan.version.publish", "plan-version:" + id) : null;
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            unit.fence(owner);
            pending(unit).versions.put(versionKey(id, version), new PlanVersion(version, snapshot, pins, recovery, membership));
            pending(unit).versionContent.put(id, content);
            var attributes = new HashMap<String, AttributeValue>(Map.of("versionId", DynamoItems.s(version.toString()), "snapshotDigest", DynamoItems.s(digest),
                    "reason", DynamoItems.s(reason), "createdAt", DynamoItems.s(unit.now().toString()), "contentIds", DynamoItems.strings(content)));
            if (parent != null) attributes.put("parentVersion", DynamoItems.s(parent.toString()));
            if (reference == null) attributes.put("document", AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build());
            else attributes.putAll(reference.attributes());
            unit.statement(accountsTable(), planPk(owner, id), "VERSION#" + version, tx -> tx.insertOnly(accountsTable(),
                    DynamoItems.item(planPk(owner, id), "VERSION#" + version, DynamoOwnerUnit.SCHEMA, attributes)));
            if (reference != null) unit.statement(tables.userStudyPlanDetailsTableName(), reference.pk(owner), "MANIFEST", tx -> documents.check(tx, owner, reference));
            return null;
        });
    }

    @Override public void insertActivity(UUID owner, UUID id, UUID version, long revision, String kind, JsonNode payload) {
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            unit.fence(owner);
            String sk = "ACTIVITY#" + DynamoItems.padded(revision) + "#" + unit.id();
            var events = pending(unit).activity.computeIfAbsent(id + "|" + sk, ignored -> new ArrayList<>());
            events.add(Map.of("kind", DynamoItems.s(kind), "versionId", DynamoItems.s(version.toString()), "payload", DynamoItems.json(payload)));
            unit.statement(accountsTable(), planPk(owner, id), sk, tx -> tx.insertOnly(accountsTable(), DynamoItems.item(planPk(owner, id), sk, DynamoOwnerUnit.SCHEMA,
                    Map.of("revision", DynamoItems.n(revision), "recordedAt", DynamoItems.s(unit.now().toString()),
                            "events", AttributeValue.builder().l(events.stream().map(event -> AttributeValue.builder().m(event).build()).toList()).build()))));
            return null;
        });
    }

    @Override public boolean containsHistoricalContent(UUID account, UUID id, String content) {
        var unit = DynamoOwnerUnit.current();
        if (unit != null) {
            var added = pending(unit).versionContent.get(id);
            if (added != null && added.contains(content)) return true;
        }
        // Only the membership is needed: never the inline documents of every version.
        for (var item : DynamoOwnerReads.queryAll(client, accountsTable(), planPk(account, id), "VERSION#", DynamoOwnerReads.CONTENT_IDS))
            if (DynamoItems.stringSet(item, "contentIds").contains(content)) return true;
        return false;
    }

    // ---- receipts -------------------------------------------------------------------------------------------------

    @Override public void lockMutation(UUID account, UUID key) {
        // The owner fence is the lock: read once here, enforced at commit. Distinct keys of one owner serialize on it,
        // and the receipt INSERT makes the same key single-use across replicas.
        DynamoOwnerUnit.run(client, tables, clock, unit -> unit.fence(account));
    }

    @Override public Optional<MutationReceipt> findReceipt(UUID account, UUID key) {
        var item = get(accountsTable(), DynamoItems.account(account), "MUTATION#" + key);
        if (item.isEmpty()) return Optional.empty();
        DynamoItems.requireSchema(item, DynamoOwnerUnit.SCHEMA);
        int status = (int) DynamoItems.number(item, "status");
        UUID planId = DynamoItems.uuid(item, "planId");
        // A receipt written before its plan was deleted answers PLAN_DELETED and never its bytes; the delete's own
        // 204 receipt stays replayable. The retained plan tombstone is the authority, so no receipt is rewritten.
        boolean deleted = DynamoItems.flag(item, "deleted");
        if (!deleted && status != 204) {
            var plan = get(accountsTable(), DynamoItems.account(account), "PLAN#" + planId);
            deleted = plan.isEmpty() || DynamoItems.flag(plan, "deleted");
        }
        JsonNode response = null;
        if (!deleted && status != 204) {
            var inline = item.get("response");
            byte[] bytes = inline != null && inline.b() != null ? inline.b().asByteArray() : documents.read(account, DynamoPlanDocuments.Reference.from(item));
            response = mapper.readTree(bytes);
        }
        return Optional.of(new MutationReceipt(DynamoItems.string(item, "requestHash"), response, status, deleted, planId));
    }

    @Override public void insertReceipt(UUID account, UUID key, String hash, UUID id, JsonNode result, int status) {
        byte[] bytes = status == 204 ? null : mapper.writeValueAsBytes(result);
        DynamoPlanDocuments.Reference reference = bytes != null && bytes.length > INLINE_BYTES
                ? documents.publish(account, DynamoPlanDocuments.operation("plan-receipt", account, key.toString()), bytes,
                        "plan-receipt:" + key, "plan-receipt-publication", "plan.receipt.publish", "plan-receipt:" + id) : null;
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            unit.fence(account);
            var attributes = new HashMap<String, AttributeValue>(Map.of("requestHash", DynamoItems.s(hash), "planId", DynamoItems.s(id.toString()),
                    "status", DynamoItems.n(status), "createdAt", DynamoItems.s(unit.now().toString()), "deleted", DynamoItems.bool(false)));
            if (reference != null) attributes.putAll(reference.attributes());
            else if (bytes != null) attributes.put("response", AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build());
            unit.statement(accountsTable(), DynamoItems.account(account), "MUTATION#" + key, tx -> tx.insertOnly(accountsTable(),
                    DynamoItems.item(DynamoItems.account(account), "MUTATION#" + key, DynamoOwnerUnit.SCHEMA, attributes)));
            var projection = new HashMap<String, AttributeValue>(Map.of("status", DynamoItems.n(status)));
            if (reference != null) projection.putAll(reference.attributes());
            unit.statement(accountsTable(), planPk(account, id), "RECEIPT#" + key, tx -> tx.insertOnly(accountsTable(),
                    DynamoItems.item(planPk(account, id), "RECEIPT#" + key, DynamoOwnerUnit.SCHEMA, projection)));
            if (reference != null) unit.statement(tables.userStudyPlanDetailsTableName(), reference.pk(account), "MANIFEST", tx -> documents.check(tx, account, reference));
            return null;
        });
    }

    @Override public void tombstoneReceipts(UUID account, UUID id) {
        // Nothing to write: findReceipt derives the tombstone from the retained plan marker that deletePlan commits.
    }

    @Override public void deletePlan(UUID account, UUID id) {
        DynamoOwnerUnit.run(client, tables, clock, unit -> {
            var state = locked(unit, account, id);
            state.deleted = true;
            var plan = state.pending;
            String now = unit.now().toString();
            unit.statement(accountsTable(), DynamoItems.account(account), "PLAN#" + id, tx -> tx.updateAtRevision(accountsTable(),
                    DynamoItems.key(DynamoItems.account(account), "PLAN#" + id),
                    Map.of("revision", DynamoItems.n(plan.revision() + 1), "deleted", DynamoItems.bool(true), "deletedAt", DynamoItems.s(now),
                            "updatedAt", DynamoItems.s(now), "progress", DynamoItems.nul(), "goal", DynamoItems.nul(), "name", DynamoItems.nul()),
                    plan.revision()));
            unit.statement(accountsTable(), DynamoItems.account(account), listKey(plan), tx -> tx.delete(accountsTable(), DynamoItems.key(DynamoItems.account(account), listKey(plan)), null));
            // The SQL cascade of plans → plan_lifecycle: the lifecycle row goes with the plan.
            unit.statement(accountsTable(), DynamoItems.account(account), "LIFECYCLE#" + id, tx -> tx.delete(accountsTable(), DynamoItems.key(DynamoItems.account(account), "LIFECYCLE#" + id), null));
            String job = DynamoItems.time(unit.now()) + "#" + account + "#" + id;
            unit.statement(accountsTable(), PURGE_PARTITION, job, tx -> tx.insertOnly(accountsTable(), DynamoItems.item(PURGE_PARTITION, job, DynamoOwnerUnit.SCHEMA,
                    Map.of("owner", DynamoItems.s(account.toString()), "planId", DynamoItems.s(id.toString())))));
            return null;
        });
    }

    private Map<String, AttributeValue> get(String table, String pk, String sk) { return DynamoOwnerReads.get(client, table, pk, sk); }
    List<Map<String, AttributeValue>> queryAll(String pk, String prefix) { return DynamoOwnerReads.queryAll(client, accountsTable(), pk, prefix); }
    static <T> T read(java.util.function.Supplier<T> call) { return DynamoOwnerReads.read(call); }

    /** For the purge: the items of one plan partition and the document references they carry. */
    DynamoPlanDocuments documents() { return documents; }
}
