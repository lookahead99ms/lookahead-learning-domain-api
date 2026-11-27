package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Instant;
import java.util.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970 (lane C): the DynamoDB item families of the membership aggregate, shared by every class that reads or writes
 * purchases ({@link MembershipStore}, {@link MembershipGrantsStore}, {@link MembershipAccessSummaryStore}, the
 * test-account fixtures and erasure).
 * <ul>
 *   <li>{@code CATALOG / PLAN#<id>} definition + {@code priceCents}, {@code CATALOG / META} catalog revision.</li>
 *   <li>{@code SALES / SALE#<startsAt>#<id>} intervals, {@code SALES / META} revision (no-overlap fence).</li>
 *   <li>{@code ACCOUNT#<id> / MEMBERSHIP#<purchaseId>} the purchase row (public fields plus key, actor, times and the
 *   bounded payment-attempt list), {@code PURCHASE#<purchaseId> / ACCOUNT} its owner pointer.</li>
 *   <li>{@code PROVIDEREVENT#mock#<encoded eventId> / RECEIPT} provider-event dedup.</li>
 *   <li>Audit table: {@code MEMBERSHIP#<subject> / EVENT#<time>#<auditId>}, insert-only.</li>
 *   <li>{@code MOCKPAYMENT#<encoded key> / RECEIPT} and {@code MOCKPAYMENTID#<encoded paymentId> / KEY}: the Local/DEV
 *   mock provider's durable idempotency (a fixture concern, never production membership data).</li>
 * </ul>
 */
public final class MembershipItems {
    private MembershipItems() {}
    public static final int SCHEMA = 1;
    public static final String CATALOG_PK = "CATALOG", META_SK = "META", PLAN_PREFIX = "PLAN#";
    public static final String SALES_PK = "SALES", SALE_PREFIX = "SALE#";
    public static final String MEMBERSHIP_PREFIX = "MEMBERSHIP#";
    public static final String RECEIPT_SK = "RECEIPT";
    /** Internal purchase attributes the API row never shows. */
    static final Set<String> INTERNAL = Set.of("idempotencyKey", "actorId", "accountId", "createdAt", "updatedAt", "paymentAttempts", "revision");
    private static final Set<String> KEYS = Set.of("pk", "sk", "schemaVersion");
    /**
     * The request fingerprint of a scenario (fixture) purchase, in both stores: a checkout row carries the SHA-256 of its
     * request, a row the test-account scenario wrote carries this. The reset tells the two apart by it (see
     * {@link #fixture}).
     */
    public static final String FIXTURE_FINGERPRINT = "0".repeat(64);

    public static String planSk(String id) { return PLAN_PREFIX + id; }
    public static String saleSk(Instant startsAt, UUID id) { return DynamoItems.timeKey("SALE", startsAt, id); }
    public static String purchaseSk(UUID id) { return MEMBERSHIP_PREFIX + id; }
    public static String purchasePointerPk(UUID id) { return "PURCHASE#" + id; }
    public static String providerEventPk(String provider, String eventId) { return "PROVIDEREVENT#" + provider + "#" + DynamoItems.encode(eventId); }
    public static String mockPaymentPk(String key) { return "MOCKPAYMENT#" + DynamoItems.encode(key); }
    public static String mockPaymentIdPk(String paymentId) { return "MOCKPAYMENTID#" + DynamoItems.encode(paymentId); }
    /** Subjects are purchase/sale ids or plan codes; anything else is length-delimited. */
    public static String auditPk(String subject) { return "MEMBERSHIP#" + (subject.matches("[A-Za-z0-9-]{1,64}") ? subject : DynamoItems.encode(subject)); }
    public static String auditSk(Instant at, UUID auditId) { return DynamoItems.timeKey("EVENT", at, auditId); }

    // ---- generic strong reads -----------------------------------------------------------------------------------

    public static Map<String, AttributeValue> get(DynamoDbClient client, String table, String pk, String sk) {
        return DynamoItems.strongGet(client, table, pk, sk);
    }
    /** Every item of a partition whose sort key starts with {@code prefix}, through all pages, in sort-key order. */
    public static List<Map<String, AttributeValue>> query(DynamoDbClient client, String table, String pk, String prefix) {
        var items = new ArrayList<Map<String, AttributeValue>>();
        Map<String, AttributeValue> start = null;
        do {
            var request = QueryRequest.builder().tableName(table).consistentRead(true)
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                    .expressionAttributeValues(Map.of(":pk", DynamoItems.s(pk), ":prefix", DynamoItems.s(prefix)));
            if (start != null) request.exclusiveStartKey(start);
            var page = client.query(request.build());
            items.addAll(page.items());
            start = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        return items;
    }

    // ---- JSON rows <-> items --------------------------------------------------------------------------------------

    /** Every non-key attribute as a JSON field; {@code revision} as a number. */
    public static ObjectNode row(Map<String, AttributeValue> item, ObjectMapper mapper) {
        DynamoItems.requireSchema(item, SCHEMA);
        var row = mapper.createObjectNode();
        for (var entry : item.entrySet()) if (!KEYS.contains(entry.getKey())) row.set(entry.getKey(), DynamoItems.json(entry.getValue(), mapper));
        return row;
    }
    /** The row's fields as the SET of an UPDATE (every non-key attribute), {@code revision} replaced by the one given. */
    public static Map<String, AttributeValue> set(ObjectNode row, long revision) {
        var attributes = new LinkedHashMap<String, AttributeValue>();
        for (var entry : row.properties()) if (!KEYS.contains(entry.getKey()) && !entry.getKey().equals("revision")) attributes.put(entry.getKey(), DynamoItems.json(entry.getValue()));
        attributes.put("revision", DynamoItems.n(revision));
        return attributes;
    }
    /** The row as a whole new item under the given key. */
    public static Map<String, AttributeValue> item(String pk, String sk, ObjectNode row, long revision) {
        return DynamoItems.item(pk, sk, SCHEMA, set(row, revision));
    }
    public static long revision(ObjectNode row) { return row.path("revision").asLong(); }

    /** The API row of a stored purchase: the public fields only, as the SQL mapper produced them. */
    public static ObjectNode publicRow(ObjectNode stored) {
        var row = stored.deepCopy();
        INTERNAL.forEach(row::remove);
        return row;
    }
    /** A stored purchase as the SQL INSERT would have written it. */
    public static ObjectNode storedPurchase(ObjectMapper mapper, UUID id, UUID account, String planId, JsonNode plan, String fingerprint, String key,
            int price, String status, Instant startsAt, Instant endsAt, String source, UUID actor, String providerPaymentId,
            String providerStatus, UUID replacesId, Instant supersededAt, JsonNode discount, Instant now) {
        var p = mapper.createObjectNode();
        p.put("id", id.toString()).put("planId", planId).set("plan", plan.deepCopy());
        p.put("pricePaidCents", price).put("currency", "USD").put("status", status).put("startsAt", startsAt.toString()).put("endsAt", endsAt.toString())
                .put("source", source).put("provider", "mock").put("providerPaymentId", providerPaymentId).put("providerStatus", providerStatus)
                .put("replacesId", replacesId == null ? null : replacesId.toString()).put("supersededAt", supersededAt == null ? null : supersededAt.toString())
                .put("fingerprint", fingerprint);
        p.putArray("courses");
        if (discount == null || discount.isNull()) p.putNull("discount"); else p.set("discount", discount.deepCopy());
        p.put("idempotencyKey", key).put("actorId", actor.toString()).put("accountId", account.toString())
                .put("createdAt", now.toString()).put("updatedAt", now.toString());
        p.putArray("paymentAttempts");
        p.put("revision", 0);
        return p;
    }
    /** Purchase fields the SQL row always carries (as NULL when unset); an item written without one reads the same. */
    private static final Set<String> OPTIONAL = Set.of("supersededAt", "replacesId", "providerPaymentId", "providerStatus", "discount");
    /** A stored purchase as a row: {@link #row} with every optional field present, so {@code isNull()} checks downstream hold for an absent attribute too. */
    public static ObjectNode purchaseRow(Map<String, AttributeValue> item, ObjectMapper mapper) {
        var row = row(item, mapper);
        for (String field : OPTIONAL) if (row.path(field).isMissingNode()) row.putNull(field);
        return row;
    }
    /** The account's stored purchases, newest first (created time, then id). */
    public static List<ObjectNode> purchases(DynamoDbClient client, DynamoTableNames tables, ObjectMapper mapper, UUID account) {
        var rows = new ArrayList<ObjectNode>();
        for (var item : query(client, tables.userAccountDetailsTableName(), DynamoItems.account(account), MEMBERSHIP_PREFIX)) rows.add(purchaseRow(item, mapper));
        rows.sort(byTime("createdAt").thenComparing(p -> p.path("id").asText()).reversed());
        return rows;
    }
    /**
     * Chronological order on a stored time field. Times are stored as {@code Instant.toString()}, which prints 0, 3, 6 or
     * 9 fraction digits, so the text does not sort chronologically ({@code …00Z} sorts after {@code …00.000001Z}): every
     * comparison parses.
     */
    public static Comparator<ObjectNode> byTime(String field) { return Comparator.comparing(p -> Instant.parse(p.path(field).asText())); }
    /** A field that is absent or stored as NULL: both read as "not set" (an item written without the attribute is as unset as one cleared to NULL). */
    public static boolean unset(JsonNode field) { return field == null || field.isNull() || field.isMissingNode(); }
    /** A purchase the test-account scenario wrote (its fingerprint is {@link #FIXTURE_FINGERPRINT}), never a checkout's. */
    public static boolean fixture(ObjectNode p) { return FIXTURE_FINGERPRINT.equals(p.path("fingerprint").asText(null)); }

    // ---- audit --------------------------------------------------------------------------------------------------

    public static Map<String, AttributeValue> auditItem(String subject, UUID auditId, UUID actor, String action, JsonNode detail, Instant at) {
        var attributes = new LinkedHashMap<String, AttributeValue>();
        attributes.put("id", DynamoItems.s(auditId.toString()));
        attributes.put("actor", DynamoItems.s(actor.toString()));
        attributes.put("action", DynamoItems.s(action));
        attributes.put("subjectId", DynamoItems.s(subject));
        attributes.put("occurredAt", DynamoItems.s(at.toString()));
        attributes.put("detail", DynamoItems.json(detail));
        return DynamoItems.item(auditPk(subject), auditSk(at, auditId), SCHEMA, attributes);
    }
    /** The audit rows of a subject, oldest first. */
    public static List<ObjectNode> auditEvents(DynamoDbClient client, DynamoTableNames tables, ObjectMapper mapper, String subject) {
        var rows = new ArrayList<ObjectNode>();
        for (var item : query(client, tables.administratorAuditEventsTableName(), auditPk(subject), "EVENT#")) rows.add(row(item, mapper));
        return rows;
    }
}
