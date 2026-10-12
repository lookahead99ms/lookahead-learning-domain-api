package com.lookahead.learning.content.service;

import com.lookahead.learning.content.model.PlanRecord;
import com.lookahead.learning.content.model.PlanVersion;
import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.util.CatalogIds;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-931: a plan sent by an old browser with look-ahead IDs is saved and served with blaze IDs only. */
class LegacyCatalogPlanTest {
    private static final UUID OWNER = UUID.fromString("0b5f1f7e-5d0e-4c55-9b55-7f6f1a7f3c11");
    private static final String CATALOG_VERSION = "sha256:" + "b".repeat(64);
    private final JsonMapper mapper = new JsonMapper();
    private final PlanJson json = new PlanJson(mapper);
    private PlanRepository repository;
    private PlanService service;
    private final AtomicReference<PlanRecord> plan = new AtomicReference<>();
    private final AtomicReference<PlanVersion> version = new AtomicReference<>();

    @BeforeEach void setup() {
        // The trusted catalog itself is still the pre-rename publication; it is read as blaze too.
        var catalog = new SnapshotValidator(mapper, mapper.readTree("""
            {"schemaVersion":"account-catalog/v1","catalogVersion":"VERSION","algorithmVersions":["study-schedule/v2"],"rankingVersions":[],
             "records":[{"id":"look-ahead:system-design:caching","aliases":[],"contentType":"theory",
               "topicIds":["look-ahead:system-design"],"route":["/","look-ahead","system-design","caching"]}]}
            """.replace("VERSION", CATALOG_VERSION)));
        assertThat(catalog.allTopicIds()).containsExactly("blaze:system-design");
        repository = mock(PlanRepository.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(repository.findReceipt(any(), any())).thenReturn(Optional.empty());
        when(repository.grants(OWNER)).thenReturn(Set.of("blaze:system-design"));
        when(repository.allocatePlanNumber(OWNER)).thenReturn(1L);
        doAnswer(call -> { plan.set(new PlanRecord(call.getArgument(1), 1, call.getArgument(2), call.getArgument(3),
                call.getArgument(4), "2026-10-12T00:00:00Z", "2026-10-12T00:00:00Z", call.getArgument(5), 1)); return null; })
            .when(repository).createPlan(any(), any(), any(), any(), any(), any(), anyLong());
        doAnswer(call -> { version.set(new PlanVersion(call.getArgument(2), call.getArgument(4), call.getArgument(6),
                call.getArgument(7), call.getArgument(9))); return null; })
            .when(repository).insertVersion(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        doAnswer(call -> { var p = plan.get(); plan.set(new PlanRecord(p.id(), call.getArgument(3), p.version(), p.goal(),
                call.getArgument(2), p.createdAt(), p.updatedAt(), p.name(), p.planNumber())); return null; })
            .when(repository).updateProgress(any(), any(), any(), anyLong());
        when(repository.findPlan(eq(OWNER), any(), anyBoolean())).thenAnswer(call -> Optional.ofNullable(plan.get()));
        when(repository.findVersion(eq(OWNER), any(), any())).thenAnswer(call -> Optional.ofNullable(version.get()));
        service = new PlanService(repository, mapper, catalog, json);
    }

    private ObjectNode legacyCreate() {
        ObjectNode body = (ObjectNode) mapper.readTree("""
            {"goal":"Prepare look-ahead interviews","name":"My look-ahead plan",
             "snapshot":{"config":{"days":1,"dailyHours":1,"topicIds":["look-ahead:system-design"],"accessTopicIds":["look-ahead:system-design"]},
               "focusedDailyHours":1,"bufferHours":0,"excludedTopics":[],"uniqueNewItems":1,"reviewAssignments":0,
               "schedulingVersion":"study-schedule/v2",
               "includedTopics":[{"id":"look-ahead:system-design","path":"look-ahead","title":"System Design","description":"","courseIds":["system-design"]}],
               "days":[{"day":1,"phase":"Start","focus":"System Design","newCount":1,"reviewCount":0,"focusedMinutes":30,
                 "assignments":[{"id":"look-ahead:system-design:caching","kind":"new","activity":"Understand","topicId":"look-ahead:system-design",
                   "topicTitle":"System Design","title":"Caching","courseTitle":"System Design","contentType":"theory",
                   "route":["/","look-ahead","system-design","caching"],"minutes":30}]}],"weeks":[]},
             "provenance":{"snapshotSchemaVersion":"study-plan/v1","algorithmVersion":"study-schedule/v2","catalogVersion":"VERSION","rankingVersion":null,"origin":"generated"}}
            """.replace("VERSION", CATALOG_VERSION));
        ObjectNode snapshot = (ObjectNode) body.path("snapshot");
        snapshot.withArray("weeks").addObject().put("number", 1).put("label", "Start").set("days", snapshot.path("days").deepCopy());
        return body;
    }

    @Test void legacyCreateIsStoredAsBlazeWhileTheRetryReceiptMatchesTheRequestAsSent() {
        UUID key = UUID.randomUUID();
        ObjectNode raw = legacyCreate();
        var created = service.create(OWNER, key, raw, false);
        assertThat(created.status()).isEqualTo(201);

        var snapshot = ArgumentCaptor.forClass(JsonNode.class);
        var digest = ArgumentCaptor.forClass(String.class);
        var membership = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).insertVersion(eq(OWNER), any(), any(), isNull(), snapshot.capture(), digest.capture(), any(), any(), eq("create"), membership.capture());
        assertThat(CatalogIds.containsLegacy(snapshot.getValue())).isFalse();
        assertThat(snapshot.getValue().path("includedTopics").get(0).path("path").asText()).isEqualTo("blaze");
        assertThat(snapshot.getValue().path("days").get(0).path("assignments").get(0).path("route").get(1).asText()).isEqualTo("blaze");
        assertThat(digest.getValue()).isEqualTo(json.digest(json.canonical(snapshot.getValue())));
        assertThat(membership.getValue().path("blaze:system-design:caching").asText()).isEqualTo("blaze:system-design:caching");
        assertThat(created.data().path("restrictedContentIds")).isEmpty();
        // Prose is the learner's own text and is kept as written.
        assertThat(plan.get().goal()).isEqualTo("Prepare look-ahead interviews");
        assertThat(plan.get().name()).isEqualTo("My look-ahead plan");

        var hash = ArgumentCaptor.forClass(String.class);
        verify(repository).insertReceipt(eq(OWNER), eq(key), hash.capture(), any(), any(), eq(201));
        assertThat(hash.getValue()).isEqualTo(json.digest("create:" + json.canonical(raw)));
    }

    @Test void legacySessionIdsFromAnOldTabUpdateTheBlazePlan() {
        var created = service.create(OWNER, UUID.randomUUID(), legacyCreate(), false).data();
        ObjectNode activity = (ObjectNode) mapper.readTree("""
            {"expectedRevision":1,"versionId":"VERSION","studyDay":1,
             "operations":[{"type":"setSessionCompletion","assignmentId":"look-ahead:system-design:caching","completed":true},
                           {"type":"recordAttempt","assignmentId":"look-ahead:system-design:caching","canonicalContentId":"look-ahead:system-design:caching","outcome":"attempted"}]}
            """.replace("VERSION", created.path("versionId").asText()));
        when(repository.containsHistoricalContent(any(), any(), any())).thenReturn(true);
        var result = service.activity(OWNER, UUID.fromString(created.path("planId").asText()), UUID.randomUUID(), activity).data();
        JsonNode progress = result.path("progress");
        assertThat(progress.path("completedSessionIds").get(0).asText()).isEqualTo("blaze:system-design:caching");
        assertThat(progress.path("attemptedContentIds").get(0).asText()).isEqualTo("blaze:system-design:caching");
        assertThat(progress.path("sessionOutcomes").has("blaze:system-design:caching")).isTrue();
        assertThat(CatalogIds.containsLegacy(progress)).isFalse();
    }
}
