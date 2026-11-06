package com.lookahead.domain.review.platform;

import com.lookahead.domain.LogCapture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** DLV-971: the one-time import of the legacy day files and images into the record stores; never twice, never deleting. */
class LegacyReviewImportTest {
    @TempDir Path root;
    final JsonMapper mapper = JsonMapper.builder().build();
    final UUID reviewer = UUID.randomUUID(), learner = UUID.randomUUID();
    PlatformReviewService.Stores stores;
    FileSystemPlatformReviewObjects legacy;

    @BeforeEach void setup() throws Exception {
        legacy = new FileSystemPlatformReviewObjects(root.resolve("legacy"));
        stores = PlatformReviewFixtures.stores(root, mapper);
        byte[] png = PlatformReviewTestImages.png();
        legacy.put("review-images/item-a/" + UUID.randomUUID() + ".png", png);
        String imageKey = legacy.list("review-images/item-a/").getFirst();
        var day1 = new PlatformReviewModels.DayFile(PlatformReviewModels.DAY_SCHEMA, "2026-10-12", List.of(
                new PlatformReviewModels.StoredItem("item-a", reviewer, "abc", "First Reviewer", "problem", "Look Ahead Catalog", "/look-ahead", "Old tab", "Steps", "high",
                        List.of(new PlatformReviewModels.StoredAttachment("att-1", "Shot One.png", "image/png", png.length, "h", imageKey)),
                        "accepted", "Noted", "2026-10-12T09:00:00Z", "2026-10-12T10:00:00Z", UUID.randomUUID(), "hash", UUID.randomUUID(), "reviewer", null, null, null)));
        var day2 = new PlatformReviewModels.DayFile(PlatformReviewModels.DAY_SCHEMA, "2026-10-13", List.of(
                new PlatformReviewModels.StoredItem("item-b", learner, "def", "A Learner", "idea", "Support", null, "Question", "Body", "medium", List.of(),
                        "new", "", "2026-10-13T09:00:00Z", "2026-10-13T09:00:00Z", null, null, null, "user", "request", "question", "ref-1"),
                new PlatformReviewModels.StoredItem("item-c", reviewer, "abc", "First Reviewer", "change", "Search", "", "Later", "More", "low",
                        List.of(new PlatformReviewModels.StoredAttachment("att-gone", "gone.png", "image/png", 1, "h", "review-images/item-c/missing.png")),
                        "new", "", "2026-10-13T11:00:00Z", "2026-10-13T11:00:00Z", UUID.randomUUID(), "hash", UUID.randomUUID(), "reviewer", "review", null, null)));
        legacy.put("reviews/2026-10-12.json", mapper.writeValueAsBytes(day1));
        legacy.put("reviews/2026-10-13.json", mapper.writeValueAsBytes(day2));
        legacy.put("reviews/notes.txt", "ignored".getBytes());
    }

    @Test void importsEveryItemIntoTheRightStoreWithImagesAndWritesTheMarkerOnce() throws Exception {
        var importer = new LegacyReviewImport(legacy, stores, mapper);
        LegacyReviewImport.Counts counts;
        try (var log = LogCapture.start()) {
            counts = importer.run().orElseThrow();
            var line = log.only("platform-review.import");
            assertThat(line.getFormattedMessage()).isEqualTo("Legacy reviews imported: 2 review(s) and 1 request(s) from 2 day file(s) with 1 image(s); marker written, legacy objects kept");
            assertThat(LogCapture.keys(line)).containsEntry("count", 3).containsEntry("reviews", 2).containsEntry("requests", 1).containsEntry("images", 1);
            log.containsNone("Old tab", "First Reviewer");
        }
        assertThat(counts).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        var first = stores.reviews().find("REV-000001").orElseThrow().record();
        assertThat(first.title()).isEqualTo("Old tab");
        assertThat(first.page()).isEqualTo("Blaze Catalog");
        assertThat(first.status()).isEqualTo("accepted");
        assertThat(first.ownerNote()).isEqualTo("Noted");
        assertThat(first.submitter()).isEqualTo(new PlatformReviewModels.Submitter(reviewer, "reviewer", "First Reviewer"));
        assertThat(first.createdAt()).isEqualTo("2026-10-12T09:00:00Z");
        assertThat(first.attachments()).hasSize(1);
        assertThat(first.attachments().getFirst().path()).isEqualTo("attachments/REV-000001/01-shot-one.png");
        assertThat(first.attachments().getFirst().id()).isEqualTo("att-1");
        assertThat(Files.exists(root.resolve("reviews/attachments/REV-000001/01-shot-one.png"))).isTrue();
        assertThat(Files.exists(root.resolve("reviews/records/2026/10/12/REV-000001.json"))).isTrue();
        var second = stores.reviews().find("REV-000002").orElseThrow().record();
        assertThat(second.title()).isEqualTo("Later");
        assertThat(second.attachments()).as("a missing legacy image is skipped").isEmpty();
        var request = stores.requests().find("SUP-000001").orElseThrow().record();
        assertThat(request.kind()).isEqualTo("request");
        assertThat(request.requestType()).isEqualTo("question");
        assertThat(request.reference()).isEqualTo("ref-1");
        assertThat(request.submitter().role()).isEqualTo("user");
        assertThat(Files.exists(root.resolve("reviews/records/2026/10/13/REV-000002.json"))).isTrue();
        assertThat(Files.exists(root.resolve("support-requests/records/2026/10/13/SUP-000001.json"))).isTrue();
        // The marker carries the counts; the legacy objects are untouched; a second run does nothing.
        String marker = Files.readString(root.resolve("reviews/.imported.json"));
        assertThat(marker).contains("\"reviews\" : 2", "\"requests\" : 1", "\"images\" : 1", "\"days\" : 2");
        assertThat(legacy.list("reviews/")).containsExactlyInAnyOrder("reviews/2026-10-12.json", "reviews/2026-10-13.json", "reviews/notes.txt");
        assertThat(legacy.list("review-images/item-a/")).hasSize(1);
        try (var log = LogCapture.start()) {
            assertThat(importer.run()).isEmpty();
            assertThat(log.lines()).isEmpty();
        }
        assertThat(stores.reviews().all()).hasSize(2);
        assertThat(stores.requests().all()).hasSize(1);
    }

    @Test void nothingHappensWithoutALegacyStoreOrDayFilesAndANonEmptyStoreIsNeverImportedInto() throws Exception {
        assertThat(new LegacyReviewImport(null, stores, mapper).run()).isEmpty();
        var empty = new FileSystemPlatformReviewObjects(root.resolve("empty"));
        assertThat(new LegacyReviewImport(empty, stores, mapper).run()).isEmpty();
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        // A record already in the store: the import is skipped and says so, no marker is written.
        var service = new PlatformReviewService(stores, org.mockito.Mockito.mock(com.lookahead.learning.content.security.LocalAuthorAccess.class), mapper, "", java.time.Clock.systemUTC(), new ReviewStatusHistory.InMemory());
        service.recordRequest(new com.lookahead.learning.content.security.AccountPrincipal(learner, "l@example.test", "L", true), "problem", "x", "y", List.of(), "ref-9");
        try (var log = LogCapture.start()) {
            assertThat(new LegacyReviewImport(legacy, stores, mapper).run()).isEmpty();
            assertThat(log.only("platform-review.import").getFormattedMessage()).isEqualTo("Legacy review import skipped: the record stores already hold records; no marker written");
        }
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
    }

    @Test void aDamagedDayFileStopsTheImportWithoutAMarkerSoTheNextStartTriesAgain() throws Exception {
        legacy.put("reviews/2026-10-14.json", "{ not json".getBytes());
        try (var log = LogCapture.start()) {
            assertThat(new LegacyReviewImport(legacy, stores, mapper).run()).isEmpty();
            assertThat(LogCapture.keys(log.only("platform-review.import"))).containsEntry("reason", "IMPORT_FAILED");
        }
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        legacy.put("reviews/2026-10-14.json", "{\"schema\":\"other\",\"day\":\"2026-10-14\",\"items\":[]}".getBytes());
        assertThat(new LegacyReviewImport(legacy, stores, mapper).run()).isEmpty();
        assertThat(LegacyReviewImport.extension("image/jpeg")).isEqualTo("jpg");
        assertThat(LegacyReviewImport.extension("image/webp")).isEqualTo("webp");
        assertThat(LegacyReviewImport.extension(null)).isEqualTo("png");
        assertThat(LegacyReviewImport.legacyImagePrefix()).isEqualTo("review-images/");
    }
}
