package com.lookahead.domain.persistence.dynamodb;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Read-only, bounded inspection of a quiescent candidate. Not a runtime readiness bean or repair tool. */
public final class DynamoStaffAliasVerifier {
    private static final List<String> ROLES = List.of("co-author", "admin", "reviewer", "tester", "learner");
    public record Limits(int pageSize, int maxPages, int maxAssignments) {
        public Limits {
            if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > 10_000
                    || maxAssignments < 1 || maxAssignments > 100_000) {
                throw new IllegalArgumentException("Invalid alias verification budget");
            }
        }
    }
    public record Result(int assignments, int pages, Map<String, Integer> assignedPerRole) {
        public Result { assignedPerRole = Map.copyOf(assignedPerRole); }
    }
    private record Counter(long last, long revision, UUID initializationId) {}
    private record Alias(UUID accountId, String role) {}
    private record NumberClaim(String role, int number) {}
    private record Fact(Alias alias, NumberClaim claim, Instant time) {}

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    public DynamoStaffAliasVerifier(DynamoDbClient client, DynamoTableNames tables) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    /** Writers must be held by the operator. A counter comparison is not a snapshot/maintenance lock. */
    public Result verify(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        var before = counters();
        var aliases = new HashMap<Alias, Fact>();
        var claims = new HashMap<NumberClaim, Fact>();
        var markers = new HashMap<String, UUID>();
        Map<String, AttributeValue> cursor = Map.of();
        int pages = 0;
        do {
            if (++pages > limits.maxPages()) throw invalid("Scan page budget exhausted");
            var response = client.scan(ScanRequest.builder().tableName(tables.administratorAuditEventsTableName())
                    .consistentRead(true).limit(limits.pageSize()).exclusiveStartKey(cursor.isEmpty() ? null : cursor)
                    .filterExpression("begins_with(pk, :alias) OR begins_with(pk, :role)")
                    .expressionAttributeValues(Map.of(":alias", s("STAFF_ALIAS#"), ":role", s("STAFF_ROLE#"))).build());
            for (var item : response.items()) {
                String pk = string(item, "pk");
                String sk = string(item, "sk");
                schema(item);
                if (pk.startsWith("STAFF_ALIAS#")) {
                    UUID account = uuid(pk.substring("STAFF_ALIAS#".length()));
                    if (!sk.startsWith("ROLE#")) throw invalid("Invalid alias role key");
                    String role = role(sk.substring(5));
                    var alias = new Alias(account, role);
                    var fact = new Fact(alias, new NumberClaim(role, (int) integer(item, "number", 1, Integer.MAX_VALUE)), time(item));
                    if (aliases.putIfAbsent(alias, fact) != null) throw invalid("Duplicate alias encountered");
                } else if (pk.startsWith("STAFF_ROLE#")) {
                    String role = role(pk.substring("STAFF_ROLE#".length()));
                    if (sk.equals("INITIALIZED")) {
                        if (markers.putIfAbsent(role, uuid(string(item, "initializationId"))) != null) throw invalid("Duplicate initialization marker");
                    } else {
                        if (!sk.matches("NUMBER#[0-9]{10}")) throw invalid("Invalid number claim key");
                        long number = Long.parseLong(sk.substring(7));
                        if (number < 1 || number > Integer.MAX_VALUE) throw invalid("Number claim out of range");
                        var claim = new NumberClaim(role, (int) number);
                        var fact = new Fact(new Alias(uuid(string(item, "accountId")), role), claim, time(item));
                        if (claims.putIfAbsent(claim, fact) != null) throw invalid("Duplicate number claim encountered");
                    }
                } else {
                    throw invalid("Unexpected item in filtered alias scan");
                }
                if (aliases.size() > limits.maxAssignments() || claims.size() > limits.maxAssignments()) {
                    throw invalid("Assignment budget exhausted");
                }
            }
            var next = response.lastEvaluatedKey();
            if (!next.isEmpty()) {
                if (next.size() != 2 || string(next, "pk").isBlank() || string(next, "sk").isBlank()
                        || next.equals(cursor)) throw invalid("Invalid scan continuation");
            }
            cursor = next;
        } while (!cursor.isEmpty());
        var after = counters();
        if (!before.equals(after)) throw invalid("Role counters changed during verification");
        var counts = new HashMap<String, Integer>();
        for (String role : ROLES) {
            counts.put(role, 0);
            if (!before.get(role).initializationId().equals(markers.get(role))) throw invalid("Missing or mismatched initialization marker");
        }
        for (var fact : aliases.values()) {
            if (!fact.equals(claims.get(fact.claim()))) throw invalid("Alias has no matching number claim");
            if (fact.claim().number() > before.get(fact.alias().role()).last()) throw invalid("Counter is behind assigned number");
            counts.merge(fact.alias().role(), 1, Integer::sum);
        }
        for (var fact : claims.values()) {
            if (!fact.equals(aliases.get(fact.alias()))) throw invalid("Number claim has no matching alias");
        }
        return new Result(aliases.size(), pages, counts);
    }

    private Map<String, Counter> counters() {
        var result = new HashMap<String, Counter>();
        for (String role : ROLES) {
            var item = client.getItem(GetItemRequest.builder().tableName(tables.userAccountDetailsTableName())
                    .key(Map.of("pk", s("STAFF_ROLE#" + role), "sk", s("COUNTER"))).consistentRead(true).build()).item();
            schema(item);
            result.put(role, new Counter(integer(item, "lastAssigned", 0, Integer.MAX_VALUE),
                    integer(item, "revision", 0, Long.MAX_VALUE), uuid(string(item, "initializationId"))));
        }
        return result;
    }

    private static void schema(Map<String, AttributeValue> item) {
        if (!AttributeValue.builder().n("1").build().equals(item.get("schemaVersion"))) throw invalid("Missing or invalid alias schema");
    }
    private static String string(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        if (value == null || value.s() == null) throw invalid("Missing string attribute");
        return value.s();
    }
    private static long integer(Map<String, AttributeValue> item, String name, long min, long max) {
        try {
            long value = Long.parseLong(item.get(name).n());
            if (value < min || value > max) throw invalid("Number outside supported range");
            return value;
        } catch (RuntimeException failure) { throw invalid("Invalid numeric attribute"); }
    }
    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw invalid("Noncanonical account/initialization UUID");
            return id;
        } catch (RuntimeException failure) { throw invalid("Invalid account/initialization UUID"); }
    }
    private static String role(String role) {
        if (!ROLES.contains(role)) throw invalid("Unsupported numbered role");
        return role;
    }
    private static Instant time(Map<String, AttributeValue> item) {
        try {
            String value = string(item, "assignedAt");
            Instant time = Instant.parse(value);
            if (time.isBefore(Instant.EPOCH) || time.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw invalid("Time outside supported range");
            if (!time.toString().equals(value)) throw invalid("Noncanonical assignment time");
            return time;
        } catch (RuntimeException failure) { throw invalid("Invalid assignment time"); }
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
