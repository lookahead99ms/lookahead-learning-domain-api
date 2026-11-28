package com.lookahead.domain.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-1016: the shared JSON checks that left StudyPlanSnapshotValidator; each keeps its message.
 */
class JsonChecksTest {
  private final JsonNode node =
      new JsonMapper()
          .readTree(
              """
              {"name":"Plan","count":3,"ratio":1.5,"kind":"a","items":["x","y"],"nothing":null,\
              "object":{}}
              """);

  private static String refused(Runnable call) {
    return assertThrows(IllegalArgumentException.class, call::run).getMessage();
  }

  @Test
  void textAndNumbersAreReturnedWhenTheyFit() {
    assertEquals("Plan", JsonChecks.text(node, "name", 10));
    assertNull(JsonChecks.optionalText(node, "nothing", 10));
    assertNull(JsonChecks.optionalText(node, "absent", 10));
    assertEquals(3, JsonChecks.integer(node, "count", 1, 5));
    assertEquals(List.of("x", "y"), JsonChecks.strings(node, "items", 5));
    assertEquals(List.of(), JsonChecks.optionalStrings(node, "absent", 5));
    assertEquals(2, JsonChecks.array(node, "items", 5).size());
    assertDoesNotThrow(() -> JsonChecks.number(node, "ratio", 1, 2));
    assertDoesNotThrow(() -> JsonChecks.enumValue(node, "kind", Set.of("a", "b")));
    assertDoesNotThrow(() -> JsonChecks.optionalEnum(node, "absent", Set.of("a")));
    assertDoesNotThrow(() -> JsonChecks.object(node.path("object")));
    assertDoesNotThrow(() -> JsonChecks.fields(node.path("object"), "anything"));
  }

  @Test
  void eachRefusalKeepsItsMessage() {
    assertEquals("Invalid text: count", refused(() -> JsonChecks.text(node, "count", 10)));
    assertEquals("Invalid or oversized text", refused(() -> JsonChecks.text(node, "name", 3)));
    assertEquals(
        "Invalid or oversized text", refused(() -> JsonChecks.boundedText("a\u0000b", 10)));
    assertEquals(
        "Invalid value: kind", refused(() -> JsonChecks.enumValue(node, "kind", Set.of("b"))));
    assertEquals("Expected object", refused(() -> JsonChecks.object(node.path("items"))));
    assertEquals("nope", refused(() -> JsonChecks.require(false, "nope")));
    assertThrows(IllegalArgumentException.class, () -> JsonChecks.integer(node, "count", 4, 5));
    assertThrows(IllegalArgumentException.class, () -> JsonChecks.number(node, "ratio", 2, 3));
    assertThrows(IllegalArgumentException.class, () -> JsonChecks.strings(node, "items", 1));
    assertThrows(IllegalArgumentException.class, () -> JsonChecks.array(node, "name", 5));
    assertThrows(IllegalArgumentException.class, () -> JsonChecks.fields(node, "name count"));
  }
}
