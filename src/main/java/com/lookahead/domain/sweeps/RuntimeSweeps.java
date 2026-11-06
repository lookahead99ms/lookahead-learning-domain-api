package com.lookahead.domain.sweeps;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.domain.review.platform.LegacyReviewImport;
import com.lookahead.domain.review.platform.PlatformReviewService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DLV-971 (owner, 2026-11-06): the one scheduled component. At application start (before the task takes traffic) the
 * legacy review import runs once, then the three sweeps: support requests with no update for seven days are closed,
 * the delivery inbox is merged into the plan, and the 48-hour admission cleanup runs (cloud only; it was hourly). The
 * sweeps repeat every 4 hours while the task runs (scheduling is enabled in cloud mode only; a local Domain sweeps at
 * each start). Each sweep is independent: a failure in one is logged and the next still runs.
 */
@Component
@Profile("accounts")
public class RuntimeSweeps {
    public static final String CADENCE = "PT4H";
    private final PlatformReviewService reviews;
    private final DeliveryPlanService plans;
    private final LegacyReviewImport legacyImport;
    private final ObjectProvider<CloudAdmissionCleanup> admissions;

    public RuntimeSweeps(PlatformReviewService reviews, DeliveryPlanService plans, LegacyReviewImport legacyImport, ObjectProvider<CloudAdmissionCleanup> admissions) {
        this.reviews = reviews; this.plans = plans; this.legacyImport = legacyImport; this.admissions = admissions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        guarded("legacy import", () -> { legacyImport.run(); });
        run();
    }

    @Scheduled(fixedDelayString = CADENCE, initialDelayString = CADENCE)
    public void run() {
        guarded("support request close", () -> { reviews.closeStaleRequests(); });
        guarded("delivery inbox sync", () -> { plans.syncInbox(); });
        CloudAdmissionCleanup cleanup = admissions.getIfAvailable();
        if (cleanup != null) cleanup.run();
    }

    private static void guarded(String sweep, Runnable step) {
        try { step.run(); }
        catch (RuntimeException failure) {
            Events.failed(Events.EVENT, "sweep", "UNEXPECTED").addKeyValue("sweep", sweep).addKeyValue("error.type", Events.types(failure))
                    .log("The {} sweep did not complete ({}); the next run tries again", sweep, Events.types(failure));
        }
    }
}
