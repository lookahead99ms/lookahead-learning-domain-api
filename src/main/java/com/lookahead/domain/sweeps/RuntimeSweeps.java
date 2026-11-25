package com.lookahead.domain.sweeps;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.domain.delivery.ReleasePlanImport;
import com.lookahead.domain.review.platform.PlatformReviewService;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DLV-971 (owner, 2026-11-06): the one scheduled component. At application start (before the task takes traffic)
 * the published delivery plan is seeded or merged (DLV-998), then the three sweeps: support requests with no update for seven days are closed,
 * the delivery inbox is merged into the plan, and the 48-hour admission cleanup runs (cloud only; it was hourly). The
 * sweeps repeat every 4 hours while the task runs (scheduling is enabled in cloud mode only; a local Domain sweeps at
 * each start). Each sweep is independent: a failure in one is logged and the next still runs. The owner can also run
 * one job, or all three, at once ({@link #runNow}, {@code POST /api/v1/sweeps/run}) instead of waiting for the cadence.
 * DLV-970: a fourth sweep, {@code maintenance}, runs every job registered with {@link MaintenanceJobs} (the study-plan
 * document cleanups, the erasure queue, the sign-in records), each in bounded pages, each guarded.
 */
@Component
@Profile("accounts")
public class RuntimeSweeps {
    public static final String CADENCE = "PT4H";
    /** Pages one maintenance job may run in one sweep; what is left waits for the next sweep. */
    public static final int MAX_PAGES = 40;
    private final PlatformReviewService reviews;
    private final DeliveryPlanService plans;
    private final ReleasePlanImport releaseImport;
    private final ObjectProvider<CloudAdmissionCleanup> admissions;
    private final MaintenanceJobs maintenance;
    private final Clock clock;
    /** DLV-971: only the support-request close runs in prod; every other job is DEV only (owner, 2026-11-24). */
    private final boolean production;

    /** The jobs the owner can run, by the name the request carries. */
    public enum Job {
        SUPPORT_REQUESTS("support-requests"), DELIVERY_INBOX("delivery-inbox"), ADMISSIONS("admissions"), MAINTENANCE("maintenance"), ALL("all");
        public final String id;
        Job(String id) { this.id = id; }
        boolean includes(Job job) { return this == ALL || this == job; }
        /** The job with this request name, or {@code null} for anything else. */
        public static Job named(String id) {
            for (Job job : values()) if (job.id.equals(id)) return job;
            return null;
        }
    }

    /**
     * What one run did: the job, when it ran (ISO instant), and the requested job's counts (the others stay {@code null}):
     * how many support requests were closed; whether the delivery inbox was merged and the plan version it wrote (or the
     * reason the inbox was left alone); how many waiting accounts the admission cleanup removed ({@code null} outside
     * cloud mode, where there is no cleanup). A sweep that did not complete counts 0 (the inbox: the reason names the
     * failure); its log line carries the exception type. DLV-970: {@code maintenance} lists what each registered
     * maintenance job did (empty when the job was not requested or nothing is registered).
     */
    public record Result(String job, String ranAt, Integer supportRequestsClosed, Boolean inboxMerged, String inboxResult, Integer admissionsRemoved,
            List<Maintenance> maintenance) {
        public Result { maintenance = maintenance == null ? List.of() : List.copyOf(maintenance); }
        public Result(String job, String ranAt, Integer supportRequestsClosed, Boolean inboxMerged, String inboxResult, Integer admissionsRemoved) {
            this(job, ranAt, supportRequestsClosed, inboxMerged, inboxResult, admissionsRemoved, List.of());
        }
    }

    /** One maintenance job's sweep: pages run, items cleaned/retained/failed, whether work is left for the next sweep, and the failure type if it stopped. */
    public record Maintenance(String job, int pages, int cleaned, int retained, int failed, boolean more, String error) {}

    @Autowired
    public RuntimeSweeps(PlatformReviewService reviews, DeliveryPlanService plans, ReleasePlanImport releaseImport,
            ObjectProvider<CloudAdmissionCleanup> admissions, MaintenanceJobs maintenance,
            @Value("${app.deployment-environment:local}") String environment) {
        this(reviews, plans, releaseImport, admissions, maintenance, Clock.systemUTC(), environment);
    }

    public RuntimeSweeps(PlatformReviewService reviews, DeliveryPlanService plans, ReleasePlanImport releaseImport,
            ObjectProvider<CloudAdmissionCleanup> admissions) {
        this(reviews, plans, releaseImport, admissions, new RegisteredMaintenanceJobs(), Clock.systemUTC());
    }

    public RuntimeSweeps(PlatformReviewService reviews, DeliveryPlanService plans, ReleasePlanImport releaseImport,
            ObjectProvider<CloudAdmissionCleanup> admissions, Clock clock) {
        this(reviews, plans, releaseImport, admissions, new RegisteredMaintenanceJobs(), clock);
    }

    public RuntimeSweeps(PlatformReviewService reviews, DeliveryPlanService plans, ReleasePlanImport releaseImport,
            ObjectProvider<CloudAdmissionCleanup> admissions, MaintenanceJobs maintenance, Clock clock) {
        this(reviews, plans, releaseImport, admissions, maintenance, clock, "local");
    }

    public RuntimeSweeps(PlatformReviewService reviews, DeliveryPlanService plans, ReleasePlanImport releaseImport,
            ObjectProvider<CloudAdmissionCleanup> admissions, MaintenanceJobs maintenance, Clock clock, String environment) {
        this.reviews = reviews; this.plans = plans; this.releaseImport = releaseImport; this.admissions = admissions;
        this.maintenance = maintenance == null ? new RegisteredMaintenanceJobs() : maintenance; this.clock = clock;
        this.production = "prod".equals(environment);
    }

    /** DLV-971: in prod only the support-request close runs (at start, on the schedule and by hand); the rest are DEV only. */
    private boolean runsHere(Job job) {
        return !production || job == Job.SUPPORT_REQUESTS;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        if (releaseImport != null && !production) guarded("release plan import", releaseImport::run, failure -> null);
        sweep(Job.ALL);
    }

    @Scheduled(fixedDelayString = CADENCE, initialDelayString = CADENCE)
    public void run() {
        sweep(Job.ALL);
    }

    /** The requested sweeps in order, each guarded, and what they did. Shared by the start, the schedule and the owner's manual run. */
    public Result sweep(Job job) {
        String ranAt = clock.instant().toString();
        Integer closed = null; Boolean merged = null; String inboxResult = null; Integer removed = null;
        if (job.includes(Job.SUPPORT_REQUESTS)) closed = guarded("support request close", reviews::closeStaleRequests, failure -> 0);
        if (job.includes(Job.DELIVERY_INBOX) && runsHere(Job.DELIVERY_INBOX)) {
            DeliveryPlanService.Sync sync = guarded("delivery inbox sync", plans::syncInbox,
                    failure -> new DeliveryPlanService.Sync(false, null, 0, "did not complete (" + Events.types(failure) + ")"));
            merged = sync.synced();
            inboxResult = sync.synced() ? Long.toString(sync.version()) : sync.reason();
        }
        if (job.includes(Job.ADMISSIONS) && runsHere(Job.ADMISSIONS)) {
            CloudAdmissionCleanup cleanup = admissions.getIfAvailable();
            if (cleanup != null) removed = cleanup.run();
        }
        var maintained = new java.util.ArrayList<Maintenance>();
        if (job.includes(Job.MAINTENANCE) && runsHere(Job.MAINTENANCE)) for (MaintenanceJobs.Job registered : maintenance.jobs()) maintained.add(maintain(registered));
        return new Result(job.id, ranAt, closed, merged, inboxResult, removed, maintained);
    }

    /** One job's pages, at most {@link #MAX_PAGES}; a failure ends this job's sweep and is logged like any other sweep failure. */
    Maintenance maintain(MaintenanceJobs.Job job) {
        int pages = 0, cleaned = 0, retained = 0, failed = 0;
        boolean more = false;
        String error = null;
        try {
            do {
                MaintenanceJobs.Page page = job.run(pages > 0);
                pages++;
                cleaned += page.cleaned(); retained += page.retained(); failed += page.failed();
                more = page.more();
            } while (more && pages < MAX_PAGES);
        } catch (RuntimeException failure) {
            error = Events.types(failure);
            more = true;
            Events.failed(Events.EVENT, "sweep", "UNEXPECTED").addKeyValue("sweep", "maintenance " + job.name()).addKeyValue("error.type", error)
                    .log("The maintenance {} sweep did not complete ({}); the next run tries again", job.name(), error);
        }
        return new Maintenance(job.name(), pages, cleaned, retained, failed, more, error);
    }

    /**
     * The owner's "run this job now": the requested sweep(s) at once, and one {@code sweep} line naming the actor, the job
     * and the counts. {@code admissions} alone answers {@code 409 SWEEP_NOT_AVAILABLE} where there is no cleanup (local mode).
     */
    public Result runNow(UUID actorId, Job job) {
        if (job == Job.ADMISSIONS && admissions.getIfAvailable() == null)
            throw new AccountFailure(409, "SWEEP_NOT_AVAILABLE", "The admission cleanup runs only in cloud mode; this Domain has none.");
        Result result = sweep(job);
        Events.success(Events.EVENT, "sweep").addKeyValue("manual", true).addKeyValue("account.id", actorId).addKeyValue("job", job.id)
                .addKeyValue("support.closed", result.supportRequestsClosed()).addKeyValue("inbox.merged", result.inboxMerged())
                .addKeyValue("admissions.removed", result.admissionsRemoved()).addKeyValue("maintenance.jobs", result.maintenance().size())
                .log("{} ran the {} sweep now: {} support request(s) closed, inbox {}, {} waiting account(s) removed{}", Events.actor("Owner", actorId), job.id,
                        result.supportRequestsClosed() == null ? "no" : result.supportRequestsClosed(),
                        result.inboxMerged() == null ? "not touched" : result.inboxMerged() ? "merged into plan version " + result.inboxResult() : result.inboxResult(),
                        result.admissionsRemoved() == null ? "no" : result.admissionsRemoved(), maintenanceSummary(result.maintenance()));
        return result;
    }

    /** ", maintenance: <job> <cleaned> cleaned/<retained> retained/<failed> failed[ (more)|, stopped by <type>], …"; empty when nothing ran. */
    static String maintenanceSummary(List<Maintenance> maintained) {
        if (maintained.isEmpty()) return "";
        var text = new StringBuilder(", maintenance:");
        for (int at = 0; at < maintained.size(); at++) {
            Maintenance item = maintained.get(at);
            text.append(at == 0 ? " " : "; ").append(item.job()).append(' ').append(item.cleaned()).append(" cleaned/").append(item.retained())
                    .append(" retained/").append(item.failed()).append(" failed");
            if (item.error() != null) text.append(", stopped by ").append(item.error());
            else if (item.more()) text.append(" (more)");
        }
        return text.toString();
    }

    private static <T> T guarded(String sweep, Supplier<T> step, Function<RuntimeException, T> fallback) {
        try { return step.get(); }
        catch (RuntimeException failure) {
            Events.failed(Events.EVENT, "sweep", "UNEXPECTED").addKeyValue("sweep", sweep).addKeyValue("error.type", Events.types(failure))
                    .log("The {} sweep did not complete ({}); the next run tries again", sweep, Events.types(failure));
            return fallback.apply(failure);
        }
    }
}
