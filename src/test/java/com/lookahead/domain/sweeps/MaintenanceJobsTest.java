package com.lookahead.domain.sweeps;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.domain.persistence.dynamodb.StudyPlanDocumentMaintenance;
import com.lookahead.domain.persistence.dynamodb.StudyPlanDocumentRetirement;
import com.lookahead.domain.review.platform.PlatformReviewService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** DLV-970: the maintenance sweep runs every registered job in bounded pages; the DynamoDB document jobs page through their cleanups. */
class MaintenanceJobsTest {
    final PlatformReviewService reviews = mock(PlatformReviewService.class);
    final DeliveryPlanService plans = mock(DeliveryPlanService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<CloudAdmissionCleanup> admissions = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final RegisteredMaintenanceJobs registry = new RegisteredMaintenanceJobs();

    /** Answers a fixed sequence of pages, then repeats the last; records the continuation flags. */
    static final class PagedJob implements MaintenanceJobs.Job {
        final String name; final List<MaintenanceJobs.Page> pages; final List<Boolean> calls = new ArrayList<>(); RuntimeException failure; int page;
        PagedJob(String name, MaintenanceJobs.Page... pages) { this.name = name; this.pages = List.of(pages); }
        @Override public String name() { return name; }
        @Override public MaintenanceJobs.Page run(boolean afterFirstPage) {
            calls.add(afterFirstPage);
            if (!afterFirstPage) page = 0;
            if (failure != null) throw failure;
            return pages.get(Math.min(page++, pages.size() - 1));
        }
    }

    RuntimeSweeps sweeps() {
        when(admissions.getIfAvailable()).thenReturn(null);
        when(reviews.closeStaleRequests()).thenReturn(0);
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
        return new RuntimeSweeps(reviews, plans, null, admissions, registry, java.time.Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), java.time.ZoneOffset.UTC));
    }

    @Test void theRegistryKeepsOrderRefusesDuplicatesAndBadNamesAndCollectsBeans() {
        var a = new PagedJob("a-job", MaintenanceJobs.Page.NOTHING); var b = new PagedJob("b-job", MaintenanceJobs.Page.NOTHING);
        @SuppressWarnings("unchecked") ObjectProvider<MaintenanceJobs.Job> beans = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(beans.orderedStream()).thenReturn(java.util.stream.Stream.of(b, a));
        var collected = new RegisteredMaintenanceJobs(beans);
        assertThat(collected.jobs()).containsExactly(b, a);
        registry.register(a); registry.register(b);
        assertThat(registry.jobs()).extracting(MaintenanceJobs.Job::name).containsExactly("a-job", "b-job");
        assertThatThrownBy(() -> registry.register(new PagedJob("a-job"))).hasMessageContaining("already registered");
        assertThatThrownBy(() -> registry.register(new PagedJob("Bad Name"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(new PagedJob(null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MaintenanceJobs.Page(-1, 0, 0, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RuntimeSweeps.Job.named("maintenance")).isEqualTo(RuntimeSweeps.Job.MAINTENANCE);
    }

    @Test void theSweepPagesEachJobUntilItIsDoneOrThePageCapAndAFailureStopsOnlyThatJob() {
        var paged = new PagedJob("documents", new MaintenanceJobs.Page(2, 1, 0, true), new MaintenanceJobs.Page(1, 0, 1, true), new MaintenanceJobs.Page(0, 0, 0, false));
        var endless = new PagedJob("endless", new MaintenanceJobs.Page(1, 0, 0, true));
        var broken = new PagedJob("broken", MaintenanceJobs.Page.NOTHING); broken.failure = new IllegalStateException("synthetic store outage");
        var after = new PagedJob("after", new MaintenanceJobs.Page(0, 3, 0, false));
        registry.register(paged); registry.register(endless); registry.register(broken); registry.register(after);
        var sweeps = sweeps();
        RuntimeSweeps.Result result;
        try (var log = LogCapture.start()) {
            result = sweeps.sweep(RuntimeSweeps.Job.MAINTENANCE);
            assertThat(log.lines("sweep")).hasSize(1);
            assertThat(LogCapture.keys(log.only("sweep"))).containsEntry("sweep", "maintenance broken").containsEntry("error.type", "IllegalStateException");
            assertThat(log.only("sweep").getFormattedMessage()).isEqualTo("The maintenance broken sweep did not complete (IllegalStateException); the next run tries again");
            log.containsNone("synthetic store outage");
        }
        assertThat(result.job()).isEqualTo("maintenance");
        assertThat(result.supportRequestsClosed()).isNull(); assertThat(result.inboxMerged()).isNull(); assertThat(result.admissionsRemoved()).isNull();
        assertThat(result.maintenance()).containsExactly(
                new RuntimeSweeps.Maintenance("documents", 3, 3, 1, 1, false, null),
                new RuntimeSweeps.Maintenance("endless", RuntimeSweeps.MAX_PAGES, RuntimeSweeps.MAX_PAGES, 0, 0, true, null),
                new RuntimeSweeps.Maintenance("broken", 0, 0, 0, 0, true, "IllegalStateException"),
                new RuntimeSweeps.Maintenance("after", 1, 0, 3, 0, false, null));
        assertThat(paged.calls).containsExactly(false, true, true);
        assertThat(endless.calls).hasSize(RuntimeSweeps.MAX_PAGES);
        assertThat(endless.calls.getFirst()).isFalse();
        verifyNoInteractions(reviews, plans);
        // The next sweep starts every job afresh.
        sweeps.sweep(RuntimeSweeps.Job.MAINTENANCE);
        assertThat(paged.calls).containsExactly(false, true, true, false, true, true);
    }

    @Test void allIncludesMaintenanceAndTheManualLineSummarisesEachJobOnlyWhenSomethingIsRegistered() {
        var sweeps = sweeps();
        var actor = UUID.randomUUID();
        try (var log = LogCapture.start()) {
            var empty = sweeps.runNow(actor, RuntimeSweeps.Job.ALL);
            assertThat(empty.maintenance()).isEmpty();
            assertThat(empty).isEqualTo(new RuntimeSweeps.Result("all", "2026-11-10T08:00:00Z", 0, false, "no inbox file", null));
            assertThat(log.only("sweep").getFormattedMessage()).isEqualTo("Owner " + actor.toString().substring(0, 8) + " ran the all sweep now: 0 support request(s) closed, inbox no inbox file, no waiting account(s) removed");
            assertThat(LogCapture.keys(log.only("sweep"))).containsEntry("maintenance.jobs", 0);
        }
        var done = new PagedJob("documents", new MaintenanceJobs.Page(2, 1, 0, true), new MaintenanceJobs.Page(0, 0, 0, false));
        var broken = new PagedJob("broken", MaintenanceJobs.Page.NOTHING); broken.failure = new IllegalStateException("synthetic");
        var endless = new PagedJob("endless", new MaintenanceJobs.Page(1, 0, 0, true));
        registry.register(done); registry.register(broken); registry.register(endless);
        try (var log = LogCapture.start()) {
            var result = sweeps.runNow(actor, RuntimeSweeps.Job.ALL);
            assertThat(result.maintenance()).extracting(RuntimeSweeps.Maintenance::job).containsExactly("documents", "broken", "endless");
            assertThat(log.lines("sweep").getLast().getFormattedMessage()).isEqualTo("Owner " + actor.toString().substring(0, 8) + " ran the all sweep now: 0 support request(s) closed, inbox no inbox file, no waiting account(s) removed"
                    + ", maintenance: documents 2 cleaned/1 retained/0 failed; broken 0 cleaned/0 retained/0 failed, stopped by IllegalStateException; endless 40 cleaned/0 retained/0 failed (more)");
            assertThat(LogCapture.keys(log.lines("sweep").getLast())).containsEntry("maintenance.jobs", 3);
        }
        // A single other job does not run maintenance.
        assertThat(sweeps.sweep(RuntimeSweeps.Job.SUPPORT_REQUESTS).maintenance()).isEmpty();
        assertThat(done.calls).hasSize(2);
        assertThat(new RuntimeSweeps(reviews, plans, null, admissions, null, java.time.Clock.systemUTC()).sweep(RuntimeSweeps.Job.MAINTENANCE).maintenance()).isEmpty();
    }

    @Test void theDynamoDocumentJobsPageThroughTheirCleanupsAndRestartTheCursorEachSweep() {
        var maintenance = mock(StudyPlanDocumentMaintenance.class);
        var retirement = mock(StudyPlanDocumentRetirement.class);
        var cursor = new StudyPlanDocumentMaintenance.Cursor(Instant.parse("2026-11-10T08:00:00Z"), "ACCOUNT#x");
        when(maintenance.cleanDue(eq(StudyPlanDocumentMaintenance.MAX_JOBS), isNull())).thenReturn(new StudyPlanDocumentMaintenance.Batch(cursor.cutoff(),
                List.of(new StudyPlanDocumentMaintenance.JobResult("a", StudyPlanDocumentMaintenance.Outcome.CLEANED), new StudyPlanDocumentMaintenance.JobResult("b", StudyPlanDocumentMaintenance.Outcome.RETAINED)), cursor));
        when(maintenance.cleanDue(StudyPlanDocumentMaintenance.MAX_JOBS, cursor)).thenReturn(new StudyPlanDocumentMaintenance.Batch(cursor.cutoff(),
                List.of(new StudyPlanDocumentMaintenance.JobResult("c", StudyPlanDocumentMaintenance.Outcome.FAILED)), null));
        var documents = new DynamoMaintenanceJobs.DocumentJob(maintenance);
        assertThat(documents.name()).isEqualTo("study-plan-documents");
        assertThat(documents.run(false)).isEqualTo(new MaintenanceJobs.Page(1, 1, 0, true));
        assertThat(documents.run(true)).isEqualTo(new MaintenanceJobs.Page(0, 0, 1, false));
        assertThat(documents.run(false)).as("a new sweep starts without a cursor").isEqualTo(new MaintenanceJobs.Page(1, 1, 0, true));
        verify(maintenance, times(2)).cleanDue(StudyPlanDocumentMaintenance.MAX_JOBS, null);
        when(retirement.cleanPage(eq(StudyPlanDocumentRetirement.MAX_JOBS), isNull())).thenReturn(new StudyPlanDocumentRetirement.Page(
                List.of(new StudyPlanDocumentRetirement.JobResult("x", StudyPlanDocumentRetirement.Outcome.CLEANED)), "PLAN#x"));
        when(retirement.cleanPage(StudyPlanDocumentRetirement.MAX_JOBS, "PLAN#x")).thenReturn(new StudyPlanDocumentRetirement.Page(
                List.of(new StudyPlanDocumentRetirement.JobResult("y", StudyPlanDocumentRetirement.Outcome.RETAINED), new StudyPlanDocumentRetirement.JobResult("z", StudyPlanDocumentRetirement.Outcome.FAILED)), null));
        var retired = new DynamoMaintenanceJobs.RetiredJob(retirement);
        assertThat(retired.name()).isEqualTo("retired-study-plan-documents");
        assertThat(retired.run(false)).isEqualTo(new MaintenanceJobs.Page(1, 0, 0, true));
        assertThat(retired.run(true)).isEqualTo(new MaintenanceJobs.Page(0, 1, 1, false));
        // The configuration builds both jobs from the client and the names.
        var client = mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
        var configuration = new DynamoMaintenanceJobs();
        assertThat(configuration.studyPlanDocumentMaintenanceJob(client, com.lookahead.domain.persistence.dynamodb.DynamoTableNames.local()).name()).isEqualTo("study-plan-documents");
        assertThat(configuration.retiredStudyPlanDocumentJob(client, com.lookahead.domain.persistence.dynamodb.DynamoTableNames.local()).name()).isEqualTo("retired-study-plan-documents");
        verifyNoInteractions(client);
    }
}
