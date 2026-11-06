package com.lookahead.domain.review.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-971/F9 regression, written against the import's public shape only so it also runs against the import before
 * the fix: one review and one request in the legacy prefix, the second record's write fails, a new importer retries.
 * Before the fix the retry saw the first record, skipped everything and the request stayed stranded with no marker.
 */
class LegacyReviewImportStrandingTest {
    static final class FailOnce implements PlatformReviewObjects {
        final PlatformReviewObjects delegate; String failKey;
        FailOnce(PlatformReviewObjects delegate, String failKey) { this.delegate = delegate; this.failKey = failKey; }
        @Override public Optional<Stored> read(String key) { return delegate.read(key); }
        @Override public boolean write(String key, byte[] bytes, String expectedVersion) {
            if (key.equals(failKey)) { failKey = null; throw new UncheckedIOException(new IOException("injected failure at " + key)); }
            return delegate.write(key, bytes, expectedVersion);
        }
        @Override public void put(String key, byte[] bytes) { delegate.put(key, bytes); }
        @Override public List<String> list(String prefix) { return delegate.list(prefix); }
        @Override public List<String> listAll(String prefix) { return delegate.listAll(prefix); }
        @Override public void delete(String key) { delegate.delete(key); }
    }

    @TempDir Path root;

    @Test void aRecordStrandedByAFailureAfterTheFirstRecordIsImportedByTheNextStart() throws Exception {
        var mapper = JsonMapper.builder().build();
        var numbers = new ReviewRecordNumbers.InMemory();
        var stores = new PlatformReviewService.Stores(
                new RecordStore(new FileSystemPlatformReviewObjects(root.resolve("reviews")), PlatformReviewModels.KIND_REVIEW, mapper, numbers),
                new RecordStore(new FailOnce(new FileSystemPlatformReviewObjects(root.resolve("support-requests")), "records/2026/10/13/SUP-000001.json"), PlatformReviewModels.KIND_REQUEST, mapper, numbers));
        var legacy = new FileSystemPlatformReviewObjects(root.resolve("legacy"));
        UUID reviewer = UUID.randomUUID(), learner = UUID.randomUUID();
        legacy.put("reviews/2026-10-12.json", mapper.writeValueAsBytes(new PlatformReviewModels.DayFile(PlatformReviewModels.DAY_SCHEMA, "2026-10-12", List.of(
                new PlatformReviewModels.StoredItem("item-a", reviewer, "abc", "First Reviewer", "problem", "Search", "/search", "Old tab", "Steps", "high", List.of(),
                        "accepted", "Noted", "2026-10-12T09:00:00Z", "2026-10-12T10:00:00Z", UUID.randomUUID(), "hash", UUID.randomUUID(), "reviewer", "review", null, null)))));
        legacy.put("reviews/2026-10-13.json", mapper.writeValueAsBytes(new PlatformReviewModels.DayFile(PlatformReviewModels.DAY_SCHEMA, "2026-10-13", List.of(
                new PlatformReviewModels.StoredItem("item-b", learner, "def", "A Learner", "idea", "Support", null, "Question", "Body", "medium", List.of(),
                        "new", "", "2026-10-13T09:00:00Z", "2026-10-13T09:00:00Z", null, null, null, "user", "request", "question", "ref-1")))));
        // Attempt one: the review lands, the request's write fails, no marker.
        assertThat(new LegacyReviewImport(legacy, stores, mapper).run()).isEmpty();
        assertThat(stores.reviews().all()).hasSize(1);
        assertThat(stores.requests().all()).isEmpty();
        assertThat(Files.exists(root.resolve("reviews/.imported.json"))).isFalse();
        // Attempt two, a new importer as a restarted task has: the request must be imported, once, and the marker written.
        var retry = new LegacyReviewImport(legacy, stores, mapper).run();
        assertThat(retry).as("the retry must import the stranded request").isPresent();
        assertThat(stores.reviews().all()).extracting(l -> l.record().id()).containsExactly("REV-000001");
        assertThat(stores.requests().all()).extracting(l -> l.record().id()).containsExactly("SUP-000001");
        assertThat(Files.readString(root.resolve("reviews/.imported.json"))).contains("\"migratedRecords\" : 2", "\"legacyRecords\" : 2");
    }
}
