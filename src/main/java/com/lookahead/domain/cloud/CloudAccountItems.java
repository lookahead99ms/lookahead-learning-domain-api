package com.lookahead.domain.cloud;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.testaccounts.TestAccountRoster;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * DLV-970 lane A: the item families of the account, identity, session, admission, access and directory aggregates in
 * User Account Details (and their audit events in Administrator Audit Events), shared by every Dynamo* class of the
 * cloud and access packages so that one slice reads what another wrote.
 * <ul>
 *   <li>{@code ACCOUNT#id / META}: owner controller ({@code revision}, {@code lastPlanNumber}); the platform subject.</li>
 *   <li>{@code ACCOUNT#id / CONTROL}: the account row (cloud_accounts + cloud_account_states) in the shape
 *   {@code DynamoAccountControlStore} reads: issuer, subject, username, admitted, author, managedTest, accountState,
 *   revision, authGeneration, plus displayName, verifiedEmail, enabled, credentialChangePending, requestedAt,
 *   admittedAt, admittedBy. Every session, profile and admission write conditions on its revision.</li>
 *   <li>{@code IDENTITY#issuer#subject / BINDING} → account; {@code ACCOUNT#id / IDENTITY#linkedAt#identityId} list.</li>
 *   <li>{@code EMAIL#email / ACCOUNT#id} (who carries a verified email) and {@code EMAIL#email / META} (the
 *   per-normalised-email sentinel that serialises linking, merge requests and merges).</li>
 *   <li>{@code ACCOUNT#id / SESSION#signInId}, {@code FAMILY#digest / BINDING}, {@code ACCOUNT#id / CHALLENGE#digest}.</li>
 *   <li>{@code ACCOUNT#id / PREFERENCES}, {@code ACCOUNT#id / LINKS}, {@code ACCOUNT#id / GRANT#topic}.</li>
 *   <li>{@code DIRECTORY#00 / PERSON#username#id} and {@code WAITING / ACCOUNT#requestedAt#id} projections.</li>
 *   <li>{@code STAFFACCESS / ASSIGNMENT#id} with {@code ACCOUNT#id / STAFF} and {@code INVITE#email / ASSIGNMENT} pointers.</li>
 *   <li>{@code MERGE#digest / TOKEN} and {@code EMAIL#email / MERGE#createdAt#digest}.</li>
 *   <li>Audit: {@code ACCOUNT#id / ADMISSION#time#id}, {@code STAFF#assignmentId / EVENT#revision#id},
 *   {@code ACCOUNT#id / MAINTENANCE#time#id}, and the control store's {@code ACCOUNT#id / REMOVED} marker.</li>
 * </ul>
 */
public final class CloudAccountItems {
    private CloudAccountItems() { }

    public static final int SCHEMA = 1;
    public static final String DIRECTORY = "DIRECTORY#00";
    public static final String WAITING = "WAITING";
    public static final String STAFF_ACCESS = "STAFFACCESS";

    // ---- keys ----------------------------------------------------------------------------------------------------

    public static String account(UUID id) { return DynamoItems.account(id); }
    public static String identityPk(String issuer, String subject) { return DynamoItems.identity(issuer, subject); }
    public static String identitySk(Instant linkedAt, UUID identityId) { return DynamoItems.timeKey("IDENTITY", linkedAt, identityId); }
    public static String emailPk(String email) { return "EMAIL#" + DynamoItems.encode(CloudIdentities.normalizeEmail(email)); }
    public static String sessionSk(UUID signInId) { return "SESSION#" + signInId; }
    public static String familyPk(String familyDigest) { return "FAMILY#" + familyDigest; }
    public static String challengeSk(String tokenDigest) { return "CHALLENGE#" + tokenDigest; }
    public static String grantSk(String topic) { return "GRANT#" + DynamoItems.encode(topic); }
    public static String personSk(String username, UUID id) { return "PERSON#" + DynamoItems.encode(lower(username)) + "#" + id; }
    public static String waitingSk(Instant requestedAt, UUID id) { return DynamoItems.timeKey("ACCOUNT", requestedAt, id); }
    public static String assignmentSk(UUID assignmentId) { return "ASSIGNMENT#" + assignmentId; }
    public static String invitePk(String email) { return "INVITE#" + DynamoItems.encode(email); }
    public static String mergePk(String tokenDigest) { return "MERGE#" + tokenDigest; }
    public static String mergeSk(Instant createdAt, String tokenDigest) { return "MERGE#" + DynamoItems.time(createdAt) + "#" + tokenDigest; }
    public static String admissionSk(Instant at, UUID id) { return DynamoItems.timeKey("ADMISSION", at, id); }
    public static String staffEventPk(UUID assignmentId) { return "STAFF#" + assignmentId; }
    public static String staffEventSk(long revision, UUID eventId) { return "EVENT#" + DynamoItems.padded(revision) + "#" + eventId; }
    public static String maintenanceSk(Instant at, UUID id) { return DynamoItems.timeKey("MAINTENANCE", at, id); }
    public static String removalJobPk(UUID id) { return "ACCOUNT_REMOVAL#" + id; }

    public static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    // ---- attribute helpers ---------------------------------------------------------------------------------------

    public static AttributeValue s(String value) { return value == null ? null : DynamoItems.s(value); }
    public static AttributeValue n(long value) { return DynamoItems.n(value); }
    public static AttributeValue bool(boolean value) { return DynamoItems.bool(value); }
    public static AttributeValue time(Instant value) { return value == null ? null : DynamoItems.s(value.toString()); }
    public static AttributeValue list(List<String> values) {
        return AttributeValue.builder().l(values.stream().map(DynamoItems::s).toList()).build();
    }
    public static Map<String, AttributeValue> key(String pk, String sk) { return DynamoItems.key(pk, sk); }
    public static Map<String, AttributeValue> item(String pk, String sk, Map<String, AttributeValue> attributes) {
        return DynamoItems.item(pk, sk, SCHEMA, attributes);
    }
    /** A mutable attribute map; null values are dropped by {@link #item}. */
    public static Map<String, AttributeValue> attrs(Object... pairs) {
        var map = new HashMap<String, AttributeValue>();
        for (int at = 0; at < pairs.length; at += 2) if (pairs[at + 1] != null) map.put((String) pairs[at], (AttributeValue) pairs[at + 1]);
        return map;
    }
    public static String text(Map<String, AttributeValue> item, String name) { return DynamoItems.stringOrNull(item, name); }
    public static boolean flag(Map<String, AttributeValue> item, String name) { return DynamoItems.flag(item, name); }
    public static long number(Map<String, AttributeValue> item, String name, long absent) {
        var value = item.get(name);
        return value == null || value.n() == null ? absent : DynamoItems.number(item, name);
    }
    public static Instant instant(Map<String, AttributeValue> item, String name) {
        String value = text(item, name);
        return value == null ? null : Instant.parse(value);
    }
    public static UUID uuid(Map<String, AttributeValue> item, String name) {
        String value = text(item, name);
        return value == null ? null : UUID.fromString(value);
    }
    public static List<String> strings(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        if (value == null || !value.hasL()) return List.of();
        return value.l().stream().map(AttributeValue::s).filter(Objects::nonNull).toList();
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    /** One strongly consistent get; an absent item is an empty map. */
    public static Map<String, AttributeValue> get(DynamoDbClient client, String table, String pk, String sk) {
        return client.getItem(GetItemRequest.builder().tableName(table).key(key(pk, sk)).consistentRead(true).build()).item();
    }

    /** Every item of a partition whose sort key starts with {@code prefix}, strongly consistent, all pages. */
    public static List<Map<String, AttributeValue>> query(DynamoDbClient client, String table, String pk, String prefix) {
        var items = new ArrayList<Map<String, AttributeValue>>();
        Map<String, AttributeValue> start = null;
        do {
            var request = QueryRequest.builder().tableName(table).consistentRead(true)
                    .keyConditionExpression(prefix == null ? "pk = :pk" : "pk = :pk AND begins_with(sk, :prefix)")
                    .expressionAttributeValues(prefix == null ? Map.of(":pk", DynamoItems.s(pk)) : Map.of(":pk", DynamoItems.s(pk), ":prefix", DynamoItems.s(prefix)));
            if (start != null) request.exclusiveStartKey(start);
            QueryResponse response = client.query(request.build());
            items.addAll(response.items());
            start = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty() ? response.lastEvaluatedKey() : null;
        } while (start != null);
        return items;
    }

    /** The {@code EMAIL#email / ACCOUNT#id} projection: which accounts carry a verified email; test-issuer accounts are flagged. */
    public static boolean testIssuer(Map<String, AttributeValue> item) { return TestAccountRoster.TEST_ISSUER.equals(text(item, "issuer")); }
}
