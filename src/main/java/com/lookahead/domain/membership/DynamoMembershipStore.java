package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.domain.testaccounts.TestAccountItems;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lookahead.domain.membership.MembershipItems.*;
import static com.lookahead.domain.membership.MembershipRules.invalid;

/**
 * DLV-970: the membership aggregate on DynamoDB. A purchase transaction reads the account's purchases under
 * {@code ACCOUNT#id / META}, runs the service's body on that snapshot and commits every changed purchase at its exact
 * revision, the META revision bump, the audit INSERTs, the provider-event receipt and a check on the catalog revision in
 * one {@code TransactWriteItems}. A failed condition re-reads and re-runs the body (the mock provider is idempotent under
 * the purchase key) up to {@value #ATTEMPTS} times, then surfaces {@link DynamoWrites.Conflict}.
 */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoMembershipStore implements MembershipStore {
    static final int ATTEMPTS = 3;
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper json;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoMembershipStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json) { this(client, tables, json, Clock.systemUTC()); }
    public DynamoMembershipStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, Clock clock) {
        this.client = Objects.requireNonNull(client, "client"); this.tables = Objects.requireNonNull(tables, "tables");
        this.json = Objects.requireNonNull(json, "json"); this.clock = Objects.requireNonNull(clock, "clock");
    }
    private String data() { return tables.userAccountDetailsTableName(); }
    private String auditTable() { return tables.administratorAuditEventsTableName(); }
    private Instant now() { return clock.instant(); }

    // ---- catalog -------------------------------------------------------------------------------------------------

    private static ObjectNode definitionOf(ObjectNode row) {
        var p = (ObjectNode) row.path("definition").deepCopy();
        p.put("priceCents", row.path("priceCents").asInt());
        return p;
    }
    @Override public List<ObjectNode> catalog() {
        var rows = new ArrayList<ObjectNode>();
        for (var item : query(client, data(), CATALOG_PK, PLAN_PREFIX)) rows.add(definitionOf(row(item, json)));
        rows.sort(Comparator.comparingInt((ObjectNode p) -> p.path("priceCents").asInt()).thenComparing(p -> p.path("id").asText()));
        return rows;
    }
    @Override public ObjectNode definition(String id) {
        var item = get(client, data(), CATALOG_PK, planSk(id));
        return item.isEmpty() ? null : definitionOf(row(item, json));
    }
    /** The catalog revision, or null when the catalog has no META item yet (nothing to fence). */
    private Long catalogRevision() {
        var meta = get(client, data(), CATALOG_PK, META_SK);
        return meta.isEmpty() ? null : DynamoItems.number(meta, "revision");
    }
    @Override public PriceChange changePrice(UUID actor, String id, int price) {
        for (int attempt = 1; ; attempt++) {
            var item = get(client, data(), CATALOG_PK, planSk(id));
            if (item.isEmpty()) throw invalid("Unknown membership plan");
            var stored = row(item, json);
            int before = stored.path("priceCents").asInt();
            Long catalog = catalogRevision();
            var changed = stored.deepCopy();
            changed.put("priceCents", price).put("updatedAt", now().toString());
            var definition = definitionOf(changed);
            var tx = DynamoWrites.transaction(client)
                    .updateAtRevision(data(), DynamoItems.key(CATALOG_PK, planSk(id)), set(changed, revision(stored) + 1), revision(stored))
                    .insertOnly(auditTable(), auditItem(id, UUID.randomUUID(), actor, "price-changed", definition, now()));
            fence(tx, CATALOG_PK, catalog);
            try { tx.commit(); return new PriceChange(before, definition); }
            catch (DynamoWrites.Conflict conflict) { if (attempt >= ATTEMPTS) throw conflict; }
        }
    }
    /** Bumps a revision controller ({@code CATALOG / META}, {@code SALES / META}, {@code ACCOUNT#id / META}) at its exact revision. */
    private void fence(DynamoWrites tx, String pk, Long expected) {
        if (expected == null) tx.insertOnly(data(), DynamoItems.item(pk, META_SK, SCHEMA, Map.of("revision", DynamoItems.n(1), "createdAt", DynamoItems.s(now().toString()))));
        else tx.updateAtRevision(data(), DynamoItems.key(pk, META_SK), Map.of("revision", DynamoItems.n(expected + 1)), expected);
    }

    // ---- sales ---------------------------------------------------------------------------------------------------

    private List<ObjectNode> sales() {
        var rows = new ArrayList<ObjectNode>();
        for (var item : query(client, data(), SALES_PK, SALE_PREFIX)) rows.add(row(item, json));
        return rows;
    }
    private ObjectNode saleRow(ObjectNode stored) {
        var p = json.createObjectNode();
        p.put("id", stored.path("id").asText()).put("template", stored.path("template").asText()).put("greeting", stored.path("greeting").asText())
                .put("startsAt", stored.path("startsAt").asText()).put("endsAt", stored.path("endsAt").asText());
        p.set("planIds", stored.path("planIds").deepCopy());
        return p;
    }
    private static boolean running(ObjectNode sale, Instant now) {
        return unset(sale.path("stoppedAt")) && !Instant.parse(sale.path("startsAt").asText()).isAfter(now) && Instant.parse(sale.path("endsAt").asText()).isAfter(now);
    }
    @Override public ObjectNode activeSale(Instant now) {
        return sales().stream().filter(s -> running(s, now)).max(byTime("startsAt")).map(this::saleRow).orElse(null);
    }
    private Long salesRevision() {
        var meta = get(client, data(), SALES_PK, META_SK);
        return meta.isEmpty() ? null : DynamoItems.number(meta, "revision");
    }
    @Override public UUID startSale(UUID actor, MembershipService.Sale sale) {
        for (int attempt = 1; ; attempt++) {
            Long revision = salesRevision();
            boolean overlap = sales().stream().anyMatch(s -> unset(s.path("stoppedAt"))
                    && Instant.parse(s.path("startsAt").asText()).isBefore(sale.endsAt()) && Instant.parse(s.path("endsAt").asText()).isAfter(sale.startsAt()));
            if (overlap) throw new AccountFailure(409, "SALE_OVERLAP", "A sale already covers this period");
            UUID id = UUID.randomUUID();
            var stored = json.createObjectNode();
            stored.put("id", id.toString()).put("template", sale.template()).put("greeting", sale.greeting()).put("startsAt", sale.startsAt().toString())
                    .put("endsAt", sale.endsAt().toString()).put("createdBy", actor.toString()).put("createdAt", now().toString()).putNull("stoppedAt");
            stored.set("planIds", json.valueToTree(sale.planIds()));
            var tx = DynamoWrites.transaction(client).insertOnly(data(), item(SALES_PK, saleSk(sale.startsAt(), id), stored, 1))
                    .insertOnly(auditTable(), auditItem(id.toString(), UUID.randomUUID(), actor, "sale-started", json.createObjectNode().put("id", id.toString()), now()));
            fence(tx, SALES_PK, revision);
            try { tx.commit(); return id; }
            catch (DynamoWrites.Conflict conflict) { if (attempt >= ATTEMPTS) throw conflict; }
        }
    }
    @Override public void stopSale(UUID actor, UUID id, Instant now) {
        for (int attempt = 1; ; attempt++) {
            var stored = sales().stream().filter(s -> s.path("id").asText().equals(id.toString())).findFirst()
                    .orElseThrow(() -> new AccountFailure(404, "SALE_NOT_FOUND", "Sale not found"));
            var changed = stored.deepCopy();
            if (unset(changed.path("stoppedAt"))) changed.put("stoppedAt", now.toString());
            String sk = saleSk(Instant.parse(stored.path("startsAt").asText()), id);
            var tx = DynamoWrites.transaction(client).updateAtRevision(data(), DynamoItems.key(SALES_PK, sk), set(changed, revision(stored) + 1), revision(stored))
                    .insertOnly(auditTable(), auditItem(id.toString(), UUID.randomUUID(), actor, "sale-stopped", json.createObjectNode(), now()));
            try { tx.commit(); return; }
            catch (DynamoWrites.Conflict conflict) { if (attempt >= ATTEMPTS) throw conflict; }
        }
    }

    // ---- purchases, reads ------------------------------------------------------------------------------------------

    private ObjectNode stored(UUID account, UUID id) {
        var item = get(client, data(), DynamoItems.account(account), purchaseSk(id));
        return item.isEmpty() ? null : purchaseRow(item, json);
    }
    @Override public ObjectNode purchase(UUID account, UUID id) { var p = stored(account, id); return p == null ? null : publicRow(p); }
    private static List<UUID> accessIds(List<ObjectNode> purchases) {
        return purchases.stream().filter(p -> Set.of("succeeded", "scheduled", "expired").contains(p.path("status").asText()))
                .map(p -> UUID.fromString(p.path("id").asText())).toList();
    }
    @Override public List<UUID> accessIds(UUID account) { return accessIds(purchases(client, tables, json, account)); }
    @Override public List<JsonNode> activeSnapshots(UUID account, Instant now) {
        return purchases(client, tables, json, account).stream().filter(p -> grantsAt(p, now))
                .sorted(Comparator.comparing((ObjectNode p) -> p.path("planId").asText()).thenComparing(p -> p.path("id").asText()))
                .map(p -> (JsonNode) p.path("plan")).toList();
    }
    @Override public boolean staffCreatedTestAccount(UUID account) {
        var item = get(client, data(), DynamoItems.account(account), TestAccountItems.REGISTRY_SK);
        if (item.isEmpty()) return false;
        var registry = row(item, json);
        return "active".equals(registry.path("creationState").asText()) && !unset(registry.path("creatorId"))
                && registry.path("username").asText().endsWith("@lookahead.dev");
    }
    private UUID owner(UUID purchaseId) {
        var pointer = get(client, data(), purchasePointerPk(purchaseId), "ACCOUNT");
        return pointer.isEmpty() ? null : DynamoItems.uuid(pointer, "accountId");
    }
    private static int replacementsScheduled(List<ObjectNode> purchases, UUID replacesId) {
        return (int) purchases.stream().filter(p -> replacesId.toString().equals(p.path("replacesId").asText(null))
                && Set.of("pending", "scheduled").contains(p.path("status").asText())).count();
    }
    @Override public int replacementsScheduled(UUID replacesId) {
        UUID account = owner(replacesId);
        return account == null ? 0 : replacementsScheduled(purchases(client, tables, json, account), replacesId);
    }
    @Override public PendingRow pendingRequest(UUID account, String awaiting) {
        return purchases(client, tables, json, account).stream()
                .filter(p -> "pending".equals(p.path("status").asText()) && awaiting.equals(p.path("providerStatus").asText(null)) && unset(p.path("supersededAt")))
                .min(byTime("createdAt").thenComparing(p -> p.path("id").asText()))
                .map(p -> new PendingRow(UUID.fromString(p.path("id").asText()), Instant.parse(p.path("createdAt").asText()))).orElse(null);
    }
    @Override public UUID requestedBy(UUID purchase) {
        return auditEvents(client, tables, json, purchase.toString()).stream().filter(e -> "purchase-created".equals(e.path("action").asText()))
                .map(e -> e.path("detail").path("requestedBy")).filter(JsonNode::isString).findFirst().map(v -> UUID.fromString(v.asText())).orElse(null);
    }
    /** The SQL {@code ORDER BY recorded_at DESC, a.id DESC LIMIT 1}: the latest approval event, ties broken by the event id. */
    @Override public Approval approvedRequest(UUID account) {
        Instant since = TestAccountItems.latestResetAt(client, tables, account);
        Approval latest = null; String latestEvent = null;
        for (var p : purchases(client, tables, json, account)) {
            if (!Set.of("succeeded", "scheduled").contains(p.path("status").asText())) continue;
            UUID id = UUID.fromString(p.path("id").asText());
            for (var e : auditEvents(client, tables, json, id.toString())) {
                if (!"purchase-approved".equals(e.path("action").asText())) continue;
                Instant at = Instant.parse(e.path("occurredAt").asText());
                if (since != null && !at.isAfter(since)) continue;
                String event = e.path("id").asText();
                if (latest == null || at.isAfter(latest.approvedAt()) || at.equals(latest.approvedAt()) && event.compareTo(latestEvent) > 0) {
                    latest = new Approval(id, UUID.fromString(e.path("actor").asText()), at); latestEvent = event;
                }
            }
        }
        return latest;
    }
    private static List<String> decisions(List<ObjectNode> events) {
        return events.stream().map(e -> e.path("action").asText()).filter(a -> a.equals("purchase-approved") || a.equals("purchase-declined")).toList();
    }

    // ---- the purchase transaction ----------------------------------------------------------------------------------

    @Override public <T> T transact(UUID actor, Function<Work, T> body) {
        for (int attempt = 1; ; attempt++) {
            var snapshot = new Snapshot();
            T result = body.apply(snapshot);
            try { snapshot.commit(); return result; }
            catch (DynamoWrites.Conflict conflict) { if (attempt >= ATTEMPTS) throw conflict; }
        }
    }

    /** One account's purchases read under its META revision; writes change the copy and are committed together. */
    private final class Snapshot implements Work {
        private UUID account;
        private Long metaRevision;
        private final Map<UUID, ObjectNode> purchases = new LinkedHashMap<>();
        private final Set<UUID> dirty = new LinkedHashSet<>(), created = new LinkedHashSet<>();
        private final List<Map<String, AttributeValue>> audits = new ArrayList<>(), receipts = new ArrayList<>();
        private final Set<String> eventsSeen = new HashSet<>();
        private Long catalogRevision; private boolean catalogRead;

        @Override public void lockAccount(UUID target) {
            var meta = get(client, data(), DynamoItems.account(target), META_SK);
            if (meta.isEmpty()) throw new AccountFailure(404, "ACCOUNT_NOT_FOUND", "Account not found");
            account = target; metaRevision = DynamoItems.number(meta, "revision");
            purchases.clear();
            for (var p : MembershipItems.purchases(client, tables, json, target)) purchases.put(UUID.fromString(p.path("id").asText()), p);
        }
        private List<ObjectNode> rows(UUID target) {
            return target.equals(account) ? List.copyOf(purchases.values()) : MembershipItems.purchases(client, tables, json, target);
        }
        private ObjectNode owned(UUID id) {
            var p = purchases.get(id);
            if (p == null) throw new IllegalStateException("Purchase is not part of the locked account");
            return p;
        }
        @Override public UUID purchaseAccount(UUID purchaseId) { return owner(purchaseId); }
        @Override public ObjectNode purchase(UUID target, UUID id, boolean lock) {
            var p = target.equals(account) ? purchases.get(id) : stored(target, id);
            return p == null ? null : publicRow(p);
        }
        @Override public List<UUID> purchasesByKey(UUID target, String key) {
            return rows(target).stream().filter(p -> key.equals(p.path("idempotencyKey").asText(null))).map(p -> UUID.fromString(p.path("id").asText())).toList();
        }
        @Override public List<String> pendingProviderStatuses(UUID target, String awaiting) {
            var statuses = new ArrayList<String>();
            for (var p : rows(target)) {
                if (!"pending".equals(p.path("status").asText())) continue;
                String status = unset(p.path("providerStatus")) ? "" : p.path("providerStatus").asText();
                if (!status.equals(awaiting) || unset(p.path("supersededAt"))) statuses.add(status);
            }
            return statuses;
        }
        @Override public ObjectNode definition(String id) {
            if (!catalogRead) { catalogRevision = catalogRevision(); catalogRead = true; }
            return DynamoMembershipStore.this.definition(id);
        }
        @Override public ObjectNode activeSale(Instant now) { return DynamoMembershipStore.this.activeSale(now); }
        @Override public int replacementsScheduled(UUID replacesId) {
            UUID target = purchases.values().stream().anyMatch(p -> p.path("id").asText().equals(replacesId.toString())) ? account : owner(replacesId);
            return target == null ? 0 : DynamoMembershipStore.replacementsScheduled(rows(target), replacesId);
        }
        @Override public List<UUID> accessIds(UUID target) { return DynamoMembershipStore.accessIds(rows(target)); }
        @Override public void insertPurchase(NewPurchase p) {
            if (!p.account().equals(account)) throw new IllegalStateException("Purchase outside the locked account");
            var stored = storedPurchase(json, p.id(), p.account(), p.planId(), p.plan(), p.fingerprint(), p.key(), p.price(), "pending", p.startsAt(), p.endsAt(),
                    p.source(), p.actor(), null, null, p.replacesId(), null, null, now());
            purchases.put(p.id(), stored); created.add(p.id());
        }
        @Override public int paymentAttempts(UUID purchase) { return owned(purchase).path("paymentAttempts").size(); }
        @Override public void recordAttempt(UUID purchase, String status, String error) {
            var attempt = owned(purchase).withArray("paymentAttempts").addObject();
            attempt.put("id", UUID.randomUUID().toString()).put("status", status).put("error", error).put("at", now().toString());
            dirty.add(purchase);
        }
        @Override public void failValidation(UUID purchase) { owned(purchase).put("status", "failed").put("providerStatus", "validation-failed"); dirty.add(purchase); }
        @Override public void decline(UUID purchase, String providerStatus) {
            owned(purchase).put("status", "failed").put("providerStatus", providerStatus).put("updatedAt", now().toString()); dirty.add(purchase);
        }
        @Override public void settle(UUID purchase, String state, String providerPaymentId, String providerStatus) {
            owned(purchase).put("status", state).put("providerPaymentId", providerPaymentId).put("providerStatus", providerStatus).put("updatedAt", now().toString());
            dirty.add(purchase);
        }
        @Override public boolean recordProviderEvent(String eventId, UUID purchase) {
            String pk = providerEventPk("mock", eventId);
            if (!eventsSeen.add(pk) || !get(client, data(), pk, RECEIPT_SK).isEmpty()) return false;
            receipts.add(DynamoItems.item(pk, RECEIPT_SK, SCHEMA, Map.of("purchaseId", DynamoItems.s(purchase.toString()), "occurredAt", DynamoItems.s(now().toString()))));
            return true;
        }
        @Override public void supersede(UUID target, UUID purchase, Instant at) {
            if (!target.equals(account)) throw new IllegalStateException("Purchase outside the locked account");
            var p = purchases.get(purchase);
            if (p == null) return;
            p.put("supersededAt", at.toString()); dirty.add(purchase);
        }
        @Override public void audit(UUID actor, String action, String subject, JsonNode detail) {
            audits.add(auditItem(subject, UUID.randomUUID(), actor, action, detail, now()));
        }
        @Override public List<String> decisions(UUID purchase) { return DynamoMembershipStore.decisions(auditEvents(client, tables, json, purchase.toString())); }

        void commit() {
            if (created.isEmpty() && dirty.isEmpty() && audits.isEmpty() && receipts.isEmpty()) return;
            if (account == null) throw new IllegalStateException("Writes require a locked account");
            var tx = DynamoWrites.transaction(client);
            fence(tx, DynamoItems.account(account), metaRevision);
            for (UUID id : created) {
                tx.insertOnly(data(), item(DynamoItems.account(account), purchaseSk(id), purchases.get(id), 1));
                tx.insertOnly(data(), DynamoItems.item(purchasePointerPk(id), "ACCOUNT", SCHEMA, Map.of("accountId", DynamoItems.s(account.toString()))));
            }
            for (UUID id : dirty) {
                if (created.contains(id)) continue;
                var p = purchases.get(id);
                tx.updateAtRevision(data(), DynamoItems.key(DynamoItems.account(account), purchaseSk(id)), set(p, revision(p) + 1), revision(p));
            }
            for (var item : receipts) tx.insertOnly(data(), item);
            for (var item : audits) tx.insertOnly(auditTable(), item);
            if (catalogRead && catalogRevision != null)
                tx.check(data(), DynamoItems.key(CATALOG_PK, META_SK), SCHEMA, "revision=?", DynamoItems.n(catalogRevision));
            tx.commit();
        }
    }
}
