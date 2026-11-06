package com.lookahead.domain.review.platform;

import com.lookahead.domain.LogCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-971/F9: the legacy import resumes after a partial failure without duplicate records, lost images or cleared data.
 * Failures are injected into the object store under the record stores at exact keys; every retry is a new importer
 * instance against the same stores, the way a restarted task sees them.
 */
class LegacyReviewImportRetryTest {
    /** Wraps a store; a hook registered for {@code op:key} runs once before that operation (and may throw). */
    static final class FaultyObjects implements PlatformReviewObjects {
        final PlatformReviewObjects delegate;
        final Map<String, Runnable> hooks = new HashMap<>();
        final List<String> operations = new ArrayList<>();
        FaultyObjects(PlatformReviewObjects delegate) { this.delegate = delegate; }
        void failOnce(String op, String key) { hooks.put(op + ":" + key, () -> { throw new UncheckedIOException(new IOException("injected failure at " + op + " " + key)); }); }
        void before(String op, String key, Runnable action) { hooks.put(op + ":" + key, action); }
        private void fire(String op, String key) {
            operations.add(op + ":" + key);
            Runnable hook = hooks.remove(op + ":" + key);
            if (hook != null) hook.run();
        }
        @Override public Optional<Stored> read(String key) { fire("read", key); return delegate.read(key); }
        @Override public boolean write(String key, byte[] bytes, String expectedVersion) {
            fire("write", key);
            boolean written = delegate.write(key, bytes, expectedVersion);
            fire("after-write", key);
            return written;
        }
        @Override public void put(String key, byte[] bytes) { fire("put", key); delegate.put(key, bytes); }
        @Override public List<String> list(String prefix) { return delegate.list(prefix); }
        @Override public List<String> listAll(String prefix) { return delegate.listAll(prefix); }
        @Override public void delete(String key) { delegate.delete(key); }
        long count(String op, String key) { return operations.stream().filter((op + ":" + key)::equals).count(); }
    }

    static final String A_PROGRESS = ".import-progress/2026-10-12/item-a.json", B_PROGRESS = ".import-progress/2026-10-13/item-b.json",
            C_PROGRESS = ".import-progress/2026-10-13/item-c.json", A_RECORD = "records/2026/10/12/REV-000001.json",
            B_RECORD = "records/2026/10/13/SUP-000001.json", C_RECORD = "records/2026/10/13/REV-000002.json",
            A_IMAGE = "attachments/REV-000001/01-shot-one.png";

    @TempDir Path root;
    final JsonMapper mapper = JsonMapper.builder().build();
    final UUID reviewer = UUID.randomUUID(), learner = UUID.randomUUID();
    FaultyObjects reviews, requests;
    PlatformReviewService.Stores stores;
    FileSystemPlatformReviewObjects legacy;
    byte[] png;

    @BeforeEach void setup() throws Exception {
        reviews = new FaultyObjects(new FileSystemPlatformReviewObjects(root.resolve("reviews")));
        requests = new FaultyObjects(new FileSystemPlatformReviewObjects(root.resolve("support-requests")));
        var numbers = new ReviewRecordNumbers.InMemory();
        stores = new PlatformReviewService.Stores(new RecordStore(reviews, PlatformReviewModels.KIND_REVIEW, mapper, numbers),
                new RecordStore(requests, PlatformReviewModels.KIND_REQUEST, mapper, numbers));
        legacy = new FileSystemPlatformReviewObjects(root.resolve("legacy"));
        png = PlatformReviewTestImages.png();
        legacy.put("review-images/item-a/" + UUID.randomUUID() + ".png", png);
        String imageKey = legacy.list("review-images/item-a/").getFirst();
        var day1 = new PlatformReviewModels.DayFile(PlatformReviewModels.DAY_SCHEMA, "2026-10-12", List.of(
                new PlatformReviewModels.StoredItem("item-a", reviewer, "abc", "First Reviewer", "problem", "Look Ahead Catalog", "/look-ahead", "Old tab", "Steps", "high",
                        List.of(new PlatformReviewModels.StoredAttachment("att-1", "Shot One.png", "image/png", png.length, "h", imageKey)),
                        "accepted", "Noted", "2026-10-12T09:00:00Z", "2026-10-12T10:00:00Z", UUID.randomUUID(), "hash", UUID.randomUUID(), "reviewer", "review", null, null)));
        var day2 = new PlatformReviewModels.DayFile(PlatformReviewModels.DAY_SCHEMA, "2026-10-13", List.of(
                new PlatformReviewModels.StoredItem("item-b", learner, "def", "A Learner", "idea", "Support", null, "Question", "Body", "medium", List.of(),
                        "new", "", "2026-10-13T09:00:00Z", "2026-10-13T09:00:00Z", null, null, null, "user", "request", "question", "ref-1"),
                new PlatformReviewModels.StoredItem("item-c", reviewer, "abc", "First Reviewer", "change", "Search", "", "Later", "More", "low", List.of(),
                        "new", "", "2026-10-13T11:00:00Z", "2026-10-13T11:00:00Z", UUID.randomUUID(), "hash", UUID.randomUUID(), "reviewer", "review", null, null)));
        legacy.put("reviews/2026-10-12.json", mapper.writeValueAsBytes(day1));
        legacy.put("reviews/2026-10-13.json", mapper.writeValueAsBytes(day2));
    }

    LegacyReviewImport importer() { return new LegacyReviewImport(legacy, stores, mapper, "0123456789abcdef0123456789abcdef01234567"); }

    Optional<LegacyReviewImport.Counts> failingRun() {
        try (var log = LogCapture.start()) {
            var counts = importer().run();
            assertThat(counts).isEmpty();
            var line = log.only("platform-review.import");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "IMPORT_FAILED");
            assertThat(line.getFormattedMessage()).contains("the progress so far is kept and the import resumes at the next start while no marker is written");
            return counts;
        }
    }

    /** The complete state after any successful run: three records with fixed ids, the one image, the marker, legacy untouched. */
    void assertComplete(int resumed) throws Exception { assertComplete(resumed, "SUP-000001"); }

    void assertComplete(int resumed, String requestId) throws Exception {
        assertThat(stores.reviews().all()).extracting(l -> l.record().id()).containsExactlyInAnyOrder("REV-000001", "REV-000002");
        assertThat(stores.requests().all()).extracting(l -> l.record().id()).containsExactly(requestId);
        var first = stores.reviews().find("REV-000001").orElseThrow().record();
        assertThat(first.title()).isEqualTo("Old tab");
        assertThat(first.attachments()).extracting(PlatformReviewModels.StoredAttachment::path).containsExactly(A_IMAGE);
        assertThat(Files.readAllBytes(root.resolve("reviews/" + A_IMAGE))).isEqualTo(png);
        assertThat(stores.reviews().find("REV-000002").orElseThrow().record().title()).isEqualTo("Later");
        assertThat(stores.requests().find(requestId).orElseThrow().record().reference()).isEqualTo("ref-1");
        String marker = Files.readString(root.resolve("reviews/.imported.json"));
        assertThat(marker).contains("\"reviews\" : 2", "\"requests\" : 1", "\"images\" : 1", "\"days\" : 2", "\"legacyRecords\" : 3", "\"migratedRecords\" : 3",
                "\"legacyAttachments\" : 1", "\"migratedAttachments\" : 1", "\"missingAttachments\" : 0", "\"resumedRecords\" : " + resumed,
                "\"domainSourceSha\" : \"0123456789abcdef0123456789abcdef01234567\"");
        assertThat(marker).doesNotContain("\"missing\" :");
        assertThat(legacy.list("reviews/")).hasSize(2);
        assertThat(legacy.list("review-images/item-a/")).hasSize(1);
        try (var log = LogCapture.start()) {
            assertThat(importer().run()).as("a completed import is a no-op").isEmpty();
            assertThat(log.lines()).isEmpty();
        }
    }

    @Test void aFailureAfterTheFirstRecordIsCreatedLeavesItAndTheRestartImportsTheRemainingRecordsExactlyOnce() throws Exception {
        // The second item's progress write fails: record one is in the store, no marker exists.
        reviews.failOnce("write", B_PROGRESS);
        failingRun();
        assertThat(stores.reviews().all()).extracting(l -> l.record().id()).containsExactly("REV-000001");
        assertThat(stores.requests().all()).isEmpty();
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        // The restart: record one is reused, not duplicated; the other two are written once; the counts are complete.
        var counts = importer().run().orElseThrow();
        assertThat(counts).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        assertThat(reviews.count("write", A_RECORD)).as("record one written once across both runs").isEqualTo(1);
        assertThat(reviews.count("put", A_IMAGE)).as("its image copied once").isEqualTo(1);
        // SUP-000001 was drawn before the failed progress write: a gap in the sequence, never a record.
        assertThat(requests.count("write", B_RECORD)).isEqualTo(0);
        assertThat(requests.count("write", "records/2026/10/13/SUP-000002.json")).isEqualTo(1);
        assertThat(reviews.count("write", C_RECORD)).isEqualTo(1);
        assertComplete(1, "SUP-000002");
    }

    @Test void aFailureDuringTheAttachmentCopyKeepsTheIdAndTheRetryCopiesTheSameBytesToTheSameKeyOnce() throws Exception {
        reviews.failOnce("put", A_IMAGE);
        failingRun();
        assertThat(Files.readString(root.resolve("reviews/" + A_PROGRESS))).contains("\"recordId\" : \"REV-000001\"");
        assertThat(Files.exists(root.resolve("reviews/" + A_RECORD))).isFalse();
        assertThat(Files.exists(root.resolve("reviews/" + A_IMAGE))).isFalse();
        assertThat(importer().run().orElseThrow()).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        assertThat(reviews.count("put", A_IMAGE)).as("one failed put, one that landed").isEqualTo(2);
        assertThat(reviews.count("write", A_RECORD)).isEqualTo(1);
        assertComplete(0);
    }

    @Test void aFailureAfterTheRecordIsPersistedButBeforeItsReadBackIsResumedWithoutASecondWrite() throws Exception {
        // The record object lands, then the read that confirms it fails: the next run finds the record and verifies it.
        reviews.before("after-write", A_RECORD, () -> reviews.failOnce("read", A_RECORD));
        failingRun();
        assertThat(Files.exists(root.resolve("reviews/" + A_RECORD))).isTrue();
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        assertThat(importer().run().orElseThrow()).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        assertThat(reviews.count("write", A_RECORD)).isEqualTo(1);
        assertThat(reviews.count("put", A_IMAGE)).as("the image is verified present, not copied again").isEqualTo(1);
        assertComplete(1);
    }

    @Test void aFailureWritingTheMarkerIsRetriedWithCountsFromTheStoresNotFromTheAttempt() throws Exception {
        reviews.failOnce("write", LegacyReviewImport.MARKER);
        failingRun();
        assertThat(stores.reviews().all()).hasSize(2);
        assertThat(stores.requests().all()).hasSize(1);
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        LegacyReviewImport.Counts counts;
        try (var log = LogCapture.start()) {
            counts = importer().run().orElseThrow();
            var line = log.only("platform-review.import");
            assertThat(line.getFormattedMessage()).isEqualTo("Legacy reviews imported: 2 review(s) and 1 request(s) from 2 day file(s) with 1 image(s); 3 record(s) were already in place from an earlier attempt, 0 legacy image(s) do not exist; marker written, legacy objects kept");
        }
        assertThat(counts).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        assertThat(reviews.count("write", A_RECORD)).isEqualTo(1);
        assertThat(reviews.count("write", C_RECORD)).isEqualTo(1);
        assertThat(requests.count("write", B_RECORD)).isEqualTo(1);
        assertComplete(3);
    }

    @Test void aRecordImageLostAfterTheRecordWasWrittenIsCopiedAgainFromTheLegacyObjectOnTheRetry() throws Exception {
        reviews.failOnce("write", LegacyReviewImport.MARKER);
        failingRun();
        Files.delete(root.resolve("reviews/" + A_IMAGE));
        assertThat(importer().run().orElseThrow()).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        assertThat(reviews.count("put", A_IMAGE)).isEqualTo(2);
        assertComplete(3);
    }

    @Test void recordsWrittenBySupportAfterTheImportAndTheLegacyObjectsStayUntouchedByRepeatedRuns() throws Exception {
        assertThat(importer().run().orElseThrow()).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        var service = new PlatformReviewService(stores, org.mockito.Mockito.mock(com.lookahead.learning.content.security.LocalAuthorAccess.class), mapper, "", java.time.Clock.systemUTC(), new ReviewStatusHistory.InMemory());
        service.recordRequest(new com.lookahead.learning.content.security.AccountPrincipal(learner, "l@example.test", "L", true), "problem", "Later request", "Body", List.of(), "ref-9");
        assertThat(stores.requests().all()).extracting(l -> l.record().id()).containsExactlyInAnyOrder("SUP-000001", "SUP-000002");
        byte[] markerBefore = Files.readAllBytes(root.resolve("reviews/.imported.json"));
        for (int run = 0; run < 3; run++) assertThat(importer().run()).isEmpty();
        assertThat(Files.readAllBytes(root.resolve("reviews/.imported.json"))).isEqualTo(markerBefore);
        assertThat(stores.requests().all()).extracting(l -> l.record().id()).containsExactlyInAnyOrder("SUP-000001", "SUP-000002");
        assertThat(stores.reviews().all()).hasSize(2);
        assertThat(legacy.list("reviews/")).hasSize(2);
        assertThat(legacy.list("review-images/item-a/")).hasSize(1);
    }

    /**
     * Two tasks start at the same moment (a rolling deploy): B runs completely while A is about to fix item-b's
     * destination. A's progress write loses the if-none-match race, A reads B's destination, finds the record and
     * counts it; the number A drew is a gap in the sequence, never a second record. The marker is written once.
     */
    @Test void twoImportersAtTheSameMomentCannotDuplicateARecordBecauseProgressIsFirstWinsAndCreatesAreIfNoneMatch() throws Exception {
        var a = importer();
        var b = importer();
        var bCounts = new Optional[1];
        requests.before("write", B_PROGRESS, () -> { /* a.nextId() already drew SUP-000001; B now runs to the end */ bCounts[0] = b.run(); });
        // The progress objects live in the reviews store; the hook above must sit there.
        reviews.before("write", B_PROGRESS, () -> { bCounts[0] = b.run(); });
        LegacyReviewImport.Counts aCounts;
        try (var log = LogCapture.start()) {
            aCounts = a.run().orElseThrow();
            var lines = log.lines("platform-review.import");
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0).getFormattedMessage()).contains("1 record(s) were already in place", "marker written");
            assertThat(lines.get(1).getFormattedMessage()).contains("2 record(s) were already in place", "marker already written by another task");
        }
        assertThat(bCounts[0]).isEqualTo(Optional.of(new LegacyReviewImport.Counts(2, 1, 1, 2)));
        assertThat(aCounts).isEqualTo(new LegacyReviewImport.Counts(2, 1, 1, 2));
        assertThat(stores.reviews().all()).extracting(l -> l.record().id()).containsExactlyInAnyOrder("REV-000001", "REV-000002");
        assertThat(stores.requests().all()).extracting(l -> l.record().id()).as("SUP-000001 drawn by A is a gap; B's SUP-000002 is the record").containsExactly("SUP-000002");
        assertThat(Files.readString(root.resolve("reviews/" + B_PROGRESS))).contains("\"recordId\" : \"SUP-000002\"");
        assertThat(reviews.count("write", A_RECORD)).isEqualTo(1);
        assertThat(reviews.count("write", C_RECORD)).isEqualTo(1);
        assertThat(requests.count("write", "records/2026/10/13/SUP-000002.json")).isEqualTo(1);
        assertThat(reviews.count("write", LegacyReviewImport.MARKER)).as("both tasks try the marker; if-none-match lets one through").isEqualTo(2);
        assertThat(Files.readString(root.resolve("reviews/.imported.json"))).contains("\"migratedRecords\" : 3", "\"resumedRecords\" : 1");
    }

    @Test void aProgressObjectThatDescribesAnotherItemOrIsUnreadableStopsTheImportWithoutWritingAnything() throws Exception {
        reviews.put(A_PROGRESS, mapper.writeValueAsBytes(new LegacyReviewImport.Progress(LegacyReviewImport.PROGRESS_SCHEMA, "2026-10-12", "item-z", "review", "REV-000009", "2026-10-12T09:00:00Z", "2026-11-06T00:00:00Z")));
        try (var log = LogCapture.start()) {
            assertThat(importer().run()).isEmpty();
            assertThat(LogCapture.keys(log.only("platform-review.import"))).containsEntry("reason", "IMPORT_FAILED").containsEntry("error.type", "AccountFailure");
        }
        assertThat(stores.reviews().all()).isEmpty();
        assertThat(stores.requests().all()).isEmpty();
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        reviews.put(A_PROGRESS, "{ not json".getBytes());
        assertThat(importer().run()).isEmpty();
        reviews.put(A_PROGRESS, "{\"schema\":\"other\"}".getBytes());
        assertThat(importer().run()).isEmpty();
        assertThat(stores.reviews().all()).isEmpty();
        // A record under the fixed id that is not this item's import (another kind): stopped the same way.
        reviews.put(A_PROGRESS, mapper.writeValueAsBytes(new LegacyReviewImport.Progress(LegacyReviewImport.PROGRESS_SCHEMA, "2026-10-12", "item-a", "review", "REV-000001", "2026-10-12T09:00:00Z", "2026-11-06T00:00:00Z")));
        var foreign = stores.reviews().find("REV-000001");
        assertThat(foreign).isEmpty();
        var record = new PlatformReviewModels.StoredRecord(PlatformReviewModels.RECORD_SCHEMA, "REV-000001", "request", "question", "new",
                new PlatformReviewModels.Submitter(learner, "user", "L"), "idea", "Support", null, "Other", "Body", "medium", List.of(), "ref-2", null, "",
                "2026-10-12T09:00:00Z", "2026-10-12T09:00:00Z", null, null, null, null, null);
        assertThat(stores.reviews().createIfAbsent(record)).isTrue();
        assertThat(stores.reviews().createIfAbsent(record)).as("if-none-match: a second create of the same key is refused").isFalse();
        try (var log = LogCapture.start()) {
            assertThat(importer().run()).isEmpty();
            assertThat(LogCapture.keys(log.only("platform-review.import"))).containsEntry("reason", "IMPORT_FAILED");
        }
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        assertThat(stores.reviews().all()).hasSize(1);
    }

    @Test void progressKeysKeepReadableIdsAndHashTheRest() {
        assertThat(LegacyReviewImport.progressKey("2026-10-12.json", "item-a")).isEqualTo(".import-progress/2026-10-12/item-a.json");
        assertThat(LegacyReviewImport.progressKey("2026-10-12.json", "8f3c2a1e-0000-4000-8000-000000000000")).isEqualTo(".import-progress/2026-10-12/8f3c2a1e-0000-4000-8000-000000000000.json");
        assertThat(LegacyReviewImport.progressKey("2026-10-12.json", "../etc")).matches("\\.import-progress/2026-10-12/[0-9a-f]{64}\\.json");
        assertThat(LegacyReviewImport.progressKey("2026-10-12.json", ".hidden")).matches("\\.import-progress/2026-10-12/[0-9a-f]{64}\\.json");
        assertThat(LegacyReviewImport.progressKey("2026-10-12.json", null)).matches("\\.import-progress/2026-10-12/[0-9a-f]{64}\\.json");
        assertThat(LegacyReviewImport.listed(List.of("a", "b"))).containsExactly("a", "b");
        var many = new ArrayList<String>(); for (int i = 0; i < 60; i++) many.add("p" + i);
        assertThat(LegacyReviewImport.listed(many)).hasSize(51).endsWith("...");
    }
}
