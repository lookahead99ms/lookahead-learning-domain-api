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
                             String createdAt, String updatedAt, UUID createKey, String createHash, UUID lastWriteKey, String submitterRole) {}
    public record DayFile(String schema, String day, List<StoredItem> items) {}

    // API
    public record Viewer(String role, String reviewerId, String displayName) {}
    public record Attachment(String id, String name, String contentType, long size) {}
    public record Item(String id, String reviewerId, String reviewerName, String category, String page, String pageUrl,
                       String title, String details, String priority, List<Attachment> attachments, String status,
                       String ownerNote, String createdAt, String updatedAt, String submitterRole) {}
    public record Summary(String id, String reviewerId, String reviewerName, String category, String page,
                          String title, String priority, String status, int attachmentCount, boolean ownerNote,
                          String createdAt, String updatedAt, String submitterRole) {}
    public record Page(List<Summary> items, String nextBefore) {}

    // Requests
    public record Fields(String category, String page, String pageUrl, String title, String details, String priority) {}
    public record Upload(String name, String declaredType, byte[] bytes) {}
    public record Image(String contentType, String extension, byte[] bytes) {}
    public record Content(String contentType, byte[] bytes) {}
}
