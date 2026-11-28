package com.lookahead.domain.job;

import com.lookahead.domain.db.store.plan.StudyPlanPurgeStore;
import com.lookahead.domain.log.EventLog;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code plan-purge} maintenance job: each sweep runs one page of the queued plan deletions
 * ({@link StudyPlanPurgeStore#queued}, each through {@link StudyPlanPurgeStore#purge}; one that
 * fails is logged and stays queued). The page reports no counts, so the job answers {@link
 * MaintenanceJobRegistry.Page#NOTHING} and the next sweep continues where this one left off.
 *
 * <p>It is on purpose not a {@link MaintenanceJobRegistry.Job} bean. The registry collects the job
 * beans when it is created; this component needs the finished registry and registers its job with
 * it afterwards. So {@code plan-purge} comes after every collected job: it runs last and is listed
 * last in a sweep's answer, as it did when the store registered it.
 */
@Component
@Profile("accounts")
public class StudyPlanPurgeJob {
  public static final String NAME = "plan-purge";

  public StudyPlanPurgeJob(StudyPlanPurgeStore purge, MaintenanceJobRegistry jobs) {
    Objects.requireNonNull(purge, "purge");
    Objects.requireNonNull(jobs, "jobs");
    jobs.register(
        new MaintenanceJobRegistry.Job() {
          @Override
          public String name() {
            return NAME;
          }

          @Override
          public MaintenanceJobRegistry.Page run(boolean afterFirstPage) {
            // DLV-1016: the loop over the page and the "deferred" line were the store's cleanPage.
            // A deletion that fails stays queued.
            for (StudyPlanPurgeStore.Queued queued : purge.queued(StudyPlanPurgeStore.MAX_JOBS)) {
              try {
                purge.purge(queued);
              } catch (RuntimeException failure) {
                // Exception, decided 2026-11-25 (DLV-1016): this WARN line is written on the logger
                // directly, not through an
                // EventLog helper. It is on lookahead.event and carries no keys. EventLog.failed
                // would add the keys event.action,
                // event.outcome and reason; EventLog.warnWithoutStack writes on lookahead.failure,
                // another logger name.
                EventLog.EVENT.warn("Plan purge job deferred: {}", EventLog.types(failure));
              }
            }
            return MaintenanceJobRegistry.Page.NOTHING;
          }
        });
  }
}
