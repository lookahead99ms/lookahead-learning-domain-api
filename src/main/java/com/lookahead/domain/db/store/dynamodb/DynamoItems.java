package com.lookahead.domain.db.store.dynamodb;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: the one key and attribute encoding every adapter uses, so items written by one slice are read by another.
 * <ul>
 *   <li>Partition keys are {@code FAMILY#<id>}; UUIDs are canonical lower-case text.</li>
 *   <li>Arbitrary text that enters a key (usernames, emails, external IDs) is length-delimited
 *   ({@code v1#<UTF-8 byte length>:<text>}), never concatenated raw with a separator.</li>
 *   <li>Time sort keys are fixed-width UTC with nine fractional digits plus a tie-breaking ID.</li>
 *   <li>Numbers that sort as text are zero-padded to ten digits.</li>
 *   <li>JSON payloads are stored as DynamoDB maps/lists (readable, filterable), with {@code schemaVersion} on every item.</li>
 * </ul>
 */
public final class DynamoItems {
    private DynamoItems() { }

    static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    // ---- keys --------------------------------------------------------------------------------------------------

    public static String account(UUID accountId) { return "ACCOUNT#" + accountId; }
    public static String plan(UUID owner, UUID planId) { return "PLAN#" + owner + "#" + planId; }
    public static String identity(String issuer, String subject) { return "IDENTITY#" + encode(issuer) + "#" + encode(subject); }

    /** Length-delimited text for keys: {@code v1#<bytes>:<text>}; at most 1,024 UTF-8 bytes, no control characters. */
    public static String encode(String text) {
        if (text == null) throw new IllegalArgumentException("Key text is required");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 1024 || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Key text must be 1 through 1,024 UTF-8 bytes without control characters");
        return "v1#" + bytes.length + ":" + text;
    }

    /** The text inside an {@link #encode encoded} key segment; a damaged segment fails closed. */
    public static String decode(String segment) {
        try {
            if (!segment.startsWith("v1#")) throw new IllegalArgumentException();
            int colon = segment.indexOf(':', 3);
            int length = Integer.parseInt(segment.substring(3, colon));
            String text = segment.substring(colon + 1);
            if (text.getBytes(StandardCharsets.UTF_8).length != length) throw new IllegalArgumentException();
            return text;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Malformed key segment");
        }
    }

    /** Fixed-width UTC time with nine fractional digits: sorts chronologically as text. */
    public static String time(Instant at) {
        if (at.isBefore(Instant.EPOCH) || at.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw new IllegalArgumentException("Time outside supported range");
        return TIME.format(at);
    }

    /** {@code <time>#<id>}: chronological order with a stable tie-breaker. */
    public static String timeKey(String prefix, Instant at, UUID id) { return prefix + "#" + time(at) + "#" + id; }

    /** Ten zero-padded digits; sorts numerically as text up to 9,999,999,999. */
    public static String padded(long number) {
        if (number < 0 || number > 9_999_999_999L) throw new IllegalArgumentException("Number outside padded range");
        return String.format("%010d", number);
    }

    // ---- attribute values ----------------------------------------------------------------------------------------

    public static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    public static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    public static AttributeValue bool(boolean value) { return AttributeValue.builder().bool(value).build(); }
    public static AttributeValue nul() { return AttributeValue.builder().nul(true).build(); }
    public static AttributeValue strings(Set<String> values) {
        return values.isEmpty() ? AttributeValue.builder().l(List.of()).build()
                : AttributeValue.builder().l(values.stream().sorted().map(DynamoItems::s).toList()).build();
    }

    public static Map<String, AttributeValue> key(String pk, String sk) { return Map.of("pk", s(pk), "sk", s(sk)); }
    /** DLV-1016: the one strongly consistent GetItem by pk and sk; an absent item is an empty map. SDK failures pass through unchanged. */
    public static Map<String, AttributeValue> strongGet(DynamoDbClient client, String table, String pk, String sk) {
        return client.getItem(GetItemRequest.builder().tableName(table).key(key(pk, sk)).consistentRead(true).build()).item();
    }

    public static String string(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        if (value == null || value.s() == null) throw new IllegalStateException("Stored item lacks text attribute " + name);
        return value.s();
    }

    public static String stringOrNull(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        return value == null || value.s() == null ? null : value.s();
    }

    public static long number(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        if (value == null || value.n() == null || !value.n().matches("-?(0|[1-9][0-9]{0,18})")) throw new IllegalStateException("Stored item lacks number attribute " + name);
        return Long.parseLong(value.n());
    }

    public static boolean flag(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        return value != null && Boolean.TRUE.equals(value.bool());
    }

    public static Set<String> stringSet(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        if (value == null || !value.hasL()) return Set.of();
        return Set.copyOf(value.l().stream().map(AttributeValue::s).toList());
    }

    public static UUID uuid(Map<String, AttributeValue> item, String name) {
        try { return UUID.fromString(string(item, name)); }
        catch (IllegalArgumentException malformed) { throw new IllegalStateException("Stored item has a malformed UUID in " + name); }
    }

    public static Instant instant(Map<String, AttributeValue> item, String name) {
        try { return Instant.parse(string(item, name)); }
        catch (DateTimeParseException malformed) { throw new IllegalStateException("Stored item has a malformed time in " + name); }
    }

    /** Requires {@code schemaVersion} to be exactly {@code expected}; other versions need an explicit migration step. */
    public static void requireSchema(Map<String, AttributeValue> item, int expected) {
        if (item.isEmpty() || number(item, "schemaVersion") != expected) throw new IllegalStateException("Stored item has an unexpected schema version");
    }

    // ---- JSON ----------------------------------------------------------------------------------------------------

    /** A JSON tree as a DynamoDB value: objects → M, arrays → L, strings → S, numbers → N, booleans → BOOL, null → NULL. */
    public static AttributeValue json(JsonNode node) {
        if (node == null || node.isNull()) return nul();
        if (node.isObject()) {
            var map = new LinkedHashMap<String, AttributeValue>();
            for (var entry : node.properties()) map.put(entry.getKey(), json(entry.getValue()));
            return AttributeValue.builder().m(map).build();
        }
        if (node.isArray()) {
            var list = new ArrayList<AttributeValue>();
            for (JsonNode element : node) list.add(json(element));
            return AttributeValue.builder().l(list).build();
        }
        if (node.isBoolean()) return bool(node.booleanValue());
        if (node.isNumber()) return AttributeValue.builder().n(node.numberValue().toString()).build();
        return s(node.asString());
    }

    /** The inverse of {@link #json(JsonNode)}; a damaged value fails closed. */
    public static JsonNode json(AttributeValue value, ObjectMapper mapper) {
        if (value == null || Boolean.TRUE.equals(value.nul())) return mapper.nullNode();
        if (value.hasM()) {
            ObjectNode object = mapper.createObjectNode();
            for (var entry : value.m().entrySet()) object.set(entry.getKey(), json(entry.getValue(), mapper));
            return object;
        }
        if (value.hasL()) {
            ArrayNode array = mapper.createArrayNode();
            for (var element : value.l()) array.add(json(element, mapper));
            return array;
        }
        if (value.bool() != null) return mapper.getNodeFactory().booleanNode(value.bool());
        if (value.n() != null) {
            try { return mapper.readTree(value.n()); }
            catch (RuntimeException malformed) { throw new IllegalStateException("Stored number is malformed"); }
        }
        if (value.s() != null) return mapper.getNodeFactory().textNode(value.s());
        throw new IllegalStateException("Stored value has an unsupported type");
    }

    /** A copy with the key and schema version set; attributes with null values are dropped. */
    public static Map<String, AttributeValue> item(String pk, String sk, int schemaVersion, Map<String, AttributeValue> attributes) {
        var item = new HashMap<String, AttributeValue>();
        attributes.forEach((name, value) -> { if (value != null) item.put(name, value); });
        item.put("pk", s(pk));
        item.put("sk", s(sk));
        item.put("schemaVersion", n(schemaVersion));
        return Map.copyOf(item);
    }
}
