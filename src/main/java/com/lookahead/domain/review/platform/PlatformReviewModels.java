package com.lookahead.domain.review.platform;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DLV-928 shapes. DLV-971 (owner, 2026-11-06): one JSON record per item in the assets bucket, reviews under
 * {@code reviews/records/YYYY/MM/DD/REV-000001.json} and learner support requests under
 * {@code support-requests/records/YYYY/MM/DD/SUP-000001.json}, attachments beside them under
 * {@code attachments/<ID>/NN-name.ext}. API records never expose storage keys or account IDs. The day-file shapes
 * ({@link DayFile}, {@link StoredItem}) remain for the one-time import of the legacy {@code platform-reviews/} prefix.
 */
public final class PlatformReviewModels {
    private PlatformReviewModels() {}

    public static final String DAY_SCHEMA = "lookahead.platform-review-day/v1";
    public static final String RECORD_SCHEMA = "lookahead.review-record/v1";
    public static final Set<String> CATEGORIES = Set.of("problem", "change", "idea", "content");
    public static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    /** {@code closed}: a support request nobody updated for seven days, or one the owner or an admin closed by hand. */
    public static final Set<String> STATUSES = Set.of("new", "accepted", "planned", "done", "declined", "closed");
    /**
     * An item is a review written on Write Review ({@code kind} "review") or a learner's support request ({@code kind}
     * "request", {@code requestType} the support form's type, {@code reference} the support receipt reference).
     */
    public static final String KIND_REVIEW = "review", KIND_REQUEST = "request";
    public static final Set<String> REQUEST_TYPES = Set.of("problem", "feedback", "question");
    /** Mirrors `reviewedPages` in the web app's platform-review.model.ts. */
    public static final Set<String> PAGES = Set.of("Home", "Learn Catalog", "Grow Catalog", "Blaze Catalog",
            "Course Overview", "Lesson Reader", "Question & Answer Reader", "Hands-on DSA Catalog",
            "Coding Problem Workspace", "Search", "Create Study Plan", "Study Desk", "Sign In", "Sign Up",
            "Manage Account", "Support", "Admin pages", "Whole platform", "Other");
    /** DLV-931: the Look Ahead catalog is now Blaze. Old tabs and saved day files may still use this name. */
    public static final String LEGACY_BLAZE_PAGE = "Look Ahead Catalog";

    /** Maps the retired page name to its current one; every other value is returned unchanged. */
    public static String currentPage(String page) {
        return LEGACY_BLAZE_PAGE.equals(page) ? "Blaze Catalog" : page;
    }

    // ---- Stored in <store>/records/YYYY/MM/DD/<ID>.json (the README record shape plus the write-control fields)
    public record StoredAttachment(String id, String name, String contentType, long size, String sha256, String path) {}
    public record Submitter(UUID accountId, String role, String displayName) {}
    public record StoredRecord(String schema, String id, String kind, String requestType, String status, Submitter submitter,
                               String category, String title, String details, String page, String pageUrl, String priority,
                               List<StoredAttachment> attachments, String reference, String ticketId, String ownerNote,
                               String createdAt, String updatedAt, String closedAt, String closedBy,
                               UUID createKey, String createHash, UUID lastWriteKey) {
        public boolean request() { return KIND_REQUEST.equals(kind); }
        public UUID accountId() { return submitter.accountId(); }
        /** The same record with the fields a reviewer edits replaced. */
        public StoredRecord withFields(Fields fields, List<StoredAttachment> attachments, UUID lastWriteKey, String updatedAt) {
            return new StoredRecord(schema, id, kind, requestType, status, submitter, fields.category(), fields.title(), fields.details(),
                    fields.page(), fields.pageUrl(), fields.priority(), attachments, reference, ticketId, ownerNote, createdAt, updatedAt,
                    closedAt, closedBy, createKey, createHash, lastWriteKey);
        }
        /** A status change; {@code closedBy} is the closer's role or "system" when the new status is closed, else cleared. */
        public StoredRecord withStatus(String status, String ownerNote, String updatedAt, String closedBy) {
            boolean closing = "closed".equals(status);
            return new StoredRecord(schema, id, kind, requestType, status, submitter, category, title, details, page, pageUrl, priority,
                    attachments, reference, ticketId, ownerNote, createdAt, updatedAt, closing ? updatedAt : null, closing ? closedBy : null,
                    createKey, createHash, lastWriteKey);
        }
        public StoredRecord withTicket(String ticketId, String updatedAt) {
            return new StoredRecord(schema, id, kind, requestType, status, submitter, category, title, details, page, pageUrl, priority,
                    attachments, reference, ticketId, ownerNote, createdAt, updatedAt, closedAt, closedBy, createKey, createHash, lastWriteKey);
        }
        public StoredRecord withSubmitterName(String displayName) {
            return new StoredRecord(schema, id, kind, requestType, status, new Submitter(submitter.accountId(), submitter.role(), displayName),
                    category, title, details, page, pageUrl, priority, attachments, reference, ticketId, ownerNote, createdAt, updatedAt,
                    closedAt, closedBy, createKey, createHash, lastWriteKey);
        }
    }

    // ---- Legacy day files, platform-reviews/reviews/{day}.json (read by the import only)
    public record StoredItem(String id, UUID reviewerAccountId, String reviewerId, String reviewerName,
                             String category, String page, String pageUrl, String title, String details,
                             String priority, List<StoredAttachment> attachments, String status, String ownerNote,
                             String createdAt, String updatedAt, UUID createKey, String createHash, UUID lastWriteKey, String submitterRole,
                             String kind, String requestType, String reference) {
        public boolean request() { return KIND_REQUEST.equals(kind); }
        /** Read-time repair of a day file saved by an older version: the renamed page, the missing kind. */
        public StoredItem repaired() {
            if (!LEGACY_BLAZE_PAGE.equals(page) && kind != null) return this;
            return new StoredItem(id, reviewerAccountId, reviewerId, reviewerName, category, currentPage(page), pageUrl, title, details, priority,
                    attachments, status, ownerNote, createdAt, updatedAt, createKey, createHash, lastWriteKey, submitterRole,
                    kind == null ? KIND_REVIEW : kind, requestType, reference);
        }
    }
    public record DayFile(String schema, String day, List<StoredItem> items) {}

    // ---- API (the Web's shape; DLV-971 adds ticketId and ticketStatus)
    public record Viewer(String role, String reviewerId, String displayName) {}
    public record Attachment(String id, String name, String contentType, long size) {}
    public record Item(String id, String reviewerId, String reviewerName, String category, String page, String pageUrl,
                       String title, String details, String priority, List<Attachment> attachments, String status,
                       String ownerNote, String createdAt, String updatedAt, String submitterRole,
                       String kind, String requestType, String reference, String ticketId, String ticketStatus,
                       String closedAt, String closedBy) {}
    public record Summary(String id, String reviewerId, String reviewerName, String category, String page,
                          String title, String priority, String status, int attachmentCount, boolean ownerNote,
                          String createdAt, String updatedAt, String submitterRole,
                          String kind, String requestType, String reference, String ticketId, String ticketStatus) {}
    public record Page(List<Summary> items, String nextBefore) {}
    /** Answer of {@code POST /{id}/promote}: the new ticket's id and the work item as added to the plan. */
    public record Promoted(String ticketId, tools.jackson.databind.JsonNode item) {}

    // ---- Requests
    public record Fields(String category, String page, String pageUrl, String title, String details, String priority) {}
    public record Upload(String name, String declaredType, byte[] bytes) {}
    public record Image(String contentType, String extension, byte[] bytes) {}
    public record Content(String contentType, byte[] bytes) {}
}
