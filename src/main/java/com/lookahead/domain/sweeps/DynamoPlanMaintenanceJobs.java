package com.lookahead.domain.sweeps;

import com.lookahead.learning.content.repository.PlanMaintenanceJobs;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * DLV-970 lane G: lane D's sweep runner for lane B. A registered {@link Runnable} page (the deleted-plan purge,
 * {@code plan-purge}) becomes a {@link MaintenanceJobs.Job} that runs one page per sweep; the runnable reports no
 * counts, so the page answers {@link MaintenanceJobs.Page#NOTHING} and the next sweep continues where it left off.
 */
@Component
public class DynamoPlanMaintenanceJobs implements PlanMaintenanceJobs {
    private final MaintenanceJobs jobs;

    public DynamoPlanMaintenanceJobs(MaintenanceJobs jobs) { this.jobs = Objects.requireNonNull(jobs, "jobs"); }

    @Override public void register(String name, Runnable job) {
        Objects.requireNonNull(name, "name"); Objects.requireNonNull(job, "job");
        jobs.register(new MaintenanceJobs.Job() {
            @Override public String name() { return name; }
            @Override public MaintenanceJobs.Page run(boolean afterFirstPage) { job.run(); return MaintenanceJobs.Page.NOTHING; }
        });
    }
}
