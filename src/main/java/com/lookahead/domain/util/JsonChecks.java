package com.lookahead.domain.util;

import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * The small checks on a JSON value that the account catalog, the study plan snapshot validator and
 * the import mapper share: each returns the checked value or throws IllegalArgumentException with
 * the same message as before. DLV-1016: these were private static methods of
 * StudyPlanSnapshotValidator, moved here word for word.
 */
public final class JsonChecks {
  private JsonChecks() {}

  public static void fields(JsonNode node, String allowed) {
    object(node);
    Set<String> names = Set.of(allowed.split(" "));
    for (var entry : node.properties())
      require(names.contains(entry.getKey()), "Unknown field: " + entry.getKey());
  }

  public static void object(JsonNode node) {
    require(node != null && node.isObject(), "Expected object");
  }

  public static JsonNode array(JsonNode node, String name, int max) {
    JsonNode value = node.path(name);
    require(value.isArray() && value.size() <= max, "Invalid array: " + name);
    return value;
  }

  public static List<String> strings(JsonNode node, String name, int max) {
    List<String> result = new ArrayList<>();
    for (JsonNode value : array(node, name, max)) {
      require(value.isString(), "Invalid identifier");
      boundedText(value.asText(), 256);
      require(!value.asText().isBlank(), "Blank identifier");
      result.add(value.asText());
    }
    require(new HashSet<>(result).size() == result.size(), "Duplicate identifier");
    return result;
  }

  public static List<String> optionalStrings(JsonNode node, String name, int max) {
    return node.has(name) ? strings(node, name, max) : List.of();
  }

  public static String text(JsonNode node, String name, int max) {
    JsonNode value = node.path(name);
    require(value.isString(), "Invalid text: " + name);
    boundedText(value.asText(), max);
    return value.asText();
  }

  public static String optionalText(JsonNode node, String name, int max) {
    return !node.has(name) || node.path(name).isNull() ? null : text(node, name, max);
  }

  public static void boundedText(String value, int max) {
    require(
        value.length() <= max
            && value.codePoints().noneMatch(c -> c < 32 && c != 10 && c != 9 && c != 13),
        "Invalid or oversized text");
  }

  public static int integer(JsonNode node, String name, int min, int max) {
    JsonNode value = node.path(name);
    require(
        value.isIntegralNumber()
            && value.canConvertToInt()
            && value.asInt() >= min
            && value.asInt() <= max,
        "Invalid integer: " + name);
    return value.asInt();
  }

  public static void number(JsonNode node, String name, double min, double max) {
    JsonNode value = node.path(name);
    require(
        value.isNumber()
            && Double.isFinite(value.asDouble())
            && value.asDouble() >= min
            && value.asDouble() <= max,
        "Invalid number: " + name);
  }

  public static void enumValue(JsonNode node, String name, Set<String> values) {
    require(values.contains(text(node, name, 64)), "Invalid value: " + name);
  }

  public static void optionalEnum(JsonNode node, String name, Set<String> values) {
    if (node.has(name)) enumValue(node, name, values);
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
