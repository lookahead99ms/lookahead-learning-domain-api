package com.lookahead.domain.job;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.SweepJob;
import com.lookahead.domain.service.delivery.DeliveryPlanService;
import com.lookahead.domain.service.review.PlatformReviewService;
import com.lookahead.domain.util.DeliveryPlanMerge;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * DLV-971: one component runs the import once at start, then the three sweeps at start and every 4
 * hours; each sweep is isolated.
 */
class SweepRunnerTest {
  final PlatformReviewService reviews = mock(PlatformReviewService.class);
  final DeliveryPlanService plans = mock(DeliveryPlanService.class);
  final DeliveryPlanReleaseImportJob release = mock(DeliveryPlanReleaseImportJob.class);
  final DevAccessCleanupJob cleanup = mock(DevAccessCleanupJob.class);

  @SuppressWarnings("unchecked")
  final ObjectProvider<DevAccessCleanupJob> devAccessCleanup =
      mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));

  @Test
  void startRunsTheImportThenEverySweepAndTheScheduleIsFourHourly() throws Exception {
    when(devAccessCleanup.getIfAvailable()).thenReturn(cleanup);
    when(plans.syncInbox())
        .thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            new MaintenanceJobBeanRegistry(),
            Clock.systemUTC(),
            "local",
            null,
            null);
    sweeps.atStart();
    var order = inOrder(release, reviews, plans, cleanup);
    order.verify(release).run();
    order.verify(reviews).closeStaleRequests();
    order.verify(plans).syncInbox();
    order.verify(cleanup).run();
    var run = SweepRunner.class.getMethod("run").getAnnotation(Scheduled.class);
    assertThat(run.fixedDelayString()).isEqualTo("PT4H");
    assertThat(run.initialDelayString()).isEqualTo("PT4H");
    assertThat(SweepRunner.class.getMethod("atStart").getAnnotation(EventListener.class).value())
        .containsExactly(ApplicationReadyEvent.class);
    assertThat(DevAccessCleanupJob.class.getMethod("run").getAnnotation(Scheduled.class))
        .as("the cleanup no longer schedules itself")
        .isNull();
  }

  @Test
  void localModeHasNoAdmissionCleanupAndAFailingSweepDoesNotStopTheOthers() {
    when(devAccessCleanup.getIfAvailable()).thenReturn(null);
    when(reviews.closeStaleRequests())
        .thenThrow(new IllegalStateException("synthetic storage outage"));
    when(release.run()).thenThrow(new IllegalStateException("synthetic release outage"));
    when(plans.syncInbox())
        .thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            new MaintenanceJobBeanRegistry(),
            Clock.systemUTC(),
            "local",
            null,
            null);
    try (var log = LogCapture.start()) {
      assertThatCode(sweeps::atStart).doesNotThrowAnyException();
      assertThat(log.lines("sweep"))
          .extracting(line -> LogCapture.keys(line).get("sweep"))
          .containsExactly("release plan import", "support request close");
      assertThat(log.lines("sweep").getLast().getFormattedMessage())
          .isEqualTo(
              "The support request close sweep did not complete (IllegalStateException); the next"
                  + " run tries again");
      log.containsNone("synthetic storage outage");
    }
    verify(plans).syncInbox();
    verifyNoInteractions(cleanup);
  }

  /**
   * DLV-971: the start, the schedule and the owner's manual run share one method that answers what
   * the sweeps did.
   */
  @Test
  void theSharedSweepAnswersTheCountsAndTheManualRunLogsOneLineWithTheActor() {
    var clock = Clock.fixed(Instant.parse("2026-11-06T18:30:00Z"), ZoneOffset.UTC);
    when(devAccessCleanup.getIfAvailable()).thenReturn(cleanup);
    when(reviews.closeStaleRequests()).thenReturn(3);
    when(plans.syncInbox())
        .thenReturn(
            new DeliveryPlanService.Sync(
                true, new DeliveryPlanMerge.Counts(1, 0, 2, 0, 5, 0), 12, null));
    when(cleanup.run()).thenReturn(1);
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            new MaintenanceJobBeanRegistry(),
            clock,
            "local",
            null,
            null);
    var actor = UUID.randomUUID();
    SweepRunner.Result result;
    try (var log = LogCapture.start()) {
      result = sweeps.runNow(actor, SweepJob.ALL);
      var line = log.only("sweep");
      assertThat(line.getFormattedMessage())
          .isEqualTo(
              "Owner "
                  + actor.toString().substring(0, 8)
                  + " ran the all sweep now: 3 support request(s) closed, inbox merged into plan"
                  + " version 12, 1 waiting account(s) removed");
      assertThat(LogCapture.keys(line))
          .containsEntry("manual", true)
          .containsEntry("account.id", actor)
          .containsEntry("job", "all")
          .containsEntry("event.outcome", "success")
          .containsEntry("support.closed", 3)
          .containsEntry("inbox.merged", true)
          .containsEntry("admissions.removed", 1);
    }
    assertThat(result)
        .isEqualTo(new SweepRunner.Result("all", "2026-11-06T18:30:00Z", 3, true, "12", 1));
    var order = inOrder(reviews, plans, cleanup);
    order.verify(reviews).closeStaleRequests();
    order.verify(plans).syncInbox();
    order.verify(cleanup).run();
    // The scheduled run is the same sweep of every job, without the manual line.
    try (var log = LogCapture.start()) {
      sweeps.run();
      assertThat(log.lines("sweep")).isEmpty();
    }
    verify(reviews, times(2)).closeStaleRequests();
    verify(plans, times(2)).syncInbox();
    verify(cleanup, times(2)).run();
  }

  /** One job at a time: only that job runs and only its counts are filled. */
  @Test
  void aSingleJobRunsAloneAndAnswersOnlyItsCounts() {
    when(devAccessCleanup.getIfAvailable()).thenReturn(cleanup);
    when(reviews.closeStaleRequests()).thenReturn(2);
    when(plans.syncInbox())
        .thenReturn(new DeliveryPlanService.Sync(false, null, 5, "no inbox file"));
    when(cleanup.run()).thenReturn(4);
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            new MaintenanceJobBeanRegistry(),
            Clock.systemUTC(),
            "local",
            null,
            null);
    var actor = UUID.randomUUID();
    try (var log = LogCapture.start()) {
      var support = sweeps.runNow(actor, SweepJob.SUPPORT_REQUESTS);
      assertThat(support.job()).isEqualTo("support-requests");
      assertThat(support.supportRequestsClosed()).isEqualTo(2);
      assertThat(support.inboxMerged()).isNull();
      assertThat(support.inboxResult()).isNull();
      assertThat(support.admissionsRemoved()).isNull();
      assertThat(log.only("sweep").getFormattedMessage())
          .isEqualTo(
              "Owner "
                  + actor.toString().substring(0, 8)
                  + " ran the support-requests sweep now: 2 support request(s) closed, inbox not"
                  + " touched, no waiting account(s) removed");
      assertThat(LogCapture.keys(log.only("sweep")))
          .containsEntry("job", "support-requests")
          .containsEntry("manual", true);
    }
    verifyNoInteractions(plans, cleanup);
    var inbox = sweeps.runNow(actor, SweepJob.DELIVERY_INBOX);
    assertThat(inbox)
        .isEqualTo(
            new SweepRunner.Result(
                "delivery-inbox", inbox.ranAt(), null, false, "no inbox file", null));
    verify(reviews, times(1)).closeStaleRequests();
    verifyNoInteractions(cleanup);
    var cleaned = sweeps.runNow(actor, SweepJob.ADMISSIONS);
    assertThat(cleaned)
        .isEqualTo(new SweepRunner.Result("admissions", cleaned.ranAt(), null, null, null, 4));
    verify(plans, times(1)).syncInbox();
    assertThat(SweepJob.named("admissions")).isEqualTo(SweepJob.ADMISSIONS);
    assertThat(SweepJob.named("Admissions")).isNull();
    assertThat(SweepJob.named("")).isNull();
    assertThat(SweepJob.named(null)).isNull();
  }

  @Test
  void anInboxLeftAloneAnswersItsReasonAFailedSweepCountsZeroAndLocalModeHasNoAdmissionJob() {
    when(devAccessCleanup.getIfAvailable()).thenReturn(null);
    when(reviews.closeStaleRequests()).thenReturn(0);
    when(plans.syncInbox())
        .thenReturn(new DeliveryPlanService.Sync(false, null, 5, "no inbox file"));
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            new MaintenanceJobBeanRegistry(),
            Clock.systemUTC(),
            "local",
            null,
            null);
    var quiet = sweeps.sweep(SweepJob.ALL);
    assertThat(quiet.supportRequestsClosed()).isZero();
    assertThat(quiet.inboxMerged()).isFalse();
    assertThat(quiet.inboxResult()).isEqualTo("no inbox file");
    assertThat(quiet.admissionsRemoved()).as("no admission cleanup outside cloud mode").isNull();
    assertThat(Instant.parse(quiet.ranAt()))
        .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));
    var actor = UUID.randomUUID();
    assertThatThrownBy(() -> sweeps.runNow(actor, SweepJob.ADMISSIONS))
        .isInstanceOf(AccountFailure.class)
        .hasMessageContaining("cloud mode")
        .extracting("code")
        .isEqualTo("SWEEP_NOT_AVAILABLE");
    when(reviews.closeStaleRequests())
        .thenThrow(new IllegalStateException("synthetic storage outage"));
    when(plans.syncInbox()).thenThrow(new IllegalStateException("synthetic plan outage"));
    SweepRunner.Result failed;
    try (var log = LogCapture.start()) {
      failed = sweeps.runNow(actor, SweepJob.ALL);
      assertThat(log.lines("sweep"))
          .extracting(line -> LogCapture.keys(line).get("sweep"))
          .containsExactly("support request close", "delivery inbox sync", null);
      assertThat(log.lines("sweep").getLast().getFormattedMessage())
          .isEqualTo(
              "Owner "
                  + actor.toString().substring(0, 8)
                  + " ran the all sweep now: 0 support request(s) closed, inbox did not complete"
                  + " (IllegalStateException), no waiting account(s) removed");
      log.containsNone("synthetic storage outage", "synthetic plan outage");
    }
    assertThat(failed.supportRequestsClosed()).isZero();
    assertThat(failed.inboxMerged()).isFalse();
    assertThat(failed.inboxResult()).isEqualTo("did not complete (IllegalStateException)");
    assertThat(failed.admissionsRemoved()).isNull();
  }

  /**
   * DLV-998: a release without a plan file (no publication) gives the import nothing to do; the
   * start still sweeps.
   */
  @Test
  void startWithAReleaseImportThatHasNothingToImportStillSweeps() {
    when(devAccessCleanup.getIfAvailable()).thenReturn(null);
    when(plans.syncInbox())
        .thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
    when(release.run())
        .thenReturn(
            new DeliveryPlanReleaseImportJob.Outcome("skipped", "no plan file in the release", 0));
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            new MaintenanceJobBeanRegistry(),
            Clock.systemUTC(),
            "local",
            null,
            null);
    assertThatCode(sweeps::atStart).doesNotThrowAnyException();
    verify(release).run();
    verify(plans).syncInbox();
  }

  @Test
  void prodRunsOnlyTheSupportRequestCloseAtStartAndOnTheSchedule() {
    when(devAccessCleanup.getIfAvailable()).thenReturn(cleanup);
    var maintenance =
        mock(MaintenanceJobRegistry.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            maintenance,
            Clock.systemUTC(),
            "prod",
            null,
            null);
    sweeps.atStart();
    sweeps.run();
    verify(reviews, times(2)).closeStaleRequests();
    verifyNoInteractions(plans, cleanup, release, maintenance);
  }

  @Test
  void devRunsEveryJobAtStartAndOnTheSchedule() {
    when(devAccessCleanup.getIfAvailable()).thenReturn(cleanup);
    when(plans.syncInbox())
        .thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
    var maintenance =
        mock(MaintenanceJobRegistry.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    var sweeps =
        new SweepRunner(
            reviews,
            plans,
            release,
            devAccessCleanup,
            maintenance,
            Clock.systemUTC(),
            "dev",
            null,
            null);
    sweeps.atStart();
    verify(release).run();
    verify(plans).syncInbox();
    verify(cleanup).run();
    verify(maintenance).jobs();
  }
}
