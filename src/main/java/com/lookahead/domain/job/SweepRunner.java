package com.lookahead.domain.job;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.delivery.DeliveryPlanService;
import com.lookahead.domain.service.review.PlatformReviewService;
import com.lookahead.domain.util.DevOnly;
import com.lookahead.domain.validator.SweepRequestValidator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DLV-971 (owner, 2026-11-06): the one scheduled component. At application start (before the task
 * takes traffic) the published delivery plan is seeded or merged (DLV-998), then the three sweeps:
 * support requests with no update for seven days are closed, the delivery inbox is merged into the
 * plan, and the 48-hour admission cleanup runs (cloud only; it was hourly). The sweeps repeat every
 * 4 hours while the task runs (scheduling is enabled in cloud mode only; a local Domain sweeps at
 * each start). Each sweep is independent: a failure in one is logged and the next still runs. The
 * owner can also run one job, or all three, at once ({@link #runNow}, {@code POST
 * /api/v1/sweeps/run}) instead of waiting for the cadence. DLV-970: a fourth sweep, {@code
 * maintenance}, runs every job registered with {@link MaintenanceJobRegistry} (the study-plan
 * document cleanups, the erasure queue, the sign-in records), each in bounded pages, each guarded.
 */
@Component
@Profile("accounts")
public class SweepRunner {
  public static final String CADENCE = "PT4H";

  /** Pages one maintenance job may run in one sweep; what is left waits for the next sweep. */
  public static final int MAX_PAGES = 40;

  private final PlatformReviewService reviews;
  private final DeliveryPlanService plans;
  private final DeliveryPlanReleaseImportJob releaseImport;
  private final ObjectProvider<DevAccessCleanupJob> devAccessCleanup;
  private final MaintenanceJobRegistry maintenance;
  private final Clock clock;

  /**
   * DLV-971: only the support-request close runs in prod; every other job is DEV only (owner,
   * 2026-11-24).
   */
  private final boolean production;

  private final Environment settings;
  private final SweepRequestValidator shapes;

  /**
   * What one run did: the job, when it ran (ISO instant), and the requested job's counts (the
   * others stay {@code null}): how many support requests were closed; whether the delivery inbox
   * was merged and the plan version it wrote (or the reason the inbox was left alone); how many
   * waiting accounts the admission cleanup removed ({@code null} outside cloud mode, where there is
   * no cleanup). A sweep that did not complete counts 0 (the inbox: the reason names the failure);
   * its log line carries the exception type. DLV-970: {@code maintenance} lists what each
   * registered maintenance job did (empty when the job was not requested or nothing is registered).
   */
  public record Result(
      String job,
      String ranAt,
      Integer supportRequestsClosed,
      Boolean inboxMerged,
      String inboxResult,
      Integer admissionsRemoved,
      List<Maintenance> maintenance) {
    public Result {
      maintenance = maintenance == null ? List.of() : List.copyOf(maintenance);
    }

    public Result(
        String job,
        String ranAt,
        Integer supportRequestsClosed,
        Boolean inboxMerged,
        String inboxResult,
        Integer admissionsRemoved) {
      this(
          job,
          ranAt,
          supportRequestsClosed,
          inboxMerged,
          inboxResult,
          admissionsRemoved,
          List.of());
    }
  }

  /**
   * One maintenance job's sweep: pages run, items cleaned/retained/failed, whether work is left for
   * the next sweep, and the failure type if it stopped.
   */
  public record Maintenance(
      String job, int pages, int cleaned, int retained, int failed, boolean more, String error) {}

  @Autowired
  public SweepRunner(
      PlatformReviewService reviews,
      DeliveryPlanService plans,
      DeliveryPlanReleaseImportJob releaseImport,
      ObjectProvider<DevAccessCleanupJob> devAccessCleanup,
      MaintenanceJobRegistry maintenance,
      @Value("${app.deployment-environment:local}") String environment,
      Environment settings,
      SweepRequestValidator shapes) {
    this(
        reviews,
        plans,
        releaseImport,
        devAccessCleanup,
        maintenance,
        Clock.systemUTC(),
        environment,
        settings,
        shapes);
  }

  /**
   * {@code settings} and {@code shapes} serve {@link #requested} only: the scheduled sweeps never
   * read them.
   */
  public SweepRunner(
      PlatformReviewService reviews,
      DeliveryPlanService plans,
      DeliveryPlanReleaseImportJob releaseImport,
      ObjectProvider<DevAccessCleanupJob> devAccessCleanup,
      MaintenanceJobRegistry maintenance,
      Clock clock,
      String environment,
      Environment settings,
      SweepRequestValidator shapes) {
    this.reviews = reviews;
    this.plans = plans;
    this.releaseImport = releaseImport;
    this.devAccessCleanup = devAccessCleanup;
    this.maintenance = maintenance;
    this.clock = clock;
    this.production = "prod".equals(environment);
    this.settings = settings;
    this.shapes = shapes;
  }

  /**
   * DLV-971: in prod only the support-request close runs (at start, on the schedule and by hand);
   * the rest are DEV only.
   */
  private boolean runsHere(SweepJob job) {
    return !production || job == SweepJob.SUPPORT_REQUESTS;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void atStart() {
    if (!production) guarded("release plan import", releaseImport::run, failure -> null);
    sweep(SweepJob.ALL);
  }

  @Scheduled(fixedDelayString = CADENCE, initialDelayString = CADENCE)
  public void run() {
    sweep(SweepJob.ALL);
  }

  /**
   * The requested sweeps in order, each guarded, and what they did. Shared by the start, the
   * schedule and the owner's manual run.
   */
  public Result sweep(SweepJob job) {
    String ranAt = clock.instant().toString();
    Integer closed = null;
    Boolean merged = null;
    String inboxResult = null;
    Integer removed = null;
    if (job.includes(SweepJob.SUPPORT_REQUESTS))
      closed = guarded("support request close", reviews::closeStaleRequests, failure -> 0);
    if (job.includes(SweepJob.DELIVERY_INBOX) && runsHere(SweepJob.DELIVERY_INBOX)) {
      DeliveryPlanService.Sync sync =
          guarded(
              "delivery inbox sync",
              plans::syncInbox,
              failure ->
                  new DeliveryPlanService.Sync(
                      false, null, 0, "did not complete (" + EventLog.types(failure) + ")"));
      merged = sync.synced();
      inboxResult = sync.synced() ? Long.toString(sync.version()) : sync.reason();
    }
    if (job.includes(SweepJob.ADMISSIONS) && runsHere(SweepJob.ADMISSIONS)) {
      DevAccessCleanupJob cleanup = devAccessCleanup.getIfAvailable();
      if (cleanup != null) removed = cleanup.run();
    }
    var maintained = new ArrayList<Maintenance>();
    if (job.includes(SweepJob.MAINTENANCE) && runsHere(SweepJob.MAINTENANCE))
      for (MaintenanceJobRegistry.Job registered : maintenance.jobs())
        maintained.add(maintain(registered));
    return new Result(job.id, ranAt, closed, merged, inboxResult, removed, maintained);
  }

  /**
   * One job's pages, at most {@link #MAX_PAGES}; a failure ends this job's sweep and is logged like
   * any other sweep failure.
   */
  Maintenance maintain(MaintenanceJobRegistry.Job job) {
    int pages = 0, cleaned = 0, retained = 0, failed = 0;
    boolean more = false;
    String error = null;
    try {
      do {
        MaintenanceJobRegistry.Page page = job.run(pages > 0);
        pages++;
        cleaned += page.cleaned();
        retained += page.retained();
        failed += page.failed();
        more = page.more();
      } while (more && pages < MAX_PAGES);
    } catch (RuntimeException failure) {
      error = EventLog.types(failure);
      more = true;
      EventLog.failed(EventLog.EVENT, "sweep", "UNEXPECTED")
          .addKeyValue("sweep", "maintenance " + job.name())
          .addKeyValue("error.type", error)
          .log(
              "The maintenance {} sweep did not complete ({}); the next run tries again",
              job.name(),
              error);
    }
    return new Maintenance(job.name(), pages, cleaned, retained, failed, more, error);
  }

  /**
   * The job the owner asked to run by hand, after the three checks in the order the route has
   * always answered them: 404 {@code DEV_ONLY} in prod first, then the owner check (401 without a
   * sign-in, 403 {@code PLATFORM_REVIEW_ACCESS_DENIED} for everyone else), then the body (400
   * {@code SWEEP_JOB_INVALID}). DLV-1016: SweepController made these three calls itself; it now
   * asks here and then runs the job with {@link #runNow}.
   */
  public SweepJob requested(AccountPrincipal actor, Map<String, Object> body) {
    DevOnly.require(settings, "Running a sweep by hand");
    reviews.requireOwner(actor);
    return shapes.job(body);
  }

  /**
   * The owner's "run this job now": the requested sweep(s) at once, and one {@code sweep} line
   * naming the actor, the job and the counts. {@code admissions} alone answers {@code 409
   * SWEEP_NOT_AVAILABLE} where there is no cleanup (local mode).
   */
  public Result runNow(UUID actorId, SweepJob job) {
    if (job == SweepJob.ADMISSIONS && devAccessCleanup.getIfAvailable() == null)
      throw new AccountFailure(
          409,
          "SWEEP_NOT_AVAILABLE",
          "The admission cleanup runs only in cloud mode; this Domain has none.");
    Result result = sweep(job);
    EventLog.success(EventLog.EVENT, "sweep")
        .addKeyValue("manual", true)
        .addKeyValue("account.id", actorId)
        .addKeyValue("job", job.id)
        .addKeyValue("support.closed", result.supportRequestsClosed())
        .addKeyValue("inbox.merged", result.inboxMerged())
        .addKeyValue("admissions.removed", result.admissionsRemoved())
        .addKeyValue("maintenance.jobs", result.maintenance().size())
        .log(
            "{} ran the {} sweep now: {} support request(s) closed, inbox {}, {} waiting account(s)"
                + " removed{}",
            EventLog.actor("Owner", actorId),
            job.id,
            result.supportRequestsClosed() == null ? "no" : result.supportRequestsClosed(),
            result.inboxMerged() == null
                ? "not touched"
                : result.inboxMerged()
                    ? "merged into plan version " + result.inboxResult()
                    : result.inboxResult(),
            result.admissionsRemoved() == null ? "no" : result.admissionsRemoved(),
            maintenanceSummary(result.maintenance()));
    return result;
  }

  /**
   * ", maintenance: <job> <cleaned> cleaned/<retained> retained/<failed> failed[ (more)|, stopped
   * by <type>], …"; empty when nothing ran.
   */
  static String maintenanceSummary(List<Maintenance> maintained) {
    if (maintained.isEmpty()) return "";
    var text = new StringBuilder(", maintenance:");
    for (int at = 0; at < maintained.size(); at++) {
      Maintenance item = maintained.get(at);
      text.append(at == 0 ? " " : "; ")
          .append(item.job())
          .append(' ')
          .append(item.cleaned())
          .append(" cleaned/")
          .append(item.retained())
          .append(" retained/")
          .append(item.failed())
          .append(" failed");
      if (item.error() != null) text.append(", stopped by ").append(item.error());
      else if (item.more()) text.append(" (more)");
    }
    return text.toString();
  }

  private static <T> T guarded(
      String sweep, Supplier<T> step, Function<RuntimeException, T> fallback) {
    try {
      return step.get();
    } catch (RuntimeException failure) {
      EventLog.failed(EventLog.EVENT, "sweep", "UNEXPECTED")
          .addKeyValue("sweep", sweep)
          .addKeyValue("error.type", EventLog.types(failure))
          .log(
              "The {} sweep did not complete ({}); the next run tries again",
              sweep,
              EventLog.types(failure));
      return fallback.apply(failure);
    }
  }
}
