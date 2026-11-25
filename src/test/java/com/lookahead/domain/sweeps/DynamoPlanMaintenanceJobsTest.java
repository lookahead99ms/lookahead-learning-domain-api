package com.lookahead.domain.sweeps;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DynamoPlanMaintenanceJobsTest {
    @Test void aRegisteredRunnableBecomesOneMaintenanceJobThatRunsOnePagePerSweep() {
        var registry = new RegisteredMaintenanceJobs();
        var runs = new AtomicInteger();
        new DynamoPlanMaintenanceJobs(registry).register("plan-purge", runs::incrementAndGet);
        assertThat(registry.jobs()).extracting(MaintenanceJobs.Job::name).containsExactly("plan-purge");
        var job = registry.jobs().getFirst();
        assertThat(job.run(false)).isEqualTo(MaintenanceJobs.Page.NOTHING);
        assertThat(job.run(true)).isEqualTo(MaintenanceJobs.Page.NOTHING);
        assertThat(runs.get()).isEqualTo(2);
        // The sweep runs exactly one page: NOTHING says no more work this cycle.
        var sweeps = new RuntimeSweeps(null, null, null, null, registry, java.time.Clock.systemUTC());
        var maintained = sweeps.maintain(job);
        assertThat(maintained.pages()).isEqualTo(1); assertThat(maintained.more()).isFalse(); assertThat(runs.get()).isEqualTo(3);
        assertThatThrownBy(() -> new DynamoPlanMaintenanceJobs(registry).register("plan-purge", () -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoPlanMaintenanceJobs(registry).register(null, () -> {})).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DynamoPlanMaintenanceJobs(null)).isInstanceOf(NullPointerException.class);
    }
}
