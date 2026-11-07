package com.lookahead.domain.sweeps;

import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.domain.review.platform.LegacyReviewImport;
import com.lookahead.domain.review.platform.PlatformReviewService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-971: one component runs the import once at start, then the three sweeps at start and every 4 hours; each sweep is isolated. */
class RuntimeSweepsTest {
    final PlatformReviewService reviews = mock(PlatformReviewService.class);
    final DeliveryPlanService plans = mock(DeliveryPlanService.class);
    final LegacyReviewImport legacy = mock(LegacyReviewImport.class);
    final CloudAdmissionCleanup cleanup = mock(CloudAdmissionCleanup.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<CloudAdmissionCleanup> admissions = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));

    @Test void startRunsTheImportThenEverySweepAndTheScheduleIsFourHourly() throws Exception {
        when(admissions.getIfAvailable()).thenReturn(cleanup);
        when(legacy.run()).thenReturn(Optional.empty());
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
        var sweeps = new RuntimeSweeps(reviews, plans, legacy, admissions);
        sweeps.atStart();
        var order = inOrder(legacy, reviews, plans, cleanup);
        order.verify(legacy).run();
        order.verify(reviews).closeStaleRequests();
        order.verify(plans).syncInbox();
        order.verify(cleanup).run();
        var run = RuntimeSweeps.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(run.fixedDelayString()).isEqualTo("PT4H");
        assertThat(run.initialDelayString()).isEqualTo("PT4H");
        assertThat(RuntimeSweeps.class.getMethod("atStart").getAnnotation(EventListener.class).value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(CloudAdmissionCleanup.class.getMethod("run").getAnnotation(Scheduled.class)).as("the cleanup no longer schedules itself").isNull();
    }

    @Test void localModeHasNoAdmissionCleanupAndAFailingSweepDoesNotStopTheOthers() {
        when(admissions.getIfAvailable()).thenReturn(null);
        when(reviews.closeStaleRequests()).thenThrow(new IllegalStateException("synthetic storage outage"));
        when(legacy.run()).thenThrow(new IllegalStateException("synthetic import outage"));
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
        var sweeps = new RuntimeSweeps(reviews, plans, legacy, admissions);
        try (var log = com.lookahead.domain.LogCapture.start()) {
            assertThatCode(sweeps::atStart).doesNotThrowAnyException();
            assertThat(log.lines("sweep")).extracting(line -> com.lookahead.domain.LogCapture.keys(line).get("sweep")).containsExactly("legacy import", "support request close");
            assertThat(log.lines("sweep").getLast().getFormattedMessage()).isEqualTo("The support request close sweep did not complete (IllegalStateException); the next run tries again");
            log.containsNone("synthetic storage outage");
        }
        verify(plans).syncInbox();
        verifyNoInteractions(cleanup);
    }

    /** DLV-971: the start, the schedule and the owner's manual run share one method that answers what the sweeps did. */
    @Test void theSharedSweepAnswersTheCountsAndTheManualRunLogsOneLineWithTheActor() {
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-06T18:30:00Z"), java.time.ZoneOffset.UTC);
        when(admissions.getIfAvailable()).thenReturn(cleanup);
        when(reviews.closeStaleRequests()).thenReturn(3);
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(true, new com.lookahead.domain.delivery.DeliveryPlanMerge.Counts(1, 0, 2, 0, 5, 0), 12, null));
        when(cleanup.run()).thenReturn(1);
        var sweeps = new RuntimeSweeps(reviews, plans, legacy, admissions, clock);
        var actor = java.util.UUID.randomUUID();
        RuntimeSweeps.Result result;
        try (var log = com.lookahead.domain.LogCapture.start()) {
            result = sweeps.runNow(actor, RuntimeSweeps.Job.ALL);
            var line = log.only("sweep");
            assertThat(line.getFormattedMessage()).isEqualTo("The owner ran the all sweep now: 3 support request(s) closed, inbox merged into plan version 12, 1 waiting account(s) removed");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("manual", true).containsEntry("account.id", actor).containsEntry("job", "all")
                    .containsEntry("event.outcome", "success").containsEntry("support.closed", 3).containsEntry("inbox.merged", true).containsEntry("admissions.removed", 1);
        }
        assertThat(result).isEqualTo(new RuntimeSweeps.Result("all", "2026-11-06T18:30:00Z", 3, true, "12", 1));
        var order = inOrder(reviews, plans, cleanup);
        order.verify(reviews).closeStaleRequests();
        order.verify(plans).syncInbox();
        order.verify(cleanup).run();
        verifyNoInteractions(legacy);
        // The scheduled run is the same sweep of every job, without the manual line.
        try (var log = com.lookahead.domain.LogCapture.start()) {
            sweeps.run();
            assertThat(log.lines("sweep")).isEmpty();
        }
        verify(reviews, times(2)).closeStaleRequests();
        verify(plans, times(2)).syncInbox();
        verify(cleanup, times(2)).run();
    }

    /** One job at a time: only that job runs and only its counts are filled. */
    @Test void aSingleJobRunsAloneAndAnswersOnlyItsCounts() {
        when(admissions.getIfAvailable()).thenReturn(cleanup);
        when(reviews.closeStaleRequests()).thenReturn(2);
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(false, null, 5, "no inbox file"));
        when(cleanup.run()).thenReturn(4);
        var sweeps = new RuntimeSweeps(reviews, plans, legacy, admissions);
        var actor = java.util.UUID.randomUUID();
        try (var log = com.lookahead.domain.LogCapture.start()) {
            var support = sweeps.runNow(actor, RuntimeSweeps.Job.SUPPORT_REQUESTS);
            assertThat(support.job()).isEqualTo("support-requests");
            assertThat(support.supportRequestsClosed()).isEqualTo(2);
            assertThat(support.inboxMerged()).isNull();
            assertThat(support.inboxResult()).isNull();
            assertThat(support.admissionsRemoved()).isNull();
            assertThat(log.only("sweep").getFormattedMessage()).isEqualTo("The owner ran the support-requests sweep now: 2 support request(s) closed, inbox not touched, no waiting account(s) removed");
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("sweep"))).containsEntry("job", "support-requests").containsEntry("manual", true);
        }
        verifyNoInteractions(plans, cleanup);
        var inbox = sweeps.runNow(actor, RuntimeSweeps.Job.DELIVERY_INBOX);
        assertThat(inbox).isEqualTo(new RuntimeSweeps.Result("delivery-inbox", inbox.ranAt(), null, false, "no inbox file", null));
        verify(reviews, times(1)).closeStaleRequests();
        verifyNoInteractions(cleanup);
        var cleaned = sweeps.runNow(actor, RuntimeSweeps.Job.ADMISSIONS);
        assertThat(cleaned).isEqualTo(new RuntimeSweeps.Result("admissions", cleaned.ranAt(), null, null, null, 4));
        verify(plans, times(1)).syncInbox();
        assertThat(RuntimeSweeps.Job.named("admissions")).isEqualTo(RuntimeSweeps.Job.ADMISSIONS);
        assertThat(RuntimeSweeps.Job.named("Admissions")).isNull();
        assertThat(RuntimeSweeps.Job.named("")).isNull();
        assertThat(RuntimeSweeps.Job.named(null)).isNull();
    }

    @Test void anInboxLeftAloneAnswersItsReasonAFailedSweepCountsZeroAndLocalModeHasNoAdmissionJob() {
        when(admissions.getIfAvailable()).thenReturn(null);
        when(reviews.closeStaleRequests()).thenReturn(0);
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(false, null, 5, "no inbox file"));
        var sweeps = new RuntimeSweeps(reviews, plans, legacy, admissions);
        var quiet = sweeps.sweep(RuntimeSweeps.Job.ALL);
        assertThat(quiet.supportRequestsClosed()).isZero();
        assertThat(quiet.inboxMerged()).isFalse();
        assertThat(quiet.inboxResult()).isEqualTo("no inbox file");
        assertThat(quiet.admissionsRemoved()).as("no admission cleanup outside cloud mode").isNull();
        assertThat(java.time.Instant.parse(quiet.ranAt())).isBetween(java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(1));
        var actor = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> sweeps.runNow(actor, RuntimeSweeps.Job.ADMISSIONS)).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class)
                .hasMessageContaining("cloud mode").extracting("code").isEqualTo("SWEEP_NOT_AVAILABLE");
        when(reviews.closeStaleRequests()).thenThrow(new IllegalStateException("synthetic storage outage"));
        when(plans.syncInbox()).thenThrow(new IllegalStateException("synthetic plan outage"));
        RuntimeSweeps.Result failed;
        try (var log = com.lookahead.domain.LogCapture.start()) {
            failed = sweeps.runNow(actor, RuntimeSweeps.Job.ALL);
            assertThat(log.lines("sweep")).extracting(line -> com.lookahead.domain.LogCapture.keys(line).get("sweep")).containsExactly("support request close", "delivery inbox sync", null);
            assertThat(log.lines("sweep").getLast().getFormattedMessage()).isEqualTo("The owner ran the all sweep now: 0 support request(s) closed, inbox did not complete (IllegalStateException), no waiting account(s) removed");
            log.containsNone("synthetic storage outage", "synthetic plan outage");
        }
        assertThat(failed.supportRequestsClosed()).isZero();
        assertThat(failed.inboxMerged()).isFalse();
        assertThat(failed.inboxResult()).isEqualTo("did not complete (IllegalStateException)");
        assertThat(failed.admissionsRemoved()).isNull();
    }
}
