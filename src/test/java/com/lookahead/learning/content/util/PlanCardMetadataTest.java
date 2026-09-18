package com.lookahead.learning.content.util;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCardMetadataTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completionCountsOnlyDistinctSessionsInTheCurrentSchedule() {
        JsonNode snapshot = snapshot();
        JsonNode progress = json("""
                {"completedSessionIds":["session-a","session-a","removed-session","session-b:daily-recall:3","future-review"],
                 "completedContentIds":["source-b","source-c"],
                 "sessionOutcomes":{"session-b":"completed"},
                 "attemptedContentIds":["source-c"],
                 "studyLog":[{"assignmentId":"session-c","day":3}]}
                """);
        JsonNode card = PlanCardMetadata.from(snapshot, progress, mapper);

        assertThat(card.path("completedSessionCount").asInt()).isEqualTo(1);
        assertThat(card.path("totalSessionCount").asInt()).isEqualTo(3);
        assertThat(card.path("nextScheduledActivity").path("assignmentId").asText()).isEqualTo("session-b");
    }

    @Test
    void nextActivityFollowsSavedOrderWithoutReadinessSelectionOrCanonicalInference() {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("days").get(0).path("assignments").get(0))
                .put("kind", "review").put("requiredSessionId", "unfinished-prerequisite");
        JsonNode card = PlanCardMetadata.from(snapshot, json("{\"completedSessionIds\":[]}"), mapper);
        JsonNode next = card.path("nextScheduledActivity");

        assertThat(next.path("assignmentId").asText()).isEqualTo("session-a");
        assertThat(next.path("sourceContentId").asText()).isEqualTo("legacy-source-alias");
        assertThat(next.has("canonicalContentId")).isFalse();
        assertThat(next.path("studyDay").asInt()).isEqualTo(1);
        assertThat(next.path("kind").asText()).isEqualTo("review");
        assertThat(next.path("minutes").asInt()).isEqualTo(20);
        assertThat(next.path("title").asText()).isEqualTo("First saved activity");
        assertThat(next.path("route")).isEqualTo(snapshot.path("days").get(0).path("assignments").get(0).path("route"));
        assertThat(card.path("lifecycleState").isNull()).isTrue();
        assertThat(card.path("reservation").isNull()).isTrue();
    }

    @Test
    void configuredScopeAndTimeAreFactsFromSnapshot() {
        JsonNode card = PlanCardMetadata.from(snapshot(), json("{\"completedSessionIds\":[]}"), mapper);
        assertThat(card.path("schemaVersion").asText()).isEqualTo("plan-card/v1");
        assertThat(card.path("metadataStatus").asText()).isEqualTo("available");
        assertThat(card.path("selectedTopicIds")).isEqualTo(json("[\"learn:core-java\",\"learn:hands-on-dsa\"]"));
        assertThat(card.path("durationDays").asInt()).isEqualTo(3);
        assertThat(card.path("configuredDailyMinutes").decimalValue()).isEqualByComparingTo("75");
    }

    @Test
    void allScheduledSessionsCompleteLeavesNoNextActivity() {
        JsonNode card = PlanCardMetadata.from(snapshot(),
                json("{\"completedSessionIds\":[\"session-a\",\"session-b\",\"session-c\"]}"), mapper);
        assertThat(card.path("completedSessionCount").asInt()).isEqualTo(3);
        assertThat(card.path("totalSessionCount").asInt()).isEqualTo(3);
        assertThat(card.path("nextScheduledActivity").isNull()).isTrue();
    }

    @Test
    void emptyScheduleHasZeroCountsAndNoInferredDateOrReservation() {
        JsonNode card = PlanCardMetadata.from(json("{\"config\":{\"days\":7,\"topicIds\":[]},\"days\":[]}"),
                json("{\"completedSessionIds\":[\"stale-session\"]}"), mapper);
        assertThat(card.path("metadataStatus").asText()).isEqualTo("available");
        assertThat(card.path("completedSessionCount").asInt()).isZero();
        assertThat(card.path("totalSessionCount").asInt()).isZero();
        assertThat(card.path("nextScheduledActivity").isNull()).isTrue();
        assertThat(card.path("configuredDailyMinutes").isNull()).isTrue();
        assertThat(card.path("lifecycleState").isNull()).isTrue();
        assertThat(card.path("reservation").isNull()).isTrue();
    }

    @Test
    void unknownLegacyMetadataRemainsUnavailableRatherThanInventingProgress() {
        for (JsonNode[] source : new JsonNode[][] {
                {mapper.nullNode(), mapper.nullNode()},
                {json("{}"), json("{}")},
                {snapshot(), json("{\"completedContentIds\":[\"source-a\"]}")},
                {json("{\"config\":{},\"days\":null}"), json("{\"completedSessionIds\":[]}")}
        }) {
            JsonNode card = PlanCardMetadata.from(source[0], source[1], mapper);
            assertThat(card.path("metadataStatus").asText()).isEqualTo("unavailable");
            for (String name : new String[] {"selectedTopicIds", "durationDays", "configuredDailyMinutes",
                    "completedSessionCount", "totalSessionCount", "nextScheduledActivity", "lifecycleState", "reservation"}) {
                assertThat(card.has(name)).as(name + " must be explicitly unknown").isTrue();
                assertThat(card.path(name).isNull()).as(name).isTrue();
            }
        }
    }

    @Test
    void metadataAndReturnedRouteDoNotMutateSourceSnapshotOrProgress() {
        ObjectNode snapshot = snapshot();
        JsonNode progress = json("{\"completedSessionIds\":[],\"notes\":{\"source-a\":\"Keep this note\"}}");
        JsonNode originalSnapshot = snapshot.deepCopy(), originalProgress = progress.deepCopy();
        ObjectNode card = PlanCardMetadata.from(snapshot, progress, mapper);
        ((ObjectNode) card.path("nextScheduledActivity")).withArray("route").add("changed-return-value");
        card.withArray("selectedTopicIds").add("changed-return-topic");
        assertThat(snapshot).isEqualTo(originalSnapshot);
        assertThat(progress).isEqualTo(originalProgress);
    }

    @Test
    void legacyAssignmentWithoutSourceIdRetainsItsPublishedIdentifier() {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("days").get(0).path("assignments").get(0)).remove("sourceContentId");
        JsonNode next = PlanCardMetadata.from(snapshot, json("{\"completedSessionIds\":[]}"), mapper)
                .path("nextScheduledActivity");
        assertThat(next.path("sourceContentId").asText()).isEqualTo("session-a");
        assertThat(next.has("canonicalContentId")).isFalse();
    }

    private ObjectNode snapshot() {
        return (ObjectNode) json("""
                {"config":{"days":3,"dailyHours":1.25,"topicIds":["learn:hands-on-dsa","learn:core-java","learn:core-java"]},
                 "days":[
                   {"day":1,"assignments":[
                     {"id":"session-a","sourceContentId":"legacy-source-alias","title":"First saved activity","kind":"new","minutes":20,"route":["/","learn","fixture-a"]},
                     {"id":"session-b","sourceContentId":"source-b","title":"Second activity","kind":"review","minutes":10,"route":["/","learn","fixture-b"]}]},
                   {"day":2,"assignments":[]},
                   {"day":3,"assignments":[{"id":"session-c","sourceContentId":"source-c","title":"Third activity","kind":"new","minutes":25,"route":["/","learn","fixture-c"]}]}],
                 "futureReviews":[{"id":"future-review","sourceContentId":"source-c"}]}
                """);
    }

    private JsonNode json(String value) { return mapper.readTree(value); }
}
