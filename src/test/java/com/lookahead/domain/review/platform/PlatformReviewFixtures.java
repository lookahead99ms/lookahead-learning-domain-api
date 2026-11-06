package com.lookahead.domain.review.platform;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.domain.delivery.DeliveryPlanStore;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

/** DLV-971: a PlatformReviewService on a Local directory with the same roots as the Local runtime (reviews/, support-requests/, delivery/). */
public final class PlatformReviewFixtures {
    private PlatformReviewFixtures() {}

    /** The Content plan shape in miniature: a five-step workflow, four priorities, one stage and one ticket DLV-1. */
    public static final String PLAN = """
            {
              "schemaVersion": "delivery-plan/v1",
              "version": 7,
              "id": "look-ahead-platform-delivery",
              "title": "Build with discipline.",
              "currentStageId": "stage-1",
              "workflow": [
                {"id": "backlog", "label": "Backlog"}, {"id": "ready", "label": "Ready"}, {"id": "in-progress", "label": "In progress"},
                {"id": "review", "label": "Review"}, {"id": "done", "label": "Done"}
              ],
              "priorities": [{"id": "P0"}, {"id": "P1"}, {"id": "P2"}, {"id": "P3"}],
              "stages": [{"id": "stage-1", "order": 1, "title": "Baseline"}],
              "workItems": [
                {"id": "DLV-1", "title": "First ticket", "summary": "Seeded", "type": "story", "stageId": "stage-1", "statusId": "backlog",
                 "priorityId": "P2", "labels": [], "acceptanceCriteria": [], "dependencies": [], "blockedBy": [], "notes": "", "evidence": [],
                 "updatedAt": "2026-11-01T00:00:00Z", "createdIn": "local", "updatedIn": "local"}
              ]
            }
            """;

    public static PlatformReviewService.Stores stores(Path root, ObjectMapper mapper) {
        var numbers = new ReviewRecordNumbers.InMemory();
        return new PlatformReviewService.Stores(
                new RecordStore(new FileSystemPlatformReviewObjects(root.resolve("reviews")), PlatformReviewModels.KIND_REVIEW, mapper, numbers),
                new RecordStore(new FileSystemPlatformReviewObjects(root.resolve("support-requests")), PlatformReviewModels.KIND_REQUEST, mapper, numbers));
    }

    public static PlatformReviewService service(Path root, LocalAuthorAccess author, ObjectMapper mapper, String reviewers) {
        return service(root, author, mapper, reviewers, Clock.systemUTC(), new ReviewStatusHistory.InMemory());
    }

    public static PlatformReviewService service(Path root, LocalAuthorAccess author, ObjectMapper mapper, String reviewers, Clock clock, ReviewStatusHistory history) {
        return new PlatformReviewService(stores(root, mapper), author, mapper, reviewers, clock, history);
    }

    public static DeliveryPlanStore planStore(Path root, ObjectMapper mapper) {
        return new DeliveryPlanStore(new FileSystemPlatformReviewObjects(root.resolve("delivery")), mapper);
    }

    public static DeliveryPlanService delivery(Path root, ObjectMapper mapper, StaffAccessService access, ReviewStatusHistory history, Clock clock, String environment) {
        return new DeliveryPlanService(planStore(root, mapper), access, history, environment, clock);
    }

    /** Writes the miniature plan as delivery/plan.json. */
    public static Path seedPlan(Path root) throws java.io.IOException {
        Path plan = root.resolve("delivery/plan.json");
        Files.createDirectories(plan.getParent());
        return Files.writeString(plan, PLAN);
    }
}
