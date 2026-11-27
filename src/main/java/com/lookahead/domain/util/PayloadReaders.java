package com.lookahead.domain.util;

import static com.lookahead.domain.util.JsonChecks.require;
import com.lookahead.domain.exception.AccountFailure;
import tools.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Bounded JSON request field readers shared by HTTP and service boundaries. */
public final class PayloadReaders {
    private PayloadReaders() {}

    /**
     * A UUID written in full (36 characters); upper case letters are accepted here, unlike {@link CanonicalUuid#parse},
     * which reads the lower-cased text. Anything else: 400 INVALID_ID.
     */
    public static UUID uuid(String value) {
        UUID id = value == null ? null : CanonicalUuid.parse(value.toLowerCase(Locale.ROOT));
        if (id == null) throw new AccountFailure(400, "INVALID_ID", "A canonical UUID is required");
        return id;
    }

    public static void fields(JsonNode node, String allowed) {
        require(node.isObject(), "Expected JSON object");
        Set<String> names = Set.of(allowed.split(" "));
        for (var property : node.properties()) {
            require(names.contains(property.getKey()), "Unknown field: " + property.getKey());
        }
    }

    public static String text(JsonNode node, String key, int max) {
        String value = textAllowEmpty(node, key, max);
        require(!value.isBlank(), key + " is required");
        return value;
    }

    public static String textAllowEmpty(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        require(value.isString() && value.asText().length() <= max, "Invalid " + key);
        return value.asText();
    }

    public static boolean bool(JsonNode node, String key) {
        require(node.path(key).isBoolean(), "Invalid " + key);
        return node.path(key).asBoolean();
    }

    public static int integer(JsonNode node, String key, int min, int max) {
        JsonNode value = node.path(key);
        require(value.isIntegralNumber() && value.asLong() >= min && value.asLong() <= max, "Invalid " + key);
        return value.asInt();
    }
}
