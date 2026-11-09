package com.lookahead.domain.delivery;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.publication.LiveRelease;
import com.lookahead.domain.publication.PublicationLocation;
import com.lookahead.domain.review.platform.PlatformReviewFixtures;
import com.lookahead.domain.review.platform.ReviewStatusHistory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * DLV-998 (owner, 2026-11-09): the published plan seeds the first plan, later arrives through the inbox merge at each
 * start, is imported once per file, never replaces an owner's upload, and runs on DEV and local only.
 */
class ReleasePlanImportTest {
    @TempDir Path root;
    final JsonMapper mapper = JsonMapper.builder().build();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-09T20:00:00Z"), ZoneOffset.UTC);
    final LiveRelease live = new LiveRelease("release/live/", "v2026.11.09.20.00", null);
    Path content;
    DeliveryPlanStore store;
    DeliveryPlanService plans;

    @BeforeEach void setup() throws Exception {
        content = Files.createDirectories(root.resolve("content"));
        store = PlatformReviewFixtures.planStore(root, mapper);
        plans = PlatformReviewFixtures.delivery(root, mapper, mock(StaffAccessService.class), new ReviewStatusHistory.InMemory(), clock, "dev");
    }

    ReleasePlanImport importer(LiveRelease release, boolean allowed) {
        return new ReleasePlanImport(store, plans, new PublicationLocation("", content.toString(), "", release.configured(), 0, 1), release, allowed, mapper, clock);
    }

    Path staffAsset(String json) throws Exception {
        Path file = content.resolve(ReleasePlanImport.STAFF_ASSET);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, json);
    }

    /** The fixture plan with one more ticket and the base version the local edit started from. */
    String edited(long baseVersion, String ticketId) {
        ObjectNode plan = (ObjectNode) mapper.readTree(PlatformReviewFixtures.PLAN);
        plan.put("baseVersion", baseVersion);
        ObjectNode item = plan.withArray("workItems").addObject();
        item.put("id", ticketId).put("title", "From the release").put("summary", "Shipped").put("type", "story").put("stageId", "stage-1")
                .put("statusId", "ready").put("priorityId", "P1").put("updatedAt", "2026-11-09T19:00:00Z");
        item.putArray("labels");
        return mapper.writeValueAsString(plan);
    }

    @Test void seedsTheFirstPlanFromTheReleaseThenImportsTheSameFileOnlyOnce() throws Exception {
        staffAsset(PlatformReviewFixtures.PLAN);
        ReleasePlanImport.Outcome first;
        try (var log = LogCapture.start()) {
            first = importer(live, true).run();
            var line = log.only("delivery.release-import");
            assertThat(line.getFormattedMessage()).isEqualTo("Delivery plan seeded from the publication (v2026.11.09.20.00): version 7, 1 items");
            assertThat(LogCapture.keys(line)).containsEntry("action", "seeded").containsEntry("release", "v2026.11.09.20.00").containsEntry("file", "delivery-plan.json");
        }
        assertThat(first).isEqualTo(new ReleasePlanImport.Outcome("seeded", null, 7));
        assertThat(store.current().get().version()).isEqualTo(7);
        assertThat(store.inbox()).as("a seed never goes through the inbox").isEmpty();
        var marker = store.releaseImport().orElseThrow();
        assertThat(marker.path("schema").asString()).isEqualTo("lookahead.release-plan-import/v1");
        assertThat(marker.path("release").asString()).isEqualTo("v2026.11.09.20.00");
        assertThat(marker.path("planVersion").asLong()).isEqualTo(7);
        assertThat(marker.path("importedAt").asString()).isEqualTo("2026-11-09T20:00:00Z");
        assertThat(marker.path("sha256").asString()).isEqualTo(ReleasePlanImport.sha256(PlatformReviewFixtures.PLAN.getBytes(StandardCharsets.UTF_8)));

        var again = importer(live, true).run();
        assertThat(again).isEqualTo(new ReleasePlanImport.Outcome("skipped", "already imported (v2026.11.09.20.00)", 7));
        assertThat(store.current().get().version()).as("a restart on the same release does not raise the version").isEqualTo(7);
    }

    @Test void mergesALaterReleaseThroughTheInboxSoDevKeepsItsVersionHistory() throws Exception {
        PlatformReviewFixtures.seedPlan(root);
        staffAsset(edited(7, "DLV-2"));
        ReleasePlanImport.Outcome outcome;
        try (var log = LogCapture.start()) {
            outcome = importer(live, true).run();
            assertThat(log.only("delivery.release-import").getFormattedMessage())
                    .isEqualTo("Delivery plan from the publication (v2026.11.09.20.00) merged into plan version 8: 1 added, 0 taken from local, 0 kept from DEV, 0 conflict(s), 1 unchanged, 0 deleted on DEV");
            assertThat(LogCapture.keys(log.only("delivery.release-import"))).containsEntry("action", "merged").containsEntry("plan.version", 8L).containsEntry("plan.base", 7L);
            assertThat(log.lines("delivery.inbox-sync")).as("the existing merge did the work").hasSize(1);
        }
        assertThat(outcome).isEqualTo(new ReleasePlanImport.Outcome("merged", null, 8));
        var plan = store.current().orElseThrow();
        assertThat(plan.version()).isEqualTo(8);
        assertThat(plan.plan().path("workItems")).extracting(item -> item.path("id").asString()).containsExactly("DLV-1", "DLV-2");
        assertThat(store.inbox()).as("the inbox is consumed").isEmpty();
        assertThat(store.archived(7)).isPresent();
        assertThat(store.releaseImport().orElseThrow().path("planVersion").asLong()).isEqualTo(8);
        assertThat(importer(live, true).run().action()).isEqualTo("skipped");
        assertThat(store.current().orElseThrow().version()).isEqualTo(8);
    }

    @Test void leavesAnOwnersUploadInTheInboxAlone() throws Exception {
        PlatformReviewFixtures.seedPlan(root);
        byte[] upload = edited(7, "DLV-9").getBytes(StandardCharsets.UTF_8);
        store.putInbox(upload);
        staffAsset(edited(7, "DLV-2"));
        ReleasePlanImport.Outcome outcome;
        try (var log = LogCapture.start()) {
            outcome = importer(live, true).run();
            assertThat(LogCapture.keys(log.only("delivery.release-import"))).containsEntry("reason", "INBOX_OCCUPIED");
        }
        assertThat(outcome.action()).isEqualTo("skipped");
        assertThat(outcome.reason()).startsWith("the inbox already holds a file");
        assertThat(outcome.planVersion()).isEqualTo(7);
        assertThat(store.inbox().orElseThrow().bytes()).isEqualTo(upload);
        assertThat(store.releaseImport()).as("no marker, so the next start imports the release").isEmpty();
    }

    @Test void refusesABaseVersionDevDoesNotHave() throws Exception {
        PlatformReviewFixtures.seedPlan(root);
        staffAsset(edited(3, "DLV-2"));
        ReleasePlanImport.Outcome outcome;
        try (var log = LogCapture.start()) {
            outcome = importer(live, true).run();
            var line = log.only("delivery.release-import");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "BASE_VERSION_UNKNOWN");
            assertThat(line.getFormattedMessage()).isEqualTo("The published delivery plan was not imported: the released plan says it was edited from version 3, but DEV is at version 7 and has no archived version 3");
        }
        assertThat(outcome.action()).isEqualTo("skipped");
        assertThat(store.inbox()).isEmpty();
        assertThat(store.current().orElseThrow().version()).isEqualTo(7);
    }

    @Test void doesNothingInProdWithoutAFileOrWithAFileThatIsNotAPlan() throws Exception {
        assertThat(importer(live, false).run()).isEqualTo(new ReleasePlanImport.Outcome("skipped", "plan writes exist on DEV and local only", 0));
        assertThat(importer(live, true).run()).isEqualTo(new ReleasePlanImport.Outcome("skipped", "no plan file in the publication", 0));
        assertThat(store.current()).isEmpty();
        staffAsset("{\"version\": \"seven\"}");
        try (var log = LogCapture.start()) {
            var outcome = importer(live, true).run();
            assertThat(outcome.action()).isEqualTo("skipped");
            assertThat(outcome.reason()).contains("not a delivery plan");
            assertThat(LogCapture.keys(log.only("delivery.release-import"))).containsEntry("reason", "RELEASE_PLAN_INVALID");
        }
        assertThat(store.current()).isEmpty();
        assertThat(store.releaseImport()).isEmpty();
        var blankRoot = new ReleasePlanImport(store, plans, new PublicationLocation("", "", "", false, 0, 1), LiveRelease.notConfigured(), true, mapper, clock);
        assertThat(blankRoot.run().reason()).isEqualTo("no plan file in the publication");
    }

    @Test void aLocalDomainReadsTheRepositoryFileUnderItsContentRootAndNamesTheReleaseLocal() throws Exception {
        Path file = content.resolve(ReleasePlanImport.REPOSITORY_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, PlatformReviewFixtures.PLAN);
        var local = importer(LiveRelease.notConfigured(), true);
        assertThat(local.source()).contains(file);
        assertThat(local.run()).isEqualTo(new ReleasePlanImport.Outcome("seeded", null, 7));
        assertThat(store.releaseImport().orElseThrow().path("release").asString()).isEqualTo("local");
        assertThat(local.run().reason()).isEqualTo("already imported (local)");
    }
}
