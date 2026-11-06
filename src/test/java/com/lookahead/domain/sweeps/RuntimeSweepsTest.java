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
}
