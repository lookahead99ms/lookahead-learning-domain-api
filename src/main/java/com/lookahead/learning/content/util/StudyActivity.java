package com.lookahead.learning.content.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static com.lookahead.learning.content.util.PayloadReaders.*;

/** Activity dates and estimates are separate from immutable plan schedules. */
public final class StudyActivity {
    private StudyActivity() {}
    public static final String DAILY_MARKER = ":daily-recall:";
    public static List<JsonNode> assignments(JsonNode snapshot) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode day : snapshot.path("days")) for (JsonNode item : day.path("assignments")) result.add(item);
        for (JsonNode item : snapshot.path("futureReviews")) result.add(item);
        return result;
    }
    public static JsonNode resolve(JsonNode snapshot, String id, int day) {
        List<JsonNode> all = assignments(snapshot);
        for (JsonNode item : all) if (id.equals(item.path("id").asText())) return item;
        String suffix = DAILY_MARKER + day;
        if (day < 1 || day > snapshot.path("config").path("days").asInt() || !id.endsWith(suffix)) return null;
        String originalId = id.substring(0,id.length()-suffix.length());
        for (JsonNode original : all) if (originalId.equals(original.path("id").asText()) && "new".equals(original.path("kind").asText())) {
            ObjectNode recall = (ObjectNode) original.deepCopy();
            recall.put("id",id).put("kind","review").put("activity","Recall").put("timebox",false)
                .put("minutes",10).put("sourceContentId",source(original)).put("requiredSessionId",originalId).put("reviewDueDay",day);
            return recall;
        }
        return null;
    }
    public static String source(JsonNode item) { return item.has("sourceContentId") ? item.path("sourceContentId").asText() : item.path("id").asText(); }
    public static int dynamicDay(String id) {
        int marker = id.lastIndexOf(DAILY_MARKER);
        if (marker < 0) return 0;
        try { return Integer.parseInt(id.substring(marker+DAILY_MARKER.length())); } catch (NumberFormatException invalid) { return 0; }
    }
    public static boolean dailyEligible(JsonNode snapshot, JsonNode progress, JsonNode recall, String canonical, int day) {
        JsonNode original = resolve(snapshot,recall.path("requiredSessionId").asText(),day);
        if (original == null) return false;
        boolean complete = contains(progress.path("completedContentIds"),canonical) ||
            (original.path("timebox").asBoolean(false) && progress.path("sessionOutcomes").has(original.path("id").asText()));
        int originalDay = 0;
        for (JsonNode entry : progress.path("studyLog")) if (original.path("id").asText().equals(entry.path("assignmentId").asText())) originalDay = Math.max(originalDay,entry.path("day").asInt());
        if (originalDay == 0) for (JsonNode scheduled : snapshot.path("days")) for (JsonNode item : scheduled.path("assignments"))
            if (original.path("id").asText().equals(item.path("id").asText())) originalDay = scheduled.path("day").asInt();
        return complete && originalDay > 0 && originalDay < day;
    }
    private static boolean contains(JsonNode values, String value) { for (JsonNode item : values) if (value.equals(item.asText())) return true; return false; }
    public static void record(ObjectNode progress, JsonNode snapshot, String assignmentId, int day, ObjectMapper mapper) {
        if (day == 0) return; // Old clients have no date evidence; do not manufacture it.
        JsonNode assignment = resolve(snapshot,assignmentId,day);
        require(assignment != null,"Unknown study activity");
        for (JsonNode entry : progress.path("studyLog")) if (assignmentId.equals(entry.path("assignmentId").asText()) && day==entry.path("day").asInt()) return;
        require(progress.path("studyLog").size()<20000,"Study activity history limit reached");
        if (!progress.has("studyLog")) progress.set("studyLog",mapper.createArrayNode());
        progress.withArray("studyLog").addObject().put("assignmentId",assignmentId).put("day",day)
            .put("minutes",assignment.path("minutes").asDouble()).put("recordedAt",Instant.now().toString());
    }
}
