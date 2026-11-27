package com.lookahead.domain.db.store.access;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Read-only, key-based inspection of the five permanent role counters: migration step 2's {@code verify} and the
 * restore check. Per role it reads by key, strongly: the {@code STAFF_ROLE#role / COUNTER} item, its
 * {@code INITIALIZED} marker (same initialization ID, canonical numbers), and the counter's high-water mark against
 * the immutable claims: the last assigned number has a claim whose alias points back at it with the same time, and
 * the next number has no claim (a counter rolled back behind a retained claim would hand that number out again).
 * At most five GetItems per role, never a Scan: Administrator Audit Events is append-only and grows without bound,
 * so a whole-table pass would one day exhaust any page budget and fail a healthy store. It changes nothing.
 */
public final class StaffAliasVerificationStore {
    private static final List<String> ROLES = List.of("co-author", "admin", "reviewer", "tester", "learner");
    private static final Instant LAST_TIME = Instant.parse("9999-12-31T23:59:59Z");

    /** The high-water mark of every role (its {@code lastAssigned}) and their sum. */
    public record Result(long assigned, Map<String, Long> assignedPerRole) {
        public Result { assignedPerRole = Map.copyOf(assignedPerRole); }
    }

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    public StaffAliasVerificationStore(DynamoDbClient client, DynamoTableNames tables) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    /** Safe while the allocator runs: every check is on items that never change once written, or on one strong read. */
    public Result verify() {
        var perRole = new HashMap<String, Long>();
        long total = 0;
        for (String role : ROLES) {
            String roleKey = "STAFF_ROLE#" + role;
            var counter = get(tables.userAccountDetailsTableName(), roleKey, "COUNTER");
            if (counter.isEmpty()) throw invalid("Missing role counter");
            schema(counter);
            long last = integer(counter, "lastAssigned", 0, Integer.MAX_VALUE);
            integer(counter, "revision", 0, Long.MAX_VALUE);
            UUID initialization = uuid(string(counter, "initializationId"));
            var marker = get(tables.administratorAuditEventsTableName(), roleKey, "INITIALIZED");
            if (marker.isEmpty()) throw invalid("Missing initialization marker");
            schema(marker);
            if (!initialization.equals(uuid(string(marker, "initializationId")))) throw invalid("Mismatched initialization marker");
            if (last > 0) {
                var claim = get(tables.administratorAuditEventsTableName(), roleKey, numberKey(last));
                if (claim.isEmpty()) throw invalid("Counter is ahead of its number claims");
                schema(claim);
                UUID account = uuid(string(claim, "accountId"));
                Instant time = time(claim);
                var alias = get(tables.administratorAuditEventsTableName(), "STAFF_ALIAS#" + account, "ROLE#" + role);
                if (alias.isEmpty()) throw invalid("Number claim has no matching alias");
                schema(alias);
                if (integer(alias, "number", 1, Integer.MAX_VALUE) != last || !time(alias).equals(time)) throw invalid("Alias does not match its number claim");
            }
            if (last < Integer.MAX_VALUE && !get(tables.administratorAuditEventsTableName(), roleKey, numberKey(last + 1)).isEmpty()) {
                throw invalid("Counter is behind assigned number");
            }
            perRole.put(role, last);
            total += last;
        }
        return new Result(total, perRole);
    }

    private Map<String, AttributeValue> get(String table, String pk, String sk) {
        return DynamoItems.strongGet(client, table, pk, sk);
    }

    private static String numberKey(long number) { return "NUMBER#" + String.format(Locale.ROOT, "%010d", number); }
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
    private static Instant time(Map<String, AttributeValue> item) {
        try {
            String value = string(item, "assignedAt");
            Instant time = Instant.parse(value);
            if (time.isBefore(Instant.EPOCH) || time.isAfter(LAST_TIME)) throw invalid("Time outside supported range");
            if (!time.toString().equals(value)) throw invalid("Noncanonical assignment time");
            return time;
        } catch (RuntimeException failure) { throw invalid("Invalid assignment time"); }
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
