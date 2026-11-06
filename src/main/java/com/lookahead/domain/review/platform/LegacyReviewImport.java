package com.lookahead.domain.review.platform;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.exception.AccountFailure;
import java.io.UncheckedIOException;
import java.util.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.platform.PlatformReviewModels.*;

/**
 * DLV-971 (owner, 2026-11-06): the one-time move of today's data. The legacy prefix ({@code platform-reviews/} on
 * DEV, {@code app.platform-reviews.legacy-prefix}) holds day files {@code reviews/<day>.json} and images under
 * {@code review-images/<itemId>/}. At start, when both new stores are empty and the legacy prefix has day files,
 * every item is rewritten as a record in the right store (reviews to {@code reviews/}, kind request to
 * {@code support-requests/}) with its images copied beside it, the counts are logged, and a marker
 * {@code reviews/.imported.json} with the counts keeps it from running twice. Legacy objects are never deleted here;
 * the AWS lane removes the old prefix after the counts match. Items are imported oldest first so the new ids follow
 * the original order.
 */
public final class LegacyReviewImport {
    public static final String MARKER = ".imported.json";
    private static final String DAYS = "reviews/", IMAGES = "review-images/";

    public record Counts(int reviews, int requests, int images, int days) {}

    private final PlatformReviewObjects legacy;
    private final PlatformReviewService.Stores stores;
    private final ObjectMapper mapper;

    public LegacyReviewImport(PlatformReviewObjects legacy, PlatformReviewService.Stores stores, ObjectMapper mapper) {
        this.legacy = legacy; this.stores = stores; this.mapper = mapper;
    }

    /** Runs the import when due; answers the counts, or empty when nothing was due. Failures are logged, never thrown. */
    public Optional<Counts> run() {
        try {
            if (legacy == null) return Optional.empty();
            if (stores.reviews().objects().read(MARKER).isPresent()) return Optional.empty();
            if (!stores.reviews().empty() || !stores.requests().empty()) {
                Events.success(Events.EVENT, "platform-review.import").addKeyValue("count", 0)
                        .log("Legacy review import skipped: the record stores already hold records; no marker written");
                return Optional.empty();
            }
            var days = legacy.list(DAYS).stream().map(key -> key.substring(DAYS.length()))
                    .filter(name -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.json")).sorted().toList();
            if (days.isEmpty()) return Optional.empty();
            int reviews = 0, requests = 0, images = 0;
            for (String day : days) {
                var stored = legacy.read(DAYS + day).orElse(null);
                if (stored == null) continue;
                for (StoredItem item : parse(day, stored.bytes()).items()) {
                    StoredItem repaired = item.repaired();
                    RecordStore store = repaired.request() ? stores.requests() : stores.reviews();
                    String id = store.nextId();
                    var attachments = new ArrayList<StoredAttachment>();
                    int index = 1;
                    for (StoredAttachment attachment : repaired.attachments() == null ? List.<StoredAttachment>of() : repaired.attachments()) {
                        var bytes = legacy.read(attachment.path());
                        if (bytes.isEmpty()) continue;
                        String path = RecordStore.attachmentKey(id, index++, attachment.name(), extension(attachment.contentType()));
                        store.putAttachment(path, bytes.get().bytes());
                        attachments.add(new StoredAttachment(attachment.id(), attachment.name(), attachment.contentType(), attachment.size(), attachment.sha256(), path));
                        images++;
                    }
                    var record = new StoredRecord(RECORD_SCHEMA, id, repaired.kind(), repaired.requestType(), repaired.status(),
                            new Submitter(repaired.reviewerAccountId(), repaired.submitterRole(), repaired.reviewerName()),
                            repaired.category(), repaired.title(), repaired.details(), repaired.page(), repaired.pageUrl(), repaired.priority(),
                            List.copyOf(attachments), repaired.reference(), null, repaired.ownerNote() == null ? "" : repaired.ownerNote(),
                            repaired.createdAt(), repaired.updatedAt(), null, null, repaired.createKey(), repaired.createHash(), repaired.lastWriteKey());
                    store.create(record);
                    if (repaired.request()) requests++; else reviews++;
                }
            }
            var counts = new Counts(reviews, requests, images, days.size());
            stores.reviews().objects().put(MARKER, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                    "schema", "lookahead.review-import/v1", "importedAt", java.time.Instant.now().toString(),
                    "reviews", reviews, "requests", requests, "images", images, "days", days.size())));
            Events.success(Events.EVENT, "platform-review.import").addKeyValue("count", reviews + requests).addKeyValue("reviews", reviews)
                    .addKeyValue("requests", requests).addKeyValue("images", images).addKeyValue("days", days.size())
                    .log("Legacy reviews imported: {} review(s) and {} request(s) from {} day file(s) with {} image(s); marker written, legacy objects kept", reviews, requests, days.size(), images);
            return Optional.of(counts);
        } catch (AccountFailure | UncheckedIOException failure) {
            Events.failed(Events.EVENT, "platform-review.import", "IMPORT_FAILED").addKeyValue("error.type", Events.types(failure))
                    .log("Legacy review import did not complete ({}); it runs again at the next start while no marker is written", Events.types(failure));
            return Optional.empty();
        }
    }

    private DayFile parse(String day, byte[] bytes) {
        try {
            var file = mapper.readValue(bytes, DayFile.class);
            if (!DAY_SCHEMA.equals(file.schema()) || file.items() == null) throw new AccountFailure(503, "DAY_FILE_SCHEMA", "Legacy day file " + day + " is not a review day file");
            return file;
        } catch (JacksonException broken) {
            throw new AccountFailure(503, "DAY_FILE_UNREADABLE", "Legacy day file " + day + " is not valid JSON");
        }
    }

    static String extension(String contentType) {
        return switch (contentType == null ? "" : contentType) { case "image/jpeg" -> "jpg"; case "image/webp" -> "webp"; default -> "png"; };
    }

    /** For the import of legacy image keys: {@code review-images/<itemId>/<uuid>.<ext>} lives in the legacy prefix. */
    static String legacyImagePrefix() { return IMAGES; }
}
