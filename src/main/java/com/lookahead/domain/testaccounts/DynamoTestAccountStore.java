package com.lookahead.domain.testaccounts;

import com.lookahead.domain.membership.MembershipItems;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lookahead.domain.testaccounts.TestAccountItems.*;

/**
 * DLV-970: the test-account aggregate on DynamoDB. A {@link #transact} body reads registry rows, reservations and
 * purchases, and its writes are committed in one PartiQL transaction fenced on the {@code ACCOUNT#id / META} revision
 * of every account it locked, with reset receipts as insert-only audit events. Pending creation holds expire logically
 * after {@value #HOLD_MINUTES} minutes, checked in code.
 *
 * <p><b>Immediate commits inside a body.</b> The retired PostgreSQL store ran a whole body in one transaction; here some of it is durable
 * before the commit, each piece its own committed operation: {@link Work#createSubject} (the subject
 * {@code ACCOUNT#id / META}: schemaVersion 1, revision, lastPlanNumber, createdAt, which lane B's plan stores fence on
 * for every call the scenario makes inside the body, so it cannot wait), the cross-lane calls on lane A's and lane B's
 * items ({@link Work#deletePlans}, {@link Work#deleteGrants}, {@link Work#grantTopics}, {@link Work#admitTestAccount},
 * {@link Work#erase}) and, outside this store, the scenario's purchases and plans. A body that fails after any of them
 * leaves that state behind: a subject without its registry row (the next seed registers it as {@code preserved}), a
 * roster account with its scenario half built, a reset with new scenario rows beside the old ones. The commit itself is
 * all or nothing, and a Reset self-heals every such account: it supersedes whatever purchases exist, erases and rebuilds
 * the plans and writes the registry row and receipt together. The commit only moves the subject's revision, never
 * inserts it twice. For the accounts whose META other lanes move inside the body (a subject created here, plans erased
 * and rebuilt here) the commit fences on the revision as it stands at commit time; every other locked account keeps the
 * revision read when it was locked, so two bodies counting the same creator's reservations still conflict.
 *
 * <p><b>Conflicts.</b> A failed condition surfaces as {@link DynamoWrites.Conflict} (409). {@link #transactRetrying}
 * re-reads and re-runs a body up to {@value #ATTEMPTS} times, for bodies with nothing but reads and this store's own
 * writes (a creation reservation, the delete's authorisation, the idempotent erasure); a body with side effects (seed,
 * reset, restore, the creation's completion) runs once and keeps the 409, where a row lock would have waited, because re-running it would write its scenario twice.
 *
 * <p><b>Purchases superseded by a reset or restore</b> are read at commit, after the META revision the commit fences on:
 * a checkout committed before that read is in the list and superseded, one committed after it moves the revision and the
 * commit conflicts. A purchase in that list which the body did not see when it asked ({@link Work#supersedePurchases})
 * and which is not one of the scenario rows the body wrote ({@link MembershipItems#fixture}) is a checkout that slipped
 * in after the body decided (for instance whether the account was upgraded and reserved): the commit answers 409
 * REVISION_CONFLICT rather than silently superseding what it never evaluated.
 */
@Component
public class DynamoTestAccountStore implements TestAccountStore {
    static final int HOLD_MINUTES = 15, ATTEMPTS = 3;
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper json;
    private final AccountIdentityLookup lookup;
    private final AccountRecordsMaintenance records;
    private final PlanErasure planErasure;
    private final PlanActivityLookup planActivity;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoTestAccountStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, ObjectProvider<AccountIdentityLookup> lookup,
            ObjectProvider<AccountRecordsMaintenance> records, ObjectProvider<PlanErasure> planErasure, ObjectProvider<PlanActivityLookup> planActivity) {
        this(client, tables, json, lookup.getIfAvailable(), records.getIfAvailable(), planErasure.getIfAvailable(), planActivity.getIfAvailable(), Clock.systemUTC());
    }
    DynamoTestAccountStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, AccountIdentityLookup lookup,
            AccountRecordsMaintenance records, PlanErasure planErasure, PlanActivityLookup planActivity, Clock clock) {
        this.client = Objects.requireNonNull(client); this.tables = Objects.requireNonNull(tables); this.json = Objects.requireNonNull(json);
        this.lookup = lookup; this.records = records; this.planErasure = planErasure; this.planActivity = planActivity; this.clock = Objects.requireNonNull(clock);
    }
    private String data() { return tables.userAccountDetailsTableName(); }
    private String auditTable() { return tables.administratorAuditEventsTableName(); }
    private Instant now() { return clock.instant(); }
    private static AccountRecordsMaintenance require(AccountRecordsMaintenance records) {
        if (records == null) throw new IllegalStateException("Account records maintenance (lane A) is not wired in dynamodb mode");
        return records;
    }

    // ---- registry rows -------------------------------------------------------------------------------------------

    static RegistryRow registryRow(ObjectNode row) {
        return new RegistryRow(UUID.fromString(row.path("accountId").asText()), row.path("username").asText(), row.path("initialization").asText(),
                row.path("creationState").asText(), MembershipItems.unset(row.path("creatorId")) ? null : UUID.fromString(row.path("creatorId").asText()),
                row.path("lastChangedBy").asText(null), Instant.parse(row.path("lastChangedAt").asText()), row.path("revision").asLong());
    }
    private ObjectNode registryNode(RegistryRow r, Instant createdAt, long revision) {
        var row = json.createObjectNode();
        row.put("accountId", r.accountId().toString()).put("username", r.username()).put("initialization", r.initialization()).put("creationState", r.creationState())
                .put("creatorId", r.creatorId() == null ? null : r.creatorId().toString()).put("lastChangedBy", r.lastChangedBy())
                .put("lastChangedAt", r.lastChangedAt().toString()).put("createdAt", createdAt.toString()).put("revision", revision);
        return row;
    }
    private List<RegistryRow> directory() {
        var rows = new ArrayList<RegistryRow>();
        for (var item : MembershipItems.query(client, data(), DIRECTORY_PK, ENTRY_PREFIX)) rows.add(registryRow(MembershipItems.row(item, json)));
        rows.sort(Comparator.comparing(RegistryRow::username));
        return rows;
    }
    private RegistryRow registryOf(UUID id) {
        var item = MembershipItems.get(client, data(), DynamoItems.account(id), REGISTRY_SK);
        return item.isEmpty() ? null : registryRow(MembershipItems.row(item, json));
    }
    private static boolean rosterRow(RegistryRow r) { return r.creatorId() != null && !"created".equals(r.initialization()); }

    @Override public List<RegistryRow> activeRegistry(boolean cloud) {
        return directory().stream().filter(r -> "active".equals(r.creationState()))
                .filter(r -> !cloud || "created".equals(r.initialization()) || r.creatorId() != null).toList();
    }
    @Override public Set<UUID> registeredIds() { var ids = new HashSet<UUID>(); for (var r : directory()) ids.add(r.accountId()); return ids; }
    @Override public Set<UUID> deletedRosterIds() {
        var ids = new HashSet<UUID>();
        for (var r : directory()) if ("deleted".equals(r.creationState()) && rosterRow(r)) ids.add(r.accountId());
        return ids;
    }
    @Override public List<RosterStatus> rosterStatus() {
        var rows = new ArrayList<RosterStatus>();
        for (var r : directory()) {
            if (!rosterRow(r)) continue;
            UUID id = r.accountId();
            Instant since = latestResetAt(client, tables, id);
            Instant written = null;
            for (var p : MembershipItems.purchases(client, tables, json, id))
                for (String field : List.of("createdAt", "updatedAt")) written = later(written, Instant.parse(p.path(field).asText()));
            if (planActivity != null) written = later(written, planActivity.latestPlanWriteAt(id));
            if (lookup != null) written = later(written, lookup.staffAccessUpdatedAt(id));
            boolean hasPlan = planActivity != null && planActivity.hasPlan(id);
            boolean changed = since == null || r.lastChangedAt().isAfter(since) || written != null && written.isAfter(since);
            rows.add(new RosterStatus(id, r.creationState(), hasPlan, changed));
        }
        return rows;
    }
    private static Instant later(Instant a, Instant b) { return a == null ? b : b == null ? a : b.isAfter(a) ? b : a; }
    @Override public List<String> staffRoles(UUID account) { return lookup == null ? List.of() : lookup.staffRoles(account); }
    @Override public String displayName(UUID account) { return lookup == null ? null : lookup.displayName(account); }
    @Override public UUID accountOf(String issuer, String subject) { return lookup == null ? null : lookup.accountOf(issuer, subject); }
    @Override public Holder holder(String issuer, String subject) {
        var holder = lookup == null ? null : lookup.holder(issuer, subject);
        return holder == null ? null : new Holder(holder.accountId(), holder.authorAccess());
    }
    @Override public List<Target> targets(UUID only) {
        var targets = new ArrayList<Target>();
        for (var r : activeRegistry(true)) {
            if (only != null && !only.equals(r.accountId())) continue;
            var admitted = lookup == null ? null : lookup.admitted(r.accountId());
            if (admitted == null || !TestAccountRoster.TEST_ISSUER.equals(admitted.issuer()) || !admitted.admitted() || !admitted.enabled()) continue;
            targets.add(new Target(r.accountId(), r.username(), admitted.displayName()));
        }
        return targets;
    }

    // ---- creations -------------------------------------------------------------------------------------------------

    private List<ObjectNode> creations(UUID creator) {
        var rows = new ArrayList<ObjectNode>();
        for (var item : MembershipItems.query(client, data(), creatorPk(creator), CREATE_PREFIX)) rows.add(MembershipItems.row(item, json));
        return rows;
    }
    private static int spend(List<ObjectNode> creations) {
        return (int) creations.stream().filter(c -> Set.of("active", "deleted").contains(c.path("state").asText())).count();
    }
    @Override public int lifetimeSpend(UUID creator) { return spend(creations(creator)); }
    @Override public <T> T transact(Function<Work, T> body) {
        var snapshot = new Snapshot();
        T result = body.apply(snapshot);
        snapshot.commit();
        return result;
    }
    /** The body is re-run from a fresh snapshot after a lost race, up to {@value #ATTEMPTS} times; see the class note on conflicts. */
    @Override public <T> T transactRetrying(Function<Work, T> body) {
        for (int attempt = 1; ; attempt++) {
            try { return transact(body); }
            catch (DynamoWrites.Conflict conflict) { if (attempt >= ATTEMPTS) throw conflict; }
        }
    }

    private final class Snapshot implements Work {
        private final Map<UUID, Long> fences = new LinkedHashMap<>();
        /** Accounts whose META other lanes move inside this body: fenced at commit on the revision as it then stands. */
        private final Set<UUID> shared = new LinkedHashSet<>();
        private final Map<UUID, ObjectNode> registry = new LinkedHashMap<>();
        private final Set<UUID> registryInserted = new LinkedHashSet<>(), registryDirty = new LinkedHashSet<>();
        private final List<Map<String, AttributeValue>> inserts = new ArrayList<>(), auditInserts = new ArrayList<>();
        /** Fenced updates of existing items: key, set, expected revision. */
        private record Update(Map<String, AttributeValue> key, Map<String, AttributeValue> set, long expected) {}
        private final List<Update> updates = new ArrayList<>();
        /** Accounts whose purchases this body supersedes, with the purchase ids it saw at the time; built at commit under the fence. */
        private final Map<UUID, Set<UUID>> supersede = new LinkedHashMap<>();
        private final Set<String> touched = new HashSet<>();

        private void touch(Map<String, AttributeValue> key) {
            if (!touched.add(key.get("pk").s() + "\u0000" + key.get("sk").s())) throw new IllegalStateException("One item is written once per transaction");
        }
        @Override public void actor(String actor) { /* audit items carry their actor */ }
        @Override public void seedLock() { /* registry INSERTs conflict instead of waiting on a lock */ }
        /** The subject's current META revision, strongly read; null when the account has no subject. */
        private Long metaRevision(UUID id) {
            var meta = MembershipItems.get(client, data(), DynamoItems.account(id), MembershipItems.META_SK);
            return meta.isEmpty() ? null : DynamoItems.number(meta, "revision");
        }
        @Override public void lockSubject(UUID id) {
            if (fences.containsKey(id)) return;
            fences.put(id, metaRevision(id));
        }
        /**
         * Durable at once, in its own transaction: the scenario's plan writes inside this body fence on this item
         * before the body commits. A concurrent creator wins through the INSERT condition; the subject then exists.
         */
        @Override public boolean createSubject(UUID id) {
            lockSubject(id);
            if (fences.get(id) != null) return false;
            String pk = DynamoItems.account(id);
            try {
                // Lane A's subject shape (DynamoCloudSignInRegistry / DynamoAccountStore): revision, the plan-number sequence and createdAt.
                DynamoWrites.transaction(client).insertOnly(data(), DynamoItems.item(pk, MembershipItems.META_SK, SCHEMA,
                        Map.of("revision", DynamoItems.n(1), "lastPlanNumber", DynamoItems.n(0), "createdAt", DynamoItems.s(now().toString())))).commit();
            } catch (DynamoWrites.Conflict concurrent) {
                Long revision = metaRevision(id);
                if (revision == null) throw concurrent;
                fences.put(id, revision); shared.add(id);
                return false;
            }
            fences.put(id, 1L); shared.add(id);
            return true;
        }
        private ObjectNode registryNodeOf(UUID id) {
            if (registry.containsKey(id)) return registry.get(id);
            var item = MembershipItems.get(client, data(), DynamoItems.account(id), REGISTRY_SK);
            var row = item.isEmpty() ? null : MembershipItems.row(item, json);
            registry.put(id, row);
            return row;
        }
        @Override public RegistryRow registry(UUID id) { var row = registryNodeOf(id); return row == null ? null : registryRow(row); }
        private void insertRegistry(UUID id, String username, String initialization, UUID creator, String lastChangedBy) {
            var row = registryNode(new RegistryRow(id, username, initialization, "active", creator, lastChangedBy, now(), 1), now(), 1);
            registry.put(id, row); registryInserted.add(id);
        }
        @Override public boolean registerSeed(UUID id, String username, String initialization) {
            if (registryNodeOf(id) != null) return false;
            insertRegistry(id, username, initialization, null, "seed");
            return true;
        }
        @Override public void registerRoster(UUID id, String username, UUID creator) { insertRegistry(id, username, "scenario", creator, creator.toString()); }
        @Override public void registerCreated(UUID id, String username, UUID creator) { insertRegistry(id, username, "created", creator, creator.toString()); }
        @Override public boolean rosterRow(UUID id) { var r = registry(id); return r != null && DynamoTestAccountStore.rosterRow(r); }
        @Override public boolean deletedRosterRow(UUID id) { var r = registry(id); return r != null && "deleted".equals(r.creationState()) && DynamoTestAccountStore.rosterRow(r); }
        @Override public String resetReceipt(UUID id, UUID key) {
            var item = MembershipItems.get(client, auditTable(), resetPk(id), resetSk(key));
            return item.isEmpty() ? null : DynamoItems.string(item, "actorId") + ":" + DynamoItems.string(item, "preset");
        }
        @Override public Long activeRevision(UUID id) { var r = registry(id); return r == null || !"active".equals(r.creationState()) ? null : r.revision(); }
        private void changeRegistry(UUID id, UUID actor, Map<String, String> fields) {
            var row = registryNodeOf(id);
            if (row == null) return;
            fields.forEach(row::put);
            row.put("lastChangedBy", actor.toString()).put("lastChangedAt", now().toString());
            if (!registryInserted.contains(id)) registryDirty.add(id);
        }
        @Override public void resetRegistry(UUID id, String preset, UUID actor) { changeRegistry(id, actor, Map.of("initialization", preset)); }
        @Override public void restoreRegistry(UUID id, UUID actor) { changeRegistry(id, actor, Map.of("creationState", "active", "initialization", "scenario")); }
        @Override public void deleteRegistry(UUID id, UUID actor) { changeRegistry(id, actor, Map.of("creationState", "deleted")); }
        @Override public void insertReset(UUID id, UUID key, UUID actor, String preset) { auditInserts.add(resetItem(id, key, actor, preset, now())); }
        @Override public void supersedePurchases(UUID id) {
            lockSubject(id);
            var seen = supersede.computeIfAbsent(id, k -> new HashSet<>());
            for (var p : MembershipItems.purchases(client, tables, json, id)) seen.add(UUID.fromString(p.path("id").asText()));
        }
        /** The supersede updates of one account as its purchases stand now (the commit reads them after the fence). */
        private List<Update> supersedeUpdates(UUID id, Set<UUID> seen) {
            var result = new ArrayList<Update>();
            for (var p : MembershipItems.purchases(client, tables, json, id)) {
                UUID purchase = UUID.fromString(p.path("id").asText());
                if (!seen.contains(purchase)) {
                    if (MembershipItems.fixture(p)) continue; // the scenario rows this body wrote after it asked
                    throw new AccountFailure(409, "REVISION_CONFLICT", "The test account changed while it was being reset; reload before retrying");
                }
                if (MembershipItems.unset(p.path("supersededAt"))) p.put("supersededAt", now().toString());
                p.put("updatedAt", now().toString());
                long revision = MembershipItems.revision(p);
                result.add(new Update(DynamoItems.key(DynamoItems.account(id), MembershipItems.purchaseSk(purchase)), MembershipItems.set(p, revision + 1), revision));
            }
            return result;
        }
        @Override public void tombstoneReceipts(UUID id) { /* lane B's resetPlans (called from deletePlans) tombstones the receipts too */ }
        @Override public void deletePlans(UUID id) { shared.add(id); if (planErasure != null) planErasure.resetPlans(id); }
        @Override public void deleteGrants(UUID id) { if (records != null) records.clearAccessRecords(id); }
        @Override public void clearStaffAccess(UUID id) { /* covered by clearAccessRecords in deleteGrants */ }
        @Override public void grantTopics(UUID id, Collection<String> topics) { require(records).grantTopics(id, topics); }
        @Override public boolean hasAccountRecord(UUID id) { return records != null && records.hasAccountRecord(id); }
        @Override public void admitTestAccount(UUID id, String username, String displayName, UUID admittedBy) { require(records).admitTestAccount(id, username, displayName, admittedBy); }
        private ObjectNode creation(UUID creator, UUID key) {
            var item = MembershipItems.get(client, data(), creatorPk(creator), createSk(key));
            return item.isEmpty() ? null : MembershipItems.row(item, json);
        }
        @Override public Reservation reservation(UUID creator, UUID key) {
            var row = creation(creator, key);
            return row == null ? null : new Reservation(UUID.fromString(row.path("accountId").asText()), row.path("username").asText(), row.path("state").asText());
        }
        @Override public int holdsAndSpend(UUID creator) {
            Instant boundary = now().minus(Duration.ofMinutes(HOLD_MINUTES));
            int count = 0;
            for (var c : creations(creator)) {
                String state = c.path("state").asText();
                if (state.equals("active") || state.equals("deleted") || state.equals("pending") && Instant.parse(c.path("createdAt").asText()).isAfter(boundary)) count++;
            }
            return count;
        }
        @Override public int lifetimeSpend(UUID creator) { return spend(creations(creator)); }
        @Override public void reserve(UUID creator, UUID key, UUID account, String username) {
            var row = json.createObjectNode();
            row.put("creatorId", creator.toString()).put("mutationKey", key.toString()).put("accountId", account.toString()).put("username", username)
                    .put("state", "pending").put("createdAt", now().toString()).put("revision", 1);
            inserts.add(MembershipItems.item(creatorPk(creator), createSk(key), row, 1));
            inserts.add(DynamoItems.item(DynamoItems.account(account), CREATION_SK, SCHEMA, Map.of("creatorId", DynamoItems.s(creator.toString()), "mutationKey", DynamoItems.s(key.toString()))));
        }
        /** The creation row of an account through its pointer, with its key; null when none. */
        private Map.Entry<Map<String, AttributeValue>, ObjectNode> creationOf(UUID account) {
            var pointer = MembershipItems.get(client, data(), DynamoItems.account(account), CREATION_SK);
            if (pointer.isEmpty()) return null;
            UUID creator = DynamoItems.uuid(pointer, "creatorId"), key = DynamoItems.uuid(pointer, "mutationKey");
            var row = creation(creator, key);
            return row == null ? null : Map.entry(DynamoItems.key(creatorPk(creator), createSk(key)), row);
        }
        @Override public String creationState(UUID creator, UUID account) {
            var found = creationOf(account);
            return found == null || !creator.toString().equals(found.getValue().path("creatorId").asText()) ? null : found.getValue().path("state").asText();
        }
        private void setCreationState(UUID account, String from, String to, UUID creator) {
            var found = creationOf(account);
            if (found == null) return;
            var row = found.getValue();
            if (creator != null && !creator.toString().equals(row.path("creatorId").asText())) return;
            if (from != null && !from.equals(row.path("state").asText())) return;
            long revision = MembershipItems.revision(row);
            row.put("state", to);
            updates.add(new Update(found.getKey(), MembershipItems.set(row, revision + 1), revision));
        }
        @Override public void activateCreation(UUID creator, UUID account) { setCreationState(account, null, "active", creator); }
        @Override public void deleteCreation(UUID account) { setCreationState(account, null, "deleted", null); }
        @Override public void restoreCreations(UUID account) { setCreationState(account, "deleted", "active", null); }
        @Override public RegistryRow registryLocked(UUID id) { lockSubject(id); return registry(id); }
        /** Committed at once; the rest of this body then fences on the revision the erasure left, so a checkout in between conflicts. */
        @Override public void erase(UUID id, boolean localFixturePurge) {
            Long revision = new DynamoAccountDataErasure(client, tables, json, planErasure, records).eraseFenced(id, localFixturePurge);
            fences.put(id, revision); shared.remove(id);
        }

        void commit() {
            if (registryInserted.isEmpty() && registryDirty.isEmpty() && inserts.isEmpty() && auditInserts.isEmpty() && updates.isEmpty() && supersede.isEmpty()) return;
            var tx = DynamoWrites.transaction(client);
            for (var fence : fences.entrySet()) {
                // The subject exists already (created durably above, or before this body): its revision moves, never inserted twice.
                Long expected = shared.contains(fence.getKey()) ? metaRevision(fence.getKey()) : fence.getValue();
                if (expected == null) continue;
                var key = DynamoItems.key(DynamoItems.account(fence.getKey()), MembershipItems.META_SK);
                touch(key);
                tx.updateAtRevision(data(), key, Map.of("revision", DynamoItems.n(expected + 1)), expected);
            }
            // After the fences are read: the purchases to supersede as they stand now (see the class note).
            for (var entry : supersede.entrySet())
                for (var update : supersedeUpdates(entry.getKey(), entry.getValue())) { touch(update.key()); tx.updateAtRevision(data(), update.key(), update.set(), update.expected()); }
            for (UUID id : registryInserted) {
                var row = registry.get(id);
                var key = DynamoItems.key(DynamoItems.account(id), REGISTRY_SK); touch(key);
                tx.insertOnly(data(), MembershipItems.item(key.get("pk").s(), REGISTRY_SK, row, 1));
                var entry = DynamoItems.key(DIRECTORY_PK, entrySk(row.path("username").asText(), id)); touch(entry);
                tx.insertOnly(data(), MembershipItems.item(DIRECTORY_PK, entry.get("sk").s(), row, 1));
            }
            for (UUID id : registryDirty) {
                var row = registry.get(id);
                long revision = MembershipItems.revision(row);
                var key = DynamoItems.key(DynamoItems.account(id), REGISTRY_SK); touch(key);
                tx.updateAtRevision(data(), key, MembershipItems.set(row, revision + 1), revision);
                var entry = DynamoItems.key(DIRECTORY_PK, entrySk(row.path("username").asText(), id)); touch(entry);
                tx.update(data(), entry, MembershipItems.set(row, revision + 1), null);
            }
            for (var item : inserts) { touch(DynamoItems.key(item.get("pk").s(), item.get("sk").s())); tx.insertOnly(data(), item); }
            for (var update : updates) { touch(update.key()); tx.updateAtRevision(data(), update.key(), update.set(), update.expected()); }
            for (var item : auditInserts) { touch(DynamoItems.key(item.get("pk").s(), item.get("sk").s())); tx.insertOnly(auditTable(), item); }
            if (tx.size() == 0) return; // a supersede of an account without purchases or subject: nothing to write
            tx.commit();
        }
    }
}
