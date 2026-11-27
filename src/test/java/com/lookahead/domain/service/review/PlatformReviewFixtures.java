package com.lookahead.domain.service.review;

import com.lookahead.domain.db.store.review.PlatformReviewRecordNumberMemoryStore;
import com.lookahead.domain.db.store.review.PlatformReviewStatusHistoryMemoryStore;
import com.lookahead.domain.model.PlatformReviewModels;
import com.lookahead.domain.db.store.review.PlatformReviewFileSystemObjectStore;
import com.lookahead.domain.db.store.review.PlatformReviewRecordStore;
import com.lookahead.domain.db.store.review.PlatformReviewStatusHistory;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.delivery.DeliveryPlanService;
import com.lookahead.domain.db.store.delivery.DeliveryPlanStore;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.validator.DeliveryPlanFileValidator;
import com.lookahead.domain.validator.DeliveryPlanItemValidator;
import com.lookahead.domain.validator.DeliveryPlanRequestValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
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
        var numbers = new PlatformReviewRecordNumberMemoryStore();
        return new PlatformReviewService.Stores(
                new PlatformReviewRecordStore(new PlatformReviewFileSystemObjectStore(root.resolve("reviews")), PlatformReviewModels.KIND_REVIEW, mapper, numbers),
                new PlatformReviewRecordStore(new PlatformReviewFileSystemObjectStore(root.resolve("support-requests")), PlatformReviewModels.KIND_REQUEST, mapper, numbers));
    }

    public static PlatformReviewService service(Path root, AuthorPreviewLocalService author, ObjectMapper mapper, String reviewers) {
        return service(root, author, mapper, reviewers, Clock.systemUTC(), new PlatformReviewStatusHistoryMemoryStore());
    }

    public static PlatformReviewService service(Path root, AuthorPreviewLocalService author, ObjectMapper mapper, String reviewers, Clock clock, PlatformReviewStatusHistory history) {
        return service(root, author, mapper, reviewers, clock, history, new MockEnvironment());
    }

    /** With the environment the service reads its DEV-only gate from. */
    public static PlatformReviewService service(Path root, AuthorPreviewLocalService author, ObjectMapper mapper, String reviewers, Clock clock, PlatformReviewStatusHistory history,
            Environment environment) {
        return new PlatformReviewService(stores(root, mapper), author, mapper, reviewers, clock, history, environment);
    }

    public static DeliveryPlanStore planStore(Path root, ObjectMapper mapper) {
        return new DeliveryPlanStore(new PlatformReviewFileSystemObjectStore(root.resolve("delivery")), mapper);
    }

    /** A delivery plan service outside prod: its write requests pass the DEV-only gate. */
    public static DeliveryPlanService delivery(Path root, ObjectMapper mapper, StaffAccessService access, PlatformReviewStatusHistory history, Clock clock, String environment) {
        return delivery(root, mapper, access, history, clock, environment, new MockEnvironment());
    }

    /** {@code settings} answers the DEV-only gate ({@code app.deployment-environment}); {@code environment} is the stamp a ticket gets. */
    public static DeliveryPlanService delivery(Path root, ObjectMapper mapper, StaffAccessService access, PlatformReviewStatusHistory history, Clock clock, String environment,
            Environment settings) {
        return new DeliveryPlanService(planStore(root, mapper), new DeliveryPlanFileValidator(mapper), access, history, environment, clock,
                new DeliveryPlanItemValidator(), settings, new DeliveryPlanRequestValidator());
    }

    /** Writes the miniature plan as delivery/plan.json. */
    public static Path seedPlan(Path root) throws IOException {
        Path plan = root.resolve("delivery/plan.json");
        Files.createDirectories(plan.getParent());
        return Files.writeString(plan, PLAN);
    }
}
