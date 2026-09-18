package com.lookahead.learning.content.validator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class ReadyMadeTemplateMapperTest {
    private final JsonMapper json = new JsonMapper();
    private final ReadyMadeTemplateMapper mapper = new ReadyMadeTemplateMapper(json);
    private final String rawHash = "d".repeat(64);

    private ObjectNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/study-plans/synthetic-adoption-template.json")) {
            assertNotNull(stream);
            return (ObjectNode) json.readTree(stream.readAllBytes());
        }
    }

    @Test void preservesAuthoredObjectAndAllImmutablePins() throws Exception {
        var source = fixture();
        var result = mapper.map(source, rawHash);
        assertEquals(source, result.snapshot().path("template"));
        var pins = result.provenance();
        assertEquals("ready-made-template", pins.path("origin").asText());
        for (String field : new String[]{"algorithmVersion", "catalogVersion", "rankingVersion"})
            assertEquals(source.path("provenance").path(field), pins.path(field));
        for (String field : new String[]{"templateId", "templateVersion", "pathId"})
            assertEquals(source.path(field), pins.path("template").path(field));
        for (String field : new String[]{"blueprintVersion", "sourceContentVersion"})
            assertEquals(source.path("provenance").path(field), pins.path("template").path(field));
        assertEquals(rawHash, pins.path("template").path("templateSha256").asText());
        assertEquals("ready-made-to-study-plan/v1", pins.path("template").path("adapterVersion").asText());
    }

    @Test void preservesFullDependenciesAndOriginalPracticeKind() throws Exception {
        var source = fixture();
        var snapshot = mapper.map(source, rawHash).snapshot();
        var original = source.path("days").get(0).path("sessions").get(2);
        var assignment = snapshot.path("days").get(0).path("assignments").get(2);
        assertEquals(2, assignment.path("requiredSessionIds").size());
        assertEquals(original.path("requiredSessionIds"), assignment.path("requiredSessionIds"));
        assertEquals(original.path("prerequisiteIds"), assignment.path("prerequisiteIds"));
        assertEquals("practice", assignment.path("templateKind").asText());
        assertEquals("new", assignment.path("kind").asText());
        var repeated = snapshot.path("days").get(4).path("assignments").get(0);
        assertEquals("practice", repeated.path("templateKind").asText());
        assertEquals("review", repeated.path("kind").asText());
        assertEquals("Rehearse", repeated.path("activity").asText());
    }

    @Test void preservesReviewBasisAndFutureWorkOutsideCompletionMembership() throws Exception {
        var source = fixture();
        var result = mapper.map(source, rawHash);
        var future = result.snapshot().path("futureReviews").get(0);
        var review = source.path("futureReviews").get(0).path("review");
        assertEquals(1, result.snapshot().path("futureReviews").size());
        assertEquals(review.path("basis"), future.path("reviewBasis"));
        assertEquals(review.path("sourceSessionId"), future.path("reviewSourceSessionId"));
        assertEquals(review.path("sourceDay"), future.path("reviewFromDay"));
        assertEquals(review.path("dueDay"), future.path("reviewDueDay"));
        assertFalse(result.assignmentContentIds().containsKey(future.path("id").asText()));
        assertEquals(5, result.assignmentContentIds().size());
    }

    @Test void recoveryAndUncoveredScopeRemainExactWithoutInventedProgress() throws Exception {
        var source = fixture();
        var result = mapper.map(source, rawHash);
        var snapshot = result.snapshot();
        assertEquals(source.path("coverage"), snapshot.path("template").path("coverage"));
        assertEquals(3, snapshot.path("days").get(0).path("assignments").size());
        assertEquals(25, snapshot.path("days").get(0).path("bufferMinutes").asInt());
        assertEquals(60, snapshot.path("days").get(6).path("bufferMinutes").asInt());
        assertFalse(result.assignmentContentIds().containsKey(source.path("days").get(0).path("sessions").get(3).path("id").asText()));
        assertFalse(snapshot.has("progress"));
        assertFalse(snapshot.path("config").has("completedContentIds"));
    }

    @Test void dailySelectionCapIsNotReducedToAHorizonAverage() throws Exception {
        var snapshot = mapper.map(fixture(), rawHash).snapshot();
        assertEquals(1, snapshot.path("focusedDailyHours").asInt());
        assertEquals(0, snapshot.path("bufferHours").asInt());
        assertEquals(35, snapshot.path("days").get(0).path("focusedMinutes").asInt());
        assertTrue(snapshot.path("focusedDailyHours").asDouble() * 60 >=
                snapshot.path("days").get(0).path("focusedMinutes").asDouble());
    }

    @Test void mappingIsIsolatedFromCallerAndReturnedJsonMutations() throws Exception {
        var source = fixture();
        var baseline = source.deepCopy();
        var result = mapper.map(source, rawHash);
        source.put("intendedUse", "changed caller object");
        ((ObjectNode) result.snapshot().path("template")).put("intendedUse", "changed returned object");
        ((ObjectNode) result.provenance()).put("catalogVersion", "changed pin");
        assertEquals(baseline, result.snapshot().path("template"));
        assertEquals(baseline.path("provenance").path("catalogVersion"), result.provenance().path("catalogVersion"));
        assertThrows(UnsupportedOperationException.class, () -> result.assignmentContentIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.topicIds().clear());
    }

    @Test void rejectsChangedCanonicalTemplateAndInvalidRawPin() throws Exception {
        var source = fixture();
        source.put("intendedUse", "changed after versioning");
        assertThrows(IllegalArgumentException.class, () -> mapper.map(source, rawHash));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(fixture(), "sha256:" + rawHash));
    }

    @Test void rejectsDuplicateOrForwardDependentSessionsEvenWithValidDigest() throws Exception {
        rejects(source -> session(source, 0, 1).put("id", session(source, 0, 0).path("id").asText()));
        rejects(source -> session(source, 0, 0).putArray("requiredSessionIds").add(session(source, 0, 1).path("id").asText()));
    }

    @Test void rejectsRecoveryAsCurriculumAndInconsistentBudgets() throws Exception {
        rejects(source -> session(source, 0, 3).put("contentId", source.path("references").get(0).path("contentId").asText()));
        rejects(source -> ((ObjectNode) source.path("days").get(0)).put("unallocatedMinutes", 16));
        rejects(source -> ((ObjectNode) source.path("coverage")).put("selectedContentCount", 2));
    }

    @Test void rejectsBrokenReviewSourceOrFutureHorizon() throws Exception {
        rejects(source -> ((ObjectNode) source.path("futureReviews").get(0).path("review")).put("dueDay", 20));
        rejects(source -> ((ObjectNode) source.path("futureReviews").get(0).path("review")).put("sourceDay", 2));
        rejects(source -> ((ObjectNode) source.path("futureReviews").get(0)).putArray("requiredSessionIds"));
    }

    @Test void rejectsUnknownFieldsAndUnresolvedReferences() throws Exception {
        rejects(source -> source.put("claimAccess", true));
        rejects(source -> session(source, 0, 0).put("contentId", "unknown"));
        rejects(source -> ((ObjectNode) source.path("references").get(0)).putArray("route").add("https://example.invalid"));
    }

    private ObjectNode session(ObjectNode source, int day, int index) {
        return (ObjectNode) source.path("days").get(day).path("sessions").get(index);
    }

    private void rejects(Consumer<ObjectNode> mutation) throws Exception {
        var source = fixture();
        mutation.accept(source);
        source.remove("templateVersion");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((json.writeValueAsString(sorted(source)) + "\n").getBytes(StandardCharsets.UTF_8)));
        source.put("templateVersion", "sha256:" + digest);
        assertThrows(IllegalArgumentException.class, () -> mapper.map(source, rawHash));
    }

    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> sorted.put(entry.getKey(), sorted(entry.getValue())));
            return json.valueToTree(sorted);
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(child -> array.add(sorted(child)));
            return array;
        }
        return value;
    }
}
