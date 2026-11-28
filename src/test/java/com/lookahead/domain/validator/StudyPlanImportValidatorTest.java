package com.lookahead.domain.validator;

import static org.junit.jupiter.api.Assertions.*;

import com.lookahead.domain.service.content.AccountCatalogService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** DLV-1016: the import tests that were in StudyPlanSnapshotValidatorTest, moved with the code. */
class StudyPlanImportValidatorTest {
  private final JsonMapper mapper = new JsonMapper();
  private final String hash = "sha256:" + "a".repeat(64);
  private final AccountCatalogService catalog =
      new AccountCatalogService(
          mapper,
          mapper.readTree(
              """
              {"schemaVersion":"account-catalog/v1","catalogVersion":"HASH",
               "algorithmVersions":["study-schedule/v2","interview-sprint/v1"],\
              "rankingVersions":["rank/v1"],
               "records":[{"id":"canonical-1","aliases":["document-1"],\
              "contentType":"dsa-problem","topicIds":["dsa"],"route":["/","learn","dsa",\
              "one"]}]}
              """
                  .replace("HASH", hash)));
  private final StudyPlanSnapshotValidator validator =
      new StudyPlanSnapshotValidator(catalog.catalog());

  private ObjectNode snapshot() {
    ObjectNode snapshot =
        (ObjectNode)
            mapper.readTree(
                """
                {"config":{"days":1,"dailyHours":1,"topicIds":["dsa"],"accessTopicIds":["dsa"]},
                 "focusedDailyHours":1,"bufferHours":0,"includedTopics":[],"excludedTopics":[],
                 "uniqueNewItems":1,"reviewAssignments":0,"schedulingVersion":"study-schedule/v2",
                 "days":[{"day":1,"phase":"Start","focus":"Practice","newCount":1,\
                "reviewCount":0,"focusedMinutes":45,
                          "assignments":[{"id":"canonical-1","kind":"new","activity":"Practice",\
                "topicId":"dsa",
                           "topicTitle":"DSA","title":"One","courseTitle":"DSA",\
                "contentType":"dsa-problem",
                           "route":["/","learn","dsa","one"],"minutes":45}]}],"weeks":[]}
                """);
    snapshot
        .withArray("weeks")
        .addObject()
        .put("number", 1)
        .put("label", "Start")
        .set("days", snapshot.path("days").deepCopy());
    return snapshot;
  }

  private ObjectNode pins(boolean legacy) {
    return (ObjectNode)
        mapper.readTree(
            """
            {"snapshotSchemaVersion":"study-plan/v1","algorithmVersion":"study-schedule/v2",
             "catalogVersion":null,"rankingVersion":null,"origin":"legacy-local-import"}
            """);
  }

  private final StudyPlanImportValidator imports =
      new StudyPlanImportValidator(mapper, validator, catalog.catalog());

  @Test
  void distinguishesImportedSessionsFromExplicitCanonicalCompletion() {
    ObjectNode snapshot = snapshot();
    ObjectNode session = (ObjectNode) snapshot.path("days").get(0).path("assignments").get(0);
    session
        .put("id", "canonical-1:sprint:new")
        .put("sourceContentId", "canonical-1")
        .put("timebox", true);
    ((ObjectNode) snapshot.path("weeks").get(0)).set("days", snapshot.path("days").deepCopy());
    var validated = validator.validate(snapshot, pins(true), true, Set.of("dsa"));
    ObjectNode local =
        (ObjectNode)
            mapper.readTree(
                """
                {"schemaVersion":"study-plan-local/v1","revision":1,"goal":"Prepare",\
                "rankingVersion":null,
                 "completedIds":["canonical-1:sprint:new","older-plan-content"],"shiftedDays":0,\
                "history":[],
                 "attemptedContentIds":["document-1","old-attempt"],\
                "reviewNotes":{"document-1":"check invariant",\
                "old-content":"preserve old note"}}
                """);
    local.set("snapshot", snapshot);
    JsonNode progress = imports.mapImportedProgress(local, validated);
    assertEquals(0, progress.path("completedContentIds").size());
    assertEquals(1, progress.path("completedSessionIds").size());
    assertEquals(1, progress.path("attemptedContentIds").size());
    assertFalse(progress.path("legacySource").has("snapshot"));
    assertFalse(progress.has("attemptCount"));
    assertEquals("check invariant", progress.path("notes").path("canonical-1").asText());
    assertEquals(1, progress.path("notes").size());
    assertEquals(
        "preserve old note",
        progress.path("legacySource").path("reviewNotes").path("old-content").asText());
  }

  @Test
  void importCannotReplaceItsRecordedRankingPin() {
    var validated = validator.validate(snapshot(), pins(true), true, Set.of("dsa"));
    ObjectNode local =
        (ObjectNode)
            mapper.readTree(
                """
                {"schemaVersion":"study-plan-local/v1","revision":1,"goal":"Prepare",\
                "rankingVersion":"rank/v1",
                 "completedIds":[],"shiftedDays":0,"history":[]}
                """);
    local.set("snapshot", snapshot());
    assertThrows(
        IllegalArgumentException.class, () -> imports.mapImportedProgress(local, validated));
  }

  @Test
  void importsExplicitRecoveryLedgerAndRejectsUntrustedOrContradictoryEntries() {
    ObjectNode snapshot = snapshot();
    JsonNode deferred = snapshot.path("days").get(0).path("assignments").get(0).deepCopy();
    ((ObjectNode) snapshot.path("days").get(0)).putArray("assignments");
    ObjectNode local = mapper.createObjectNode().put("shiftedDays", 2);
    local.set("snapshot", snapshot);
    ObjectNode recovery = local.putObject("recovery");
    recovery.put("strategy", "fixed-window").put("elapsedDays", 1).put("deadlineDays", 1);
    recovery.putArray("deferredContentIds").add("canonical-1");
    ObjectNode entry = recovery.putArray("deferredSessions").addObject();
    entry.set("assignment", deferred);
    entry.putNull("originalDay").put("reason", "window-ended");
    JsonNode validated = imports.validateImportedRecovery(local, Set.of("dsa"));
    assertEquals(1, validated.path("elapsedDays").asInt());
    assertEquals(2, validated.path("legacyShiftedDays").asInt());
    assertTrue(validated.path("deferredSessions").get(0).path("originalDay").isNull());
    assertThrows(
        IllegalArgumentException.class, () -> imports.validateImportedRecovery(local, Set.of()));
    recovery.put("deadlineDays", 2);
    assertThrows(
        IllegalArgumentException.class,
        () -> imports.validateImportedRecovery(local, Set.of("dsa")));
  }

  @Test
  void oldLocalRecoveryDefaultsPreserveOffsetWithoutInventingDates() {
    ObjectNode local = mapper.createObjectNode().put("shiftedDays", 3);
    local.set("snapshot", snapshot());
    JsonNode recovery = imports.validateImportedRecovery(local, Set.of("dsa"));
    assertEquals("none", recovery.path("strategy").asText());
    assertEquals(0, recovery.path("elapsedDays").asInt());
    assertEquals(1, recovery.path("deadlineDays").asInt());
    assertEquals(3, recovery.path("legacyShiftedDays").asInt());
  }
}
