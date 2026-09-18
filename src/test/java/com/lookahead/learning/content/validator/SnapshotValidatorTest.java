package com.lookahead.learning.content.validator;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SnapshotValidatorTest {
    private final JsonMapper mapper = new JsonMapper();
    private final String hash = "sha256:" + "a".repeat(64);
    private final SnapshotValidator validator = new SnapshotValidator(mapper, mapper.readTree("""
        {"schemaVersion":"account-catalog/v1","catalogVersion":"HASH",
         "algorithmVersions":["study-schedule/v2","interview-sprint/v1"],"rankingVersions":["rank/v1"],
         "records":[{"id":"canonical-1","aliases":["document-1"],"contentType":"dsa-problem","topicIds":["dsa"],"route":["/","learn","dsa","one"]}]}
        """.replace("HASH", hash)));

    private ObjectNode snapshot() {
        ObjectNode snapshot = (ObjectNode) mapper.readTree("""
            {"config":{"days":1,"dailyHours":1,"topicIds":["dsa"],"accessTopicIds":["dsa"]},
             "focusedDailyHours":1,"bufferHours":0,"includedTopics":[],"excludedTopics":[],
             "uniqueNewItems":1,"reviewAssignments":0,"schedulingVersion":"study-schedule/v2",
             "days":[{"day":1,"phase":"Start","focus":"Practice","newCount":1,"reviewCount":0,"focusedMinutes":45,
                      "assignments":[{"id":"canonical-1","kind":"new","activity":"Practice","topicId":"dsa",
                       "topicTitle":"DSA","title":"One","courseTitle":"DSA","contentType":"dsa-problem",
                       "route":["/","learn","dsa","one"],"minutes":45}]}],"weeks":[]}
            """);
        snapshot.withArray("weeks").addObject().put("number", 1).put("label", "Start").set("days", snapshot.path("days").deepCopy());
        return snapshot;
    }

    private ObjectNode pins(boolean legacy) {
        return (ObjectNode) mapper.readTree("""
            {"snapshotSchemaVersion":"study-plan/v1","algorithmVersion":"study-schedule/v2",
             "catalogVersion":null,"rankingVersion":null,"origin":"legacy-local-import"}
            """);
    }

    @Test void recordGrantAuthorizesOnlyItsCanonicalItemAndTrustedTopic() {
        assertDoesNotThrow(() -> validator.validate(snapshot(),pins(true),true,Set.of("content:canonical-1")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(snapshot(),pins(true),true,Set.of("content:another-item")));
        assertFalse(validator.isAccessible("unknown",Set.of("content:unknown")));
    }

    @Test void preservesUnknownHistoricalProvenanceAndResolvesCanonicalIds() {
        var validated = validator.validate(snapshot(), pins(true), true, Set.of("dsa"));
        assertEquals("canonical-1", validated.assignmentContentIds().get("canonical-1"));
        assertTrue(validated.provenance().path("catalogVersion").isNull());
        assertEquals("unknown", validated.provenance().path("historicalProvenance").asText());
        assertEquals(hash, validated.validatedAgainstCatalogVersion());
    }

    @Test void rejectsCallerEntitlementAndUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(snapshot(), pins(true), true, Set.of()));
        ObjectNode poisoned = snapshot(); poisoned.put("answer", "private content");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(poisoned, pins(true), true, Set.of("dsa")));
    }

    @Test void rejectsContradictoryWeeksAndUntrustedRoutes() {
        ObjectNode mismatched = snapshot();
        ((ObjectNode) mismatched.path("weeks").get(0).path("days").get(0)).put("focus", "changed");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(mismatched, pins(true), true, Set.of("dsa")));
        ObjectNode route = snapshot();
        ((ObjectNode) route.path("days").get(0).path("assignments").get(0)).putArray("route").add("https://attacker.invalid");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(route, pins(true), true, Set.of("dsa")));
    }

    @Test void requiresGeneratedVersionPins() {
        ObjectNode pins = pins(false); pins.put("origin", "generated");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(snapshot(), pins, false, Set.of("dsa")));
        pins.put("catalogVersion", hash); pins.put("rankingVersion", "rank/v1");
        assertDoesNotThrow(() -> validator.validate(snapshot(), pins, false, Set.of("dsa")));
    }

    @Test void distinguishesImportedSessionsFromExplicitCanonicalCompletion() {
        ObjectNode snapshot = snapshot();
        ObjectNode session = (ObjectNode) snapshot.path("days").get(0).path("assignments").get(0);
        session.put("id", "canonical-1:sprint:new").put("sourceContentId", "canonical-1").put("timebox", true);
        ((ObjectNode) snapshot.path("weeks").get(0)).set("days", snapshot.path("days").deepCopy());
        var validated = validator.validate(snapshot, pins(true), true, Set.of("dsa"));
        ObjectNode local = (ObjectNode) mapper.readTree("""
            {"schemaVersion":"study-plan-local/v1","revision":1,"goal":"Prepare","rankingVersion":null,
             "completedIds":["canonical-1:sprint:new","older-plan-content"],"shiftedDays":0,"history":[],
             "attemptedContentIds":["document-1","old-attempt"],"reviewNotes":{"document-1":"check invariant","old-content":"preserve old note"}}
            """);
        local.set("snapshot", snapshot);
        JsonNode progress = validator.mapImportedProgress(local, validated);
        assertEquals(0, progress.path("completedContentIds").size());
        assertEquals(1, progress.path("completedSessionIds").size());
        assertEquals(1, progress.path("attemptedContentIds").size());
        assertFalse(progress.path("legacySource").has("snapshot"));
        assertFalse(progress.has("attemptCount"));
        assertEquals("check invariant", progress.path("notes").path("canonical-1").asText());
        assertEquals(1, progress.path("notes").size());
        assertEquals("preserve old note", progress.path("legacySource").path("reviewNotes").path("old-content").asText());
    }

    @Test void resolvesTrustedAliasesWithoutTreatingUnknownIdsAsCatalogMembers() {
        assertEquals("canonical-1", validator.canonicalContentId("document-1"));
        assertEquals("unpublished", validator.canonicalContentId("unpublished"));
    }

    @Test void importCannotReplaceItsRecordedRankingPin() {
        var validated = validator.validate(snapshot(), pins(true), true, Set.of("dsa"));
        ObjectNode local = (ObjectNode) mapper.readTree("""
            {"schemaVersion":"study-plan-local/v1","revision":1,"goal":"Prepare","rankingVersion":"rank/v1",
             "completedIds":[],"shiftedDays":0,"history":[]}
            """);
        local.set("snapshot", snapshot());
        assertThrows(IllegalArgumentException.class, () -> validator.mapImportedProgress(local, validated));
    }

    @Test void existingVersionPreservesHistoricalPinsButStillChecksCurrentContentAndAccess() {
        ObjectNode oldPins = pins(false);
        oldPins.put("origin", "generated").put("catalogVersion", "sha256:" + "b".repeat(64))
                .put("rankingVersion", "retired-rank/v0").put("algorithmVersion", "retired-scheduler/v0")
                .put("validatedAgainstCatalogVersion", "sha256:" + "b".repeat(64));
        ObjectNode historical = snapshot(); historical.put("schedulingVersion", "retired-scheduler/v0");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(historical, oldPins, false, Set.of("dsa")));
        var validated = validator.validateExistingVersion(historical, oldPins, Set.of("dsa"));
        assertEquals(oldPins, validated.provenance());
        assertEquals(hash, validated.validatedAgainstCatalogVersion());
        assertThrows(IllegalArgumentException.class, () -> validator.validateExistingVersion(historical, oldPins, Set.of()));
        oldPins.put("algorithmVersion", "different-from-snapshot");
        assertThrows(IllegalArgumentException.class, () -> validator.validateExistingVersion(historical, oldPins, Set.of("dsa")));
    }

    @Test void currentAccessRequiresKnownPublishedMetadataAndServerGrants() {
        assertTrue(validator.isAccessible("canonical-1", Set.of("dsa")));
        assertTrue(validator.isAccessible("document-1", Set.of("dsa")));
        assertFalse(validator.isAccessible("canonical-1", Set.of("other-topic")));
        assertFalse(validator.isAccessible("retired-content", Set.of("dsa")));
    }

    @Test void importsExplicitRecoveryLedgerAndRejectsUntrustedOrContradictoryEntries() {
        ObjectNode snapshot = snapshot();
        JsonNode deferred = snapshot.path("days").get(0).path("assignments").get(0).deepCopy();
        ((ObjectNode) snapshot.path("days").get(0)).putArray("assignments");
        ObjectNode local = mapper.createObjectNode().put("shiftedDays", 2);
        local.set("snapshot", snapshot);
        ObjectNode recovery = local.putObject("recovery");
        recovery.put("strategy", "fixed-window").put("elapsedDays", 1).put("deadlineDays", 1);
        recovery.putArray("deferredContentIds").add("canonical-1");
        ObjectNode entry = recovery.putArray("deferredSessions").addObject();
        entry.set("assignment", deferred); entry.putNull("originalDay").put("reason", "window-ended");
        JsonNode validated = validator.validateImportedRecovery(local, Set.of("dsa"));
        assertEquals(1, validated.path("elapsedDays").asInt());
        assertEquals(2, validated.path("legacyShiftedDays").asInt());
        assertTrue(validated.path("deferredSessions").get(0).path("originalDay").isNull());
        assertThrows(IllegalArgumentException.class, () -> validator.validateImportedRecovery(local, Set.of()));
        recovery.put("deadlineDays", 2);
        assertThrows(IllegalArgumentException.class, () -> validator.validateImportedRecovery(local, Set.of("dsa")));
    }

    @Test void oldLocalRecoveryDefaultsPreserveOffsetWithoutInventingDates() {
        ObjectNode local = mapper.createObjectNode().put("shiftedDays", 3);
        local.set("snapshot", snapshot());
        JsonNode recovery = validator.validateImportedRecovery(local, Set.of("dsa"));
        assertEquals("none", recovery.path("strategy").asText());
        assertEquals(0, recovery.path("elapsedDays").asInt());
        assertEquals(1, recovery.path("deadlineDays").asInt());
        assertEquals(3, recovery.path("legacyShiftedDays").asInt());
    }

    private SnapshotValidator validatorWithRestrictedPrerequisite() {
        return new SnapshotValidator(mapper, mapper.readTree("""
            {"schemaVersion":"account-catalog/v1","catalogVersion":"HASH",
             "algorithmVersions":["study-schedule/v2"],"rankingVersions":["rank/v1"],
             "records":[
              {"id":"canonical-1","contentType":"dsa-problem","topicIds":["dsa"],"route":["/","learn","dsa","one"]},
              {"id":"foundation-1","contentType":"dsa-problem","topicIds":["foundation"],"route":["/","learn","foundation","one"]}]}
            """.replace("HASH", hash)));
    }

    private ObjectNode snapshotWithBlockedPrerequisite() {
        ObjectNode snapshot = snapshot();
        snapshot.putArray("blockedItems").addObject().put("id", "canonical-1").put("title", "One")
                .putArray("prerequisiteIds").add("foundation-1");
        return snapshot;
    }

    @Test void blockedPrerequisiteMetadataDoesNotRequireOrGrantAccess() {
        var restricted = validatorWithRestrictedPrerequisite();
        var validated = restricted.validate(snapshotWithBlockedPrerequisite(), pins(true), true, Set.of("dsa"));
        assertFalse(restricted.isAccessible("foundation-1", Set.of("dsa")));
        assertFalse(validated.canonicalContentIds().contains("foundation-1"));
        assertFalse(validated.assignmentContentIds().containsValue("foundation-1"));
    }

    @Test void blockedPrerequisiteMetadataStillRejectsUnknownContent() {
        ObjectNode snapshot = snapshotWithBlockedPrerequisite();
        ((ObjectNode) snapshot.path("blockedItems").get(0)).putArray("prerequisiteIds").add("unpublished");
        assertThrows(IllegalArgumentException.class, () -> validatorWithRestrictedPrerequisite()
                .validate(snapshot, pins(true), true, Set.of("dsa")));
    }

    @Test void blockedItemItselfStillRequiresAccess() {
        ObjectNode snapshot = snapshotWithBlockedPrerequisite();
        ((ObjectNode) snapshot.path("blockedItems").get(0)).put("id", "foundation-1");
        assertThrows(IllegalArgumentException.class, () -> validatorWithRestrictedPrerequisite()
                .validate(snapshot, pins(true), true, Set.of("dsa")));
    }

    @Test void metadataReferenceCannotAuthorizeScheduledAssignmentsOrFutureReviews() {
        var restricted = validatorWithRestrictedPrerequisite();
        for (boolean futureReview : new boolean[] {false, true}) {
            ObjectNode snapshot = snapshotWithBlockedPrerequisite();
            snapshot.withObject("config").withArray("topicIds").add("foundation");
            ObjectNode assignment = (ObjectNode) snapshot.path("days").get(0).path("assignments").get(0).deepCopy();
            assignment.put("id", "foundation-1").put("topicId", "foundation");
            assignment.putArray("route").add("/").add("learn").add("foundation").add("one");
            if (futureReview) {
                assignment.put("kind", "review");
                snapshot.putArray("futureReviews").add(assignment);
            } else {
                ((ObjectNode) snapshot.path("days").get(0)).putArray("assignments").add(assignment);
                ((ObjectNode) snapshot.path("weeks").get(0)).set("days", snapshot.path("days").deepCopy());
            }
            var error = assertThrows(IllegalArgumentException.class,
                    () -> restricted.validate(snapshot, pins(true), true, Set.of("dsa")));
            assertEquals("Content access denied", error.getMessage());
        }
    }

}
