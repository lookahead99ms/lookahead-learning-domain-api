package com.lookahead.domain.job;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.model.StudyPlanDocumentJobModels;
import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.config.StudyPlanDocumentJobConfiguration;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.service.delivery.DeliveryPlanService;
import com.lookahead.domain.db.store.plan.StudyPlanDocumentCleanupStore;
import com.lookahead.domain.db.store.plan.StudyPlanDocumentRetirementStore;
import com.lookahead.domain.service.review.PlatformReviewService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** DLV-970: the maintenance sweep runs every registered job in bounded pages; the DynamoDB document jobs page through their cleanups. */
class MaintenanceJobRegistryTest {
    final PlatformReviewService reviews = mock(PlatformReviewService.class);
    final DeliveryPlanService plans = mock(DeliveryPlanService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<DevAccessCleanupJob> devAccessCleanup = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final MaintenanceJobBeanRegistry registry = new MaintenanceJobBeanRegistry();

    /** Answers a fixed sequence of pages, then repeats the last; records the continuation flags. */
    static final class PagedJob implements MaintenanceJobRegistry.Job {
        final String name; final List<MaintenanceJobRegistry.Page> pages; final List<Boolean> calls = new ArrayList<>(); RuntimeException failure; int page;
        PagedJob(String name, MaintenanceJobRegistry.Page... pages) { this.name = name; this.pages = List.of(pages); }
        @Override public String name() { return name; }
        @Override public MaintenanceJobRegistry.Page run(boolean afterFirstPage) {
            calls.add(afterFirstPage);
            if (!afterFirstPage) page = 0;
            if (failure != null) throw failure;
            return pages.get(Math.min(page++, pages.size() - 1));
        }
    }

    SweepRunner sweeps() {
        when(devAccessCleanup.getIfAvailable()).thenReturn(null);
        when(reviews.closeStaleRequests()).thenReturn(0);
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
        return new SweepRunner(reviews, plans, null, devAccessCleanup, registry, Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC), "local", null, null);
    }

    @Test void theRegistryKeepsOrderRefusesDuplicatesAndBadNamesAndCollectsBeans() {
        var a = new PagedJob("a-job", MaintenanceJobRegistry.Page.NOTHING); var b = new PagedJob("b-job", MaintenanceJobRegistry.Page.NOTHING);
        @SuppressWarnings("unchecked") ObjectProvider<MaintenanceJobRegistry.Job> beans = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(beans.orderedStream()).thenReturn(Stream.of(b, a));
        var collected = new MaintenanceJobBeanRegistry(beans);
        assertThat(collected.jobs()).containsExactly(b, a);
        registry.register(a); registry.register(b);
        assertThat(registry.jobs()).extracting(MaintenanceJobRegistry.Job::name).containsExactly("a-job", "b-job");
        assertThatThrownBy(() -> registry.register(new PagedJob("a-job"))).hasMessageContaining("already registered");
        assertThatThrownBy(() -> registry.register(new PagedJob("Bad Name"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(new PagedJob(null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MaintenanceJobRegistry.Page(-1, 0, 0, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SweepJob.named("maintenance")).isEqualTo(SweepJob.MAINTENANCE);
    }

    @Test void theSweepPagesEachJobUntilItIsDoneOrThePageCapAndAFailureStopsOnlyThatJob() {
        var paged = new PagedJob("documents", new MaintenanceJobRegistry.Page(2, 1, 0, true), new MaintenanceJobRegistry.Page(1, 0, 1, true), new MaintenanceJobRegistry.Page(0, 0, 0, false));
        var endless = new PagedJob("endless", new MaintenanceJobRegistry.Page(1, 0, 0, true));
        var broken = new PagedJob("broken", MaintenanceJobRegistry.Page.NOTHING); broken.failure = new IllegalStateException("synthetic store outage");
        var after = new PagedJob("after", new MaintenanceJobRegistry.Page(0, 3, 0, false));
        registry.register(paged); registry.register(endless); registry.register(broken); registry.register(after);
        var sweeps = sweeps();
        SweepRunner.Result result;
        try (var log = LogCapture.start()) {
            result = sweeps.sweep(SweepJob.MAINTENANCE);
            assertThat(log.lines("sweep")).hasSize(1);
            assertThat(LogCapture.keys(log.only("sweep"))).containsEntry("sweep", "maintenance broken").containsEntry("error.type", "IllegalStateException");
            assertThat(log.only("sweep").getFormattedMessage()).isEqualTo("The maintenance broken sweep did not complete (IllegalStateException); the next run tries again");
            log.containsNone("synthetic store outage");
        }
        assertThat(result.job()).isEqualTo("maintenance");
        assertThat(result.supportRequestsClosed()).isNull(); assertThat(result.inboxMerged()).isNull(); assertThat(result.admissionsRemoved()).isNull();
        assertThat(result.maintenance()).containsExactly(
                new SweepRunner.Maintenance("documents", 3, 3, 1, 1, false, null),
                new SweepRunner.Maintenance("endless", SweepRunner.MAX_PAGES, SweepRunner.MAX_PAGES, 0, 0, true, null),
                new SweepRunner.Maintenance("broken", 0, 0, 0, 0, true, "IllegalStateException"),
                new SweepRunner.Maintenance("after", 1, 0, 3, 0, false, null));
        assertThat(paged.calls).containsExactly(false, true, true);
        assertThat(endless.calls).hasSize(SweepRunner.MAX_PAGES);
        assertThat(endless.calls.getFirst()).isFalse();
        verifyNoInteractions(reviews, plans);
        // The next sweep starts every job afresh.
        sweeps.sweep(SweepJob.MAINTENANCE);
        assertThat(paged.calls).containsExactly(false, true, true, false, true, true);
    }

    @Test void allIncludesMaintenanceAndTheManualLineSummarisesEachJobOnlyWhenSomethingIsRegistered() {
        var sweeps = sweeps();
        var actor = UUID.randomUUID();
        try (var log = LogCapture.start()) {
            var empty = sweeps.runNow(actor, SweepJob.ALL);
            assertThat(empty.maintenance()).isEmpty();
            assertThat(empty).isEqualTo(new SweepRunner.Result("all", "2026-11-10T08:00:00Z", 0, false, "no inbox file", null));
            assertThat(log.only("sweep").getFormattedMessage()).isEqualTo("Owner " + actor.toString().substring(0, 8) + " ran the all sweep now: 0 support request(s) closed, inbox no inbox file, no waiting account(s) removed");
            assertThat(LogCapture.keys(log.only("sweep"))).containsEntry("maintenance.jobs", 0);
        }
        var done = new PagedJob("documents", new MaintenanceJobRegistry.Page(2, 1, 0, true), new MaintenanceJobRegistry.Page(0, 0, 0, false));
        var broken = new PagedJob("broken", MaintenanceJobRegistry.Page.NOTHING); broken.failure = new IllegalStateException("synthetic");
        var endless = new PagedJob("endless", new MaintenanceJobRegistry.Page(1, 0, 0, true));
        registry.register(done); registry.register(broken); registry.register(endless);
        try (var log = LogCapture.start()) {
            var result = sweeps.runNow(actor, SweepJob.ALL);
            assertThat(result.maintenance()).extracting(SweepRunner.Maintenance::job).containsExactly("documents", "broken", "endless");
            assertThat(log.lines("sweep").getLast().getFormattedMessage()).isEqualTo("Owner " + actor.toString().substring(0, 8) + " ran the all sweep now: 0 support request(s) closed, inbox no inbox file, no waiting account(s) removed"
                    + ", maintenance: documents 2 cleaned/1 retained/0 failed; broken 0 cleaned/0 retained/0 failed, stopped by IllegalStateException; endless 40 cleaned/0 retained/0 failed (more)");
            assertThat(LogCapture.keys(log.lines("sweep").getLast())).containsEntry("maintenance.jobs", 3);
        }
        // A single other job does not run maintenance.
        assertThat(sweeps.sweep(SweepJob.SUPPORT_REQUESTS).maintenance()).isEmpty();
        assertThat(done.calls).hasSize(2);
        assertThat(new SweepRunner(reviews, plans, null, devAccessCleanup, new MaintenanceJobBeanRegistry(), Clock.systemUTC(), "local", null, null).sweep(SweepJob.MAINTENANCE).maintenance()).isEmpty();
    }

    @Test void aDocumentSweepPageCountsEachJobOnceUnderItsOutcome() {
        var cleaned = new StudyPlanDocumentJobModels.JobResult("a", StudyPlanDocumentJobModels.Outcome.CLEANED);
        var retained = new StudyPlanDocumentJobModels.JobResult("b", StudyPlanDocumentJobModels.Outcome.RETAINED);
        var failed = new StudyPlanDocumentJobModels.JobResult("c", StudyPlanDocumentJobModels.Outcome.FAILED);
        assertThat(MaintenanceJobRegistry.Page.counted(List.of(cleaned, failed, cleaned, retained, failed, cleaned), true))
                .isEqualTo(new MaintenanceJobRegistry.Page(3, 1, 2, true));
        assertThat(MaintenanceJobRegistry.Page.counted(List.of(), false)).isEqualTo(MaintenanceJobRegistry.Page.NOTHING);
        assertThat(StudyPlanDocumentJobModels.MAX_JOBS).isEqualTo(25);
    }

    @Test void theDynamoDocumentJobsPageThroughTheirCleanupsAndRestartTheCursorEachSweep() {
        var maintenance = mock(StudyPlanDocumentCleanupStore.class);
        var retirement = mock(StudyPlanDocumentRetirementStore.class);
        var cursor = new StudyPlanDocumentCleanupStore.Cursor(Instant.parse("2026-11-10T08:00:00Z"), "ACCOUNT#x");
        when(maintenance.cleanDue(eq(StudyPlanDocumentJobModels.MAX_JOBS), isNull())).thenReturn(new StudyPlanDocumentCleanupStore.Batch(cursor.cutoff(),
                List.of(new StudyPlanDocumentJobModels.JobResult("a", StudyPlanDocumentJobModels.Outcome.CLEANED), new StudyPlanDocumentJobModels.JobResult("b", StudyPlanDocumentJobModels.Outcome.RETAINED)), cursor));
        when(maintenance.cleanDue(StudyPlanDocumentJobModels.MAX_JOBS, cursor)).thenReturn(new StudyPlanDocumentCleanupStore.Batch(cursor.cutoff(),
                List.of(new StudyPlanDocumentJobModels.JobResult("c", StudyPlanDocumentJobModels.Outcome.FAILED)), null));
        var documents = new StudyPlanDocumentCleanupJob(maintenance);
        assertThat(documents.name()).isEqualTo("study-plan-documents");
        assertThat(documents.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(1, 1, 0, true));
        assertThat(documents.run(true)).isEqualTo(new MaintenanceJobRegistry.Page(0, 0, 1, false));
        assertThat(documents.run(false)).as("a new sweep starts without a cursor").isEqualTo(new MaintenanceJobRegistry.Page(1, 1, 0, true));
        verify(maintenance, times(2)).cleanDue(StudyPlanDocumentJobModels.MAX_JOBS, null);
        when(retirement.cleanPage(eq(StudyPlanDocumentJobModels.MAX_JOBS), isNull())).thenReturn(new StudyPlanDocumentRetirementStore.Page(
                List.of(new StudyPlanDocumentJobModels.JobResult("x", StudyPlanDocumentJobModels.Outcome.CLEANED)), "PLAN#x"));
        when(retirement.cleanPage(StudyPlanDocumentJobModels.MAX_JOBS, "PLAN#x")).thenReturn(new StudyPlanDocumentRetirementStore.Page(
                List.of(new StudyPlanDocumentJobModels.JobResult("y", StudyPlanDocumentJobModels.Outcome.RETAINED), new StudyPlanDocumentJobModels.JobResult("z", StudyPlanDocumentJobModels.Outcome.FAILED)), null));
        var retired = new StudyPlanDocumentRetirementJob(retirement);
        assertThat(retired.name()).isEqualTo("retired-study-plan-documents");
        assertThat(retired.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(1, 0, 0, true));
        assertThat(retired.run(true)).isEqualTo(new MaintenanceJobRegistry.Page(0, 1, 1, false));
        // The configuration builds both jobs from the client and the names.
        var client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
        var configuration = new StudyPlanDocumentJobConfiguration();
        assertThat(configuration.studyPlanDocumentCleanupJob(client, DynamoTableNames.local()).name()).isEqualTo("study-plan-documents");
        assertThat(configuration.retiredStudyPlanDocumentJob(client, DynamoTableNames.local()).name()).isEqualTo("retired-study-plan-documents");
        verifyNoInteractions(client);
    }
}
