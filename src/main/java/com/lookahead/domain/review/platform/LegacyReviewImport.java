package com.lookahead.domain.review.platform;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.exception.AccountFailure;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.platform.PlatformReviewModels.*;

/**
 * DLV-971 (owner, 2026-11-06): the one-time move of today's data. The legacy prefix ({@code platform-reviews/} on
 * DEV, {@code app.platform-reviews.legacy-prefix}) holds day files {@code reviews/<day>.json} and images under
 * {@code review-images/<itemId>/}. At start, while no marker exists and the legacy prefix has day files, every item
 * is rewritten as a record in the right store (reviews to {@code reviews/}, kind request to {@code support-requests/})
 * with its images copied beside it, the counts are logged, and a marker {@code reviews/.imported.json} with the
 * counts keeps it from running twice. Legacy objects are never deleted here; the AWS lane removes the old prefix
 * after the counts match. Items are imported oldest first so the new ids follow the original order.
 *
 * <p>DLV-971/F9: the import survives a partial failure. Before anything is written for an item, its destination is
 * fixed in a progress object {@code reviews/.import-progress/<day>/<item>.json} (legacy day and item id, kind, the
 * record id drawn from the sequence, the legacy {@code createdAt} that decides the record key), created with
 * if-none-match so the first writer wins. A later run, or a second task starting at the same moment, reads that
 * object back and uses the same id and the same attachment keys: an attachment put is idempotent (same key, same
 * bytes), a record create is if-none-match and an existing record is verified and counted instead of written. The
 * marker is written only after every legacy item has a verified record, with counts taken from what is in the
 * stores, not from this attempt. A legacy image object that does not exist is reported in the marker
 * ({@code missingAttachments}), never invented; a storage failure stops the run, keeps the progress and is retried
 * at the next start. Records in the stores without any progress object block the import (nothing is written)
 * because their origin is unknown.
 */
public final class LegacyReviewImport {
    public static final String MARKER = ".imported.json";
    public static final String PROGRESS = ".import-progress/";
    static final String MARKER_SCHEMA = "lookahead.review-import/v1", PROGRESS_SCHEMA = "lookahead.review-import-progress/v1";
    private static final String DAYS = "reviews/", IMAGES = "review-images/";
    private static final int MISSING_LISTED = 50;

    public record Counts(int reviews, int requests, int images, int days) {}

    /** One legacy item's fixed destination: written first-wins before any record or attachment of the item. */
    public record Progress(String schema, String day, String itemId, String kind, String recordId, String createdAt, String assignedAt) {}

    private final PlatformReviewObjects legacy;
    private final PlatformReviewService.Stores stores;
    private final ObjectMapper mapper;
    /** The deployed Domain's source revision, written into the marker as {@code domainSourceSha}; "unknown" without build info. */
    private final String sourceRevision;

    public LegacyReviewImport(PlatformReviewObjects legacy, PlatformReviewService.Stores stores, ObjectMapper mapper) {
        this(legacy, stores, mapper, null);
    }

    public LegacyReviewImport(PlatformReviewObjects legacy, PlatformReviewService.Stores stores, ObjectMapper mapper, String sourceRevision) {
        this.legacy = legacy; this.stores = stores; this.mapper = mapper;
        this.sourceRevision = sourceRevision == null || sourceRevision.isBlank() ? "unknown" : sourceRevision;
    }

    /** Runs or resumes the import when due; answers the counts, or empty when nothing was due. Failures are logged, never thrown. */
    public Optional<Counts> run() {
        try {
            if (legacy == null) return Optional.empty();
            PlatformReviewObjects progressStore = stores.reviews().objects();
            if (progressStore.read(MARKER).isPresent()) return Optional.empty();
            var days = legacy.list(DAYS).stream().map(key -> key.substring(DAYS.length()))
                    .filter(name -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.json")).sorted().toList();
            if (days.isEmpty()) return Optional.empty();
            int progressObjects = progressStore.listAll(PROGRESS).size();
            if (progressObjects == 0 && (!stores.reviews().empty() || !stores.requests().empty())) {
                Events.failed(Events.EVENT, "platform-review.import", "IMPORT_BLOCKED").addKeyValue("count", 0)
                        .log("Legacy review import blocked: the record stores already hold records and no import progress exists, so their origin is unknown; nothing was written and no marker exists. Recovery: the owner reads the records, then either removes them or writes reviews/.imported.json by hand");
                return Optional.empty();
            }
            int reviews = 0, requests = 0, images = 0, legacyRecords = 0, legacyAttachments = 0, resumed = 0;
            var missing = new ArrayList<String>();
            String now = java.time.Instant.now().toString();
            for (String day : days) {
                var stored = legacy.read(DAYS + day).orElse(null);
                if (stored == null) continue;
                for (StoredItem item : parse(day, stored.bytes()).items()) {
                    legacyRecords++;
                    StoredItem repaired = item.repaired();
                    var legacyAttachmentList = repaired.attachments() == null ? List.<StoredAttachment>of() : repaired.attachments();
                    legacyAttachments += legacyAttachmentList.size();
                    RecordStore store = repaired.request() ? stores.requests() : stores.reviews();
                    Progress progress = destination(progressStore, day, repaired, now);
                    String id = progress.recordId();
                    String recordKey = RecordStore.key(id, repaired.createdAt());
                    var existing = store.read(recordKey);
                    List<StoredAttachment> attachments;
                    if (existing.isPresent()) {
                        // Written by an earlier attempt or by the other task: verified, its images checked, counted, never rewritten.
                        StoredRecord record = existing.get().record();
                        if (!id.equals(record.id()) || !repaired.kind().equals(record.kind()))
                            throw new AccountFailure(503, "IMPORT_PROGRESS_MISMATCH", "Record " + id + " is not the import of legacy item " + day + "/" + repaired.id());
                        attachments = record.attachments();
                        for (StoredAttachment attachment : attachments)
                            if (store.readAttachment(attachment.path()).isEmpty()) copy(store, legacyAttachmentList, attachment.id(), attachment.path());
                        for (StoredAttachment legacyAttachment : legacyAttachmentList)
                            if (attachments.stream().noneMatch(a -> Objects.equals(a.id(), legacyAttachment.id()))) note(missing, legacyAttachment.path());
                        resumed++;
                    } else {
                        var copied = new ArrayList<StoredAttachment>();
                        int index = 0;
                        for (StoredAttachment attachment : legacyAttachmentList) {
                            index++;
                            var bytes = legacy.read(attachment.path());
                            if (bytes.isEmpty()) { note(missing, attachment.path()); continue; }
                            String path = RecordStore.attachmentKey(id, index, attachment.name(), extension(attachment.contentType()));
                            store.putAttachment(path, bytes.get().bytes());
                            copied.add(new StoredAttachment(attachment.id(), attachment.name(), attachment.contentType(), attachment.size(), attachment.sha256(), path));
                        }
                        var record = new StoredRecord(RECORD_SCHEMA, id, repaired.kind(), repaired.requestType(), repaired.status(),
                                new Submitter(repaired.reviewerAccountId(), repaired.submitterRole(), repaired.reviewerName()),
                                repaired.category(), repaired.title(), repaired.details(), repaired.page(), repaired.pageUrl(), repaired.priority(),
                                List.copyOf(copied), repaired.reference(), null, repaired.ownerNote() == null ? "" : repaired.ownerNote(),
                                repaired.createdAt(), repaired.updatedAt(), null, null, repaired.createKey(), repaired.createHash(), repaired.lastWriteKey());
                        if (!store.createIfAbsent(record)) resumed++; // the other task created it between our read and our write
                        attachments = store.read(recordKey).orElseThrow(RecordStore::unavailable).record().attachments();
                    }
                    images += attachments.size();
                    if (repaired.request()) requests++; else reviews++;
                }
            }
            var counts = new Counts(reviews, requests, images, days.size());
            // The marker is the migration report the AWS lane's transition B reads: the legacy counts beside the migrated
            // ones, the deployed source revision and the time; the owner adds ownerApprovedCleanup when reading it back.
            String verifiedAt = java.time.Instant.now().toString();
            var marker = new LinkedHashMap<String, Object>();
            marker.put("schema", MARKER_SCHEMA);
            marker.put("importedAt", verifiedAt);
            marker.put("verifiedAt", verifiedAt);
            marker.put("domainSourceSha", sourceRevision);
            marker.put("reviews", reviews); marker.put("requests", requests); marker.put("images", images); marker.put("days", days.size());
            marker.put("legacyRecords", legacyRecords); marker.put("migratedRecords", reviews + requests);
            marker.put("legacyAttachments", legacyAttachments); marker.put("migratedAttachments", images);
            marker.put("missingAttachments", missing.size());
            if (!missing.isEmpty()) marker.put("missing", listed(missing));
            marker.put("resumedRecords", resumed);
            boolean written = progressStore.write(MARKER, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(marker), null);
            Events.success(Events.EVENT, "platform-review.import").addKeyValue("count", reviews + requests).addKeyValue("reviews", reviews)
                    .addKeyValue("requests", requests).addKeyValue("images", images).addKeyValue("days", days.size())
                    .addKeyValue("resumed", resumed).addKeyValue("missing", missing.size())
                    .log("Legacy reviews imported: {} review(s) and {} request(s) from {} day file(s) with {} image(s); {} record(s) were already in place from an earlier attempt, {} legacy image(s) do not exist; marker {}, legacy objects kept",
                            reviews, requests, days.size(), images, resumed, missing.size(), written ? "written" : "already written by another task");
            return Optional.of(counts);
        } catch (AccountFailure | UncheckedIOException failure) {
            Events.failed(Events.EVENT, "platform-review.import", "IMPORT_FAILED").addKeyValue("error.type", Events.types(failure))
                    .log("Legacy review import did not complete ({}); the progress so far is kept and the import resumes at the next start while no marker is written", Events.types(failure));
            return Optional.empty();
        }
    }

    /**
     * The item's fixed destination: the existing progress object, or a new one created first-wins with a fresh id. A
     * number drawn and then lost (the progress write fails, or another task won) is a gap in the sequence, never a
     * duplicate. A progress object that does not describe this item is a defect, answered as a failure.
     */
    private Progress destination(PlatformReviewObjects progressStore, String day, StoredItem item, String now) {
        String key = progressKey(day, item.id());
        Progress progress = RecordStore.storage(() -> progressStore.read(key)).map(stored -> parseProgress(key, stored.bytes())).orElse(null);
        if (progress == null) {
            RecordStore store = item.request() ? stores.requests() : stores.reviews();
            var fresh = new Progress(PROGRESS_SCHEMA, day, item.id(), item.kind(), store.nextId(), item.createdAt(), now);
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(fresh);
            if (RecordStore.storage(() -> progressStore.write(key, bytes, null))) progress = fresh;
            else progress = RecordStore.storage(() -> progressStore.read(key)).map(stored -> parseProgress(key, stored.bytes())).orElseThrow(RecordStore::unavailable);
        }
        if (!Objects.equals(progress.itemId(), item.id()) || !Objects.equals(progress.day(), day) || !Objects.equals(progress.kind(), item.kind())
                || !Objects.equals(progress.createdAt(), item.createdAt()) || progress.recordId() == null)
            throw new AccountFailure(503, "IMPORT_PROGRESS_MISMATCH", "Import progress " + key + " does not describe legacy item " + day + "/" + item.id());
        return progress;
    }

    /** {@code .import-progress/<day>/<item>.json}; an item id outside the key alphabet is named by its SHA-256. */
    static String progressKey(String day, String itemId) {
        String name = itemId != null && itemId.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}") && !itemId.equals("..") ? itemId : sha256(itemId);
        return PROGRESS + day.substring(0, day.length() - ".json".length()) + "/" + name + ".json";
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Progress parseProgress(String key, byte[] bytes) {
        try {
            var progress = mapper.readValue(bytes, Progress.class);
            if (!PROGRESS_SCHEMA.equals(progress.schema())) throw new AccountFailure(503, "IMPORT_PROGRESS_UNREADABLE", "Import progress " + key + " is not a progress object");
            return progress;
        } catch (JacksonException broken) {
            throw new AccountFailure(503, "IMPORT_PROGRESS_UNREADABLE", "Import progress " + key + " is not valid JSON");
        }
    }

    /** A record's image that is no longer in the store is copied again from the legacy object with the same key. */
    private void copy(RecordStore store, List<StoredAttachment> legacyAttachments, String attachmentId, String path) {
        for (StoredAttachment attachment : legacyAttachments) {
            if (!Objects.equals(attachment.id(), attachmentId)) continue;
            var bytes = legacy.read(attachment.path());
            if (bytes.isPresent()) store.putAttachment(path, bytes.get().bytes());
            return;
        }
    }

    private static void note(List<String> missing, String path) { missing.add(path); }

    /** The first legacy image paths that do not exist, for the marker; a long list ends with "...". */
    static List<String> listed(List<String> missing) {
        if (missing.size() <= MISSING_LISTED) return List.copyOf(missing);
        var head = new ArrayList<>(missing.subList(0, MISSING_LISTED)); head.add("..."); return List.copyOf(head);
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
