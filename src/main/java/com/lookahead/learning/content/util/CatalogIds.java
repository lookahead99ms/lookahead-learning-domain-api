package com.lookahead.learning.content.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-931: the catalog that used the data ID {@code look-ahead} is now {@code blaze}.
 *
 * <p>Only the catalog ID changes. The platform keeps its Look Ahead name, so storage keys such as
 * {@code look-ahead.study-plan.v1}, Java packages, headers and environment names are untouched.
 * Old browsers, bookmarks and saved local plans may still send the legacy ID; every place that
 * parses catalog IDs maps them here so the rest of the service only sees {@code blaze}.
 *
 * <p>Rewritten forms (anything else is returned unchanged):
 * <ul>
 *   <li>a whole {@code look-ahead} token in a colon-separated ID: {@code look-ahead},
 *       {@code look-ahead:course}, {@code look-ahead:course:lesson}, {@code content:look-ahead:...},
 *       and session IDs that embed a content ID such as {@code look-ahead:course:lesson:review:v2:3};</li>
 *   <li>a page route {@code /look-ahead}, {@code /look-ahead/...}, {@code /look-ahead?...}, {@code /look-ahead#...};</li>
 *   <li>content assets {@code /content/look-ahead/...} and {@code /content/indexes/look-ahead.json}.</li>
 * </ul>
 * The same rules are used by the V5 database migration.
 */
public final class CatalogIds {
    public static final String CATALOG = "blaze";
    public static final String LEGACY_CATALOG = "look-ahead";
    /** Topic {@code path} values a saved plan may carry. */
    public static final Set<String> PATHS = Set.of("learn", "grow", CATALOG);
    /** A course scope or topic ID after normalization, for example {@code blaze:system-design}. */
    public static final String SCOPE_PATTERN = "(?:learn|grow|" + CATALOG + "):[a-z0-9-]+";

    private static final Pattern ID_TOKEN = Pattern.compile("(^|:)look-ahead(?=:|$)");
    private static final Pattern ROUTE = Pattern.compile("^/look-ahead(?=$|[/?#])");

    private CatalogIds() {}

    public static String normalize(String value) {
        if (value == null || !value.contains(LEGACY_CATALOG)) return value;
        String result = ID_TOKEN.matcher(value).replaceAll("$1" + CATALOG);
        result = ROUTE.matcher(result).replaceFirst("/" + CATALOG);
        return result.replace("/content/look-ahead/", "/content/" + CATALOG + "/")
                .replace("/content/indexes/look-ahead.json", "/content/indexes/" + CATALOG + ".json");
    }

    public static boolean isLegacy(String value) {
        return value != null && !value.equals(normalize(value));
    }

    /** Normalizes every member; a legacy ID and its new form collapse into one member. */
    public static Set<String> normalize(Collection<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) result.add(normalize(value));
        return result;
    }

    /**
     * Returns a normalized deep copy; object keys and identifier values are both rewritten.
     * Learner and author prose (plan names, goals, titles, notes and similar fields) is never
     * changed, even if it happens to read {@code look-ahead}. When a legacy key or array string
     * collides with a value already in its new form, the new-form value wins and the legacy
     * duplicate is dropped.
     */
    public static JsonNode normalize(JsonNode node) {
        return normalize(node, null);
    }

    /** True when any key or identifier value in the tree still uses the legacy catalog ID. */
    public static boolean containsLegacy(JsonNode node) {
        return node != null && !node.equals(normalize(node));
    }

    /** Fields whose string values are prose, never catalog identifiers. */
    private static final Set<String> TEXT_FIELDS = Set.of("goal", "name", "customName", "title", "topicTitle",
            "courseTitle", "description", "focus", "phase", "label", "instructions", "text", "reason",
            "intendedUse", "startingKnowledge", "uncoveredScope", "displayName");
    /** Maps keyed by content ID whose values are learner-written notes. */
    private static final Set<String> NOTE_MAPS = Set.of("notes", "reviewNotes");

    private static boolean prose(String field) { return field != null && TEXT_FIELDS.contains(field); }

    private static JsonNode normalize(JsonNode node, String field) {
        if (node == null) return null;
        if (node.isString()) {
            String text = node.asText(), normalized = prose(field) ? text : normalize(text);
            return normalized.equals(text) ? node.deepCopy() : JsonNodeFactory.instance.stringNode(normalized);
        }
        if (node.isObject()) {
            boolean notes = field != null && NOTE_MAPS.contains(field);
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Set<String> current = new HashSet<>();
            for (var entry : node.properties()) {
                String key = entry.getKey(), normalized = normalize(key);
                boolean renamed = !normalized.equals(key);
                if (renamed && current.contains(normalized)) continue;
                if (!renamed) current.add(key);
                result.set(normalized, notes ? entry.getValue().deepCopy() : normalize(entry.getValue(), key));
            }
            return result;
        }
        if (node.isArray()) {
            if (prose(field)) return node.deepCopy();
            Set<String> present = new HashSet<>();
            for (JsonNode element : node) if (element.isString()) present.add(element.asText());
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            Set<String> added = new HashSet<>();
            for (JsonNode element : node) {
                if (element.isString()) {
                    String text = element.asText(), normalized = normalize(text);
                    if (!normalized.equals(text) && (present.contains(normalized) || added.contains(normalized))) continue;
                    added.add(normalized);
                    result.add(normalized);
                } else result.add(normalize(element, field));
            }
            return result;
        }
        return node.deepCopy();
    }
}
