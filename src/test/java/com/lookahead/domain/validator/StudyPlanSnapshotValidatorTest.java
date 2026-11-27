package com.lookahead.domain.validator;

import static org.junit.jupiter.api.Assertions.*;
import com.lookahead.domain.service.content.AccountCatalogService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StudyPlanSnapshotValidatorTest {
    private final JsonMapper mapper = new JsonMapper();
    private final String hash = "sha256:" + "a".repeat(64);
    private final AccountCatalogService catalog = new AccountCatalogService(mapper, mapper.readTree("""
        {"schemaVersion":"account-catalog/v1","catalogVersion":"HASH",
         "algorithmVersions":["study-schedule/v2","interview-sprint/v1"],"rankingVersions":["rank/v1"],
         "records":[{"id":"canonical-1","aliases":["document-1"],"contentType":"dsa-problem","topicIds":["dsa"],"route":["/","learn","dsa","one"]}]}
        """.replace("HASH", hash)));
    private final StudyPlanSnapshotValidator validator = new StudyPlanSnapshotValidator(catalog.catalog());

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
        assertFalse(catalog.isAccessible("unknown",Set.of("content:unknown")));
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

    private StudyPlanSnapshotValidator validatorWithRestrictedPrerequisite() { return new StudyPlanSnapshotValidator(restrictedCatalog().catalog()); }
    private AccountCatalogService restrictedCatalog() {
        return new AccountCatalogService(mapper, mapper.readTree("""
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
        assertFalse(restrictedCatalog().isAccessible("foundation-1", Set.of("dsa")));
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
