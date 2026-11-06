package com.lookahead.domain.review.platform;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** DLV-928 shapes. Stored records live in day files; API records never expose storage keys or account IDs. */
public final class PlatformReviewModels {
    private PlatformReviewModels() {}

    public static final String DAY_SCHEMA = "lookahead.platform-review-day/v1";
    public static final Set<String> CATEGORIES = Set.of("problem", "change", "idea", "content");
    public static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    public static final Set<String> STATUSES = Set.of("new", "accepted", "planned", "done", "declined");
    /**
     * DLV-971 (owner, 2026-11-06): an item is a review written on Write Review ({@code kind} "review") or a learner's
     * support request recorded next to the reviews ({@code kind} "request", {@code requestType} the support form's type,
     * {@code reference} the support receipt reference). Day files written before this read as reviews.
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

    // Stored in reviews/{day}.json
    public record StoredAttachment(String id, String name, String contentType, long size, String sha256, String path) {}
    public record StoredItem(String id, UUID reviewerAccountId, String reviewerId, String reviewerName,
                             String category, String page, String pageUrl, String title, String details,
                             String priority, List<StoredAttachment> attachments, String status, String ownerNote,
                             String createdAt, String updatedAt, UUID createKey, String createHash, UUID lastWriteKey, String submitterRole,
                             String kind, String requestType, String reference) {
        public boolean request() { return KIND_REQUEST.equals(kind); }
        /** The same item with the fields a reviewer edits replaced. */
        public StoredItem withFields(Fields fields, List<StoredAttachment> attachments, UUID lastWriteKey, String updatedAt) {
            return new StoredItem(id, reviewerAccountId, reviewerId, reviewerName, fields.category(), fields.page(), fields.pageUrl(),
                    fields.title(), fields.details(), fields.priority(), attachments, status, ownerNote, createdAt, updatedAt,
                    createKey, createHash, lastWriteKey, submitterRole, kind, requestType, reference);
        }
        public StoredItem withStatus(String status, String ownerNote, String updatedAt) {
            return new StoredItem(id, reviewerAccountId, reviewerId, reviewerName, category, page, pageUrl, title, details, priority,
                    attachments, status, ownerNote, createdAt, updatedAt, createKey, createHash, lastWriteKey, submitterRole, kind, requestType, reference);
        }
        public StoredItem withReviewerName(String reviewerName) {
            return new StoredItem(id, reviewerAccountId, reviewerId, reviewerName, category, page, pageUrl, title, details, priority,
                    attachments, status, ownerNote, createdAt, updatedAt, createKey, createHash, lastWriteKey, submitterRole, kind, requestType, reference);
        }
        /** Read-time repair of a day file saved by an older version: the renamed page, the missing kind. */
        public StoredItem repaired() {
            if (!LEGACY_BLAZE_PAGE.equals(page) && kind != null) return this;
            return new StoredItem(id, reviewerAccountId, reviewerId, reviewerName, category, currentPage(page), pageUrl, title, details, priority,
                    attachments, status, ownerNote, createdAt, updatedAt, createKey, createHash, lastWriteKey, submitterRole,
                    kind == null ? KIND_REVIEW : kind, requestType, reference);
        }
    }
    public record DayFile(String schema, String day, List<StoredItem> items) {}

    // API
    public record Viewer(String role, String reviewerId, String displayName) {}
    public record Attachment(String id, String name, String contentType, long size) {}
    public record Item(String id, String reviewerId, String reviewerName, String category, String page, String pageUrl,
                       String title, String details, String priority, List<Attachment> attachments, String status,
                       String ownerNote, String createdAt, String updatedAt, String submitterRole,
                       String kind, String requestType, String reference) {}
    public record Summary(String id, String reviewerId, String reviewerName, String category, String page,
                          String title, String priority, String status, int attachmentCount, boolean ownerNote,
                          String createdAt, String updatedAt, String submitterRole,
                          String kind, String requestType, String reference) {}
    public record Page(List<Summary> items, String nextBefore) {}

    // Requests
    public record Fields(String category, String page, String pageUrl, String title, String details, String priority) {}
    public record Upload(String name, String declaredType, byte[] bytes) {}
    public record Image(String contentType, String extension, byte[] bytes) {}
    public record Content(String contentType, byte[] bytes) {}
}
