package com.lookahead.domain.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stable JSON representation and digest used by snapshot and retry receipts. */
@Component
public final class StudyPlanJson {
  private final ObjectMapper mapper;

  public StudyPlanJson(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      var names = new TreeSet<String>();
      node.properties().forEach(e -> names.add(e.getKey()));
      for (String name : names) sorted.set(name, parse(canonical(node.path(name))));
      return json(sorted);
    }
    if (node.isArray()) {
      var array = mapper.createArrayNode();
      node.forEach(n -> array.add(parse(canonical(n))));
      return json(array);
    }
    return json(node);
  }

  public String digest(String value) {
    try {
      return "sha256:" + Sha256.hex(value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public String json(JsonNode value) {
    return mapper.writeValueAsString(value);
  }

  public JsonNode parse(String value) {
    return mapper.readTree(value);
  }

  /**
   * The content id a plan assignment points at, as written: its {@code sourceContentId} when it has
   * one, else its own id. DLV-1016: the plan service and the recovery validator each had this line.
   */
  public static String sourceId(JsonNode assignment) {
    return assignment.hasNonNull("sourceContentId")
        ? assignment.path("sourceContentId").asText()
        : assignment.path("id").asText();
  }

  /**
   * Every assignment of a plan snapshot by its id, in day order. DLV-1016: the plan service and the
   * recovery validator each had this loop.
   */
  public static Map<String, JsonNode> assignments(JsonNode snapshot) {
    Map<String, JsonNode> result = new LinkedHashMap<>();
    for (JsonNode day : snapshot.path("days"))
      for (JsonNode a : day.path("assignments")) result.put(a.path("id").asText(), a);
    return result;
  }
}
