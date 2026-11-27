package com.lookahead.domain.job;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.db.store.plan.StudyPlanPurgeStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyPlanPurgeJobTest {
    private final StudyPlanPurgeStore purge = mock(StudyPlanPurgeStore.class, withSettings().mockMaker(MockMakers.SUBCLASS));

    @Test void thePurgeBecomesOneMaintenanceJobThatRunsOnePagePerSweep() {
        var registry = new MaintenanceJobBeanRegistry();
        new StudyPlanPurgeJob(purge, registry);
        assertThat(registry.jobs()).extracting(MaintenanceJobRegistry.Job::name).containsExactly("plan-purge");
        verifyNoInteractions(purge);
        var job = registry.jobs().getFirst();
        assertThat(job.run(false)).isEqualTo(MaintenanceJobRegistry.Page.NOTHING);
        assertThat(job.run(true)).isEqualTo(MaintenanceJobRegistry.Page.NOTHING);
        verify(purge, times(2)).queued(StudyPlanPurgeStore.MAX_JOBS);
        // The sweep runs exactly one page: NOTHING says no more work this cycle.
        var sweeps = new SweepRunner(null, null, null, null, registry, Clock.systemUTC(), "local", null, null);
        var maintained = sweeps.maintain(job);
        assertThat(maintained.pages()).isEqualTo(1); assertThat(maintained.more()).isFalse();
        assertThat(maintained).isEqualTo(new SweepRunner.Maintenance("plan-purge", 1, 0, 0, 0, false, null));
        verify(purge, times(3)).queued(25);
        verify(purge, never()).purge(any());
        assertThatThrownBy(() -> new StudyPlanPurgeJob(purge, registry)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already registered");
        assertThatThrownBy(() -> new StudyPlanPurgeJob(null, registry)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StudyPlanPurgeJob(purge, null)).isInstanceOf(NullPointerException.class);
    }

    /** DLV-1016: the job walks the page; a deletion that fails is logged with its type, stays queued and does not stop the ones after it. */
    @Test void aDeletionThatFailsIsDeferredWithOneLineAndTheRestOfThePageStillRuns() {
        var registry = new MaintenanceJobBeanRegistry();
        new StudyPlanPurgeJob(purge, registry);
        var first = mock(StudyPlanPurgeStore.Queued.class); var broken = mock(StudyPlanPurgeStore.Queued.class); var last = mock(StudyPlanPurgeStore.Queued.class);
        when(purge.queued(StudyPlanPurgeStore.MAX_JOBS)).thenReturn(List.of(first, broken, last));
        doThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid")).when(purge).purge(broken);
        try (var log = LogCapture.start()) {
            assertThat(registry.jobs().getFirst().run(false)).isEqualTo(MaintenanceJobRegistry.Page.NOTHING);
            assertThat(log.messages()).containsExactly("Plan purge job deferred: IllegalArgumentException");
            assertThat(log.lines().getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(log.lines().getFirst().getLoggerName()).isEqualTo("lookahead.event");
        }
        var order = inOrder(purge);
        order.verify(purge).purge(first); order.verify(purge).purge(broken); order.verify(purge).purge(last);
        // The page read itself failing is not caught here: the sweep reports the job as failed, as before.
        when(purge.queued(StudyPlanPurgeStore.MAX_JOBS)).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(() -> registry.jobs().getFirst().run(false)).isInstanceOf(IllegalStateException.class);
    }

    /** As in the running application: the registry first collects the job beans, the purge registers afterwards and so runs last. */
    @Test void thePurgeRegistersAfterEveryCollectedJobAndRunsLast() {
        var ran = new ArrayList<String>();
        when(purge.queued(anyInt())).thenAnswer(page -> { ran.add("plan-purge"); return List.of(); });
        var collected = new StaticListableBeanFactory();
        for (String name : List.of("account-erasure", "sign-in-records", "study-plan-documents", "retired-study-plan-documents"))
            collected.addBean(name, new MaintenanceJobRegistry.Job() {
                @Override public String name() { return name; }
                @Override public MaintenanceJobRegistry.Page run(boolean afterFirstPage) { ran.add(name); return MaintenanceJobRegistry.Page.NOTHING; }
            });
        var registry = new MaintenanceJobBeanRegistry(collected.getBeanProvider(MaintenanceJobRegistry.Job.class));
        new StudyPlanPurgeJob(purge, registry);
        var expected = List.of("account-erasure", "sign-in-records", "study-plan-documents", "retired-study-plan-documents", "plan-purge");
        assertThat(registry.jobs()).extracting(MaintenanceJobRegistry.Job::name).containsExactlyElementsOf(expected);
        var result = new SweepRunner(null, null, null, null, registry, Clock.systemUTC(), "local", null, null).sweep(SweepJob.MAINTENANCE);
        assertThat(result.maintenance()).extracting(SweepRunner.Maintenance::job).containsExactlyElementsOf(expected);
        assertThat(ran).containsExactlyElementsOf(expected);
    }
}
