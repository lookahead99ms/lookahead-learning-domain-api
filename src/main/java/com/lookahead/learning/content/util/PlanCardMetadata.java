package com.lookahead.learning.content.util;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Read-only facts from the current saved version, not a recommendation or reservation. */
public final class PlanCardMetadata {
    private PlanCardMetadata() {}

    public static ObjectNode from(JsonNode snapshot, JsonNode progress, ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "plan-card/v1");
        // Relative study days and database creation timestamps cannot establish these facts.
        result.putNull("lifecycleState");
        result.putNull("reservation");
        JsonNode config = snapshot.path("config");
        if (!config.isObject() || !snapshot.path("days").isArray()
                || !progress.path("completedSessionIds").isArray()) {
            result.put("metadataStatus", "unavailable");
            for (String field : new String[]{"selectedTopicIds", "durationDays", "configuredDailyMinutes",
                    "completedSessionCount", "totalSessionCount", "nextScheduledActivity"}) result.putNull(field);
            return result;
        }
        result.put("metadataStatus", "available");
        Set<String> topics = new TreeSet<>();
        for (JsonNode topic : config.path("topicIds")) if (topic.isString()) topics.add(topic.asText());
        result.set("selectedTopicIds", mapper.valueToTree(topics));
        if (config.path("days").isIntegralNumber() && config.path("days").asInt() > 0)
            result.put("durationDays", config.path("days").asInt());
        else result.putNull("durationDays");
        if (config.path("dailyHours").isNumber())
            result.put("configuredDailyMinutes", config.path("dailyHours").decimalValue().multiply(BigDecimal.valueOf(60)));
        else result.putNull("configuredDailyMinutes");

        Set<String> completedIds = new HashSet<>();
        for (JsonNode id : progress.path("completedSessionIds")) if (id.isString()) completedIds.add(id.asText());
        Set<String> scheduledIds = new HashSet<>();
        int completedCount = 0;
        ObjectNode next = null;
        for (JsonNode day : snapshot.path("days")) {
            for (JsonNode assignment : day.path("assignments")) {
                String id = assignment.path("id").asText("");
                if (id.isBlank() || !scheduledIds.add(id)) continue;
                if (completedIds.contains(id)) completedCount++;
                else if (next == null) {
                    next = mapper.createObjectNode();
                    next.put("assignmentId", id);
                    next.put("sourceContentId", assignment.path("sourceContentId").asText(id));
                    for (String field : new String[]{"title", "kind", "minutes", "route"})
                        next.set(field, assignment.path(field).isMissingNode() ? mapper.nullNode() : assignment.path(field).deepCopy());
                    next.set("studyDay", day.path("day").deepCopy());
                }
            }
        }
        result.put("completedSessionCount", completedCount);
        result.put("totalSessionCount", scheduledIds.size());
        result.set("nextScheduledActivity", next == null ? mapper.nullNode() : next);
        return result;
    }
}
