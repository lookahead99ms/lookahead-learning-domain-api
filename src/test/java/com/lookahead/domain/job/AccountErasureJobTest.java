package com.lookahead.domain.job;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.access.AccountErasureStore;
import com.lookahead.domain.log.LogCapture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountErasureJobTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static AccountErasureStore.Queued queued(Instant at) { return new AccountErasureStore.Queued(UUID.randomUUID(), UUID.randomUUID(), at); }

    /** DLV-1016: the loop and the 10-minute grace rule are the job's; the store answers the queue, "pending" and its clock. */
    @Test void aPageFinishesPendingJobsDropsStaleEntriesAndKeepsYoungOnes() {
        var erasure = mock(AccountErasureStore.class);
        var pending = queued(NOW.minusSeconds(3600)); var stale = queued(NOW.minus(AccountErasureJob.GRACE)); var young = queued(NOW.minus(AccountErasureJob.GRACE).plusSeconds(1));
        var undated = queued(null);
        when(erasure.now()).thenReturn(NOW);
        when(erasure.queued(AccountErasureStore.MAX_QUEUED)).thenReturn(List.of(pending, stale, young, undated));
        when(erasure.pending(pending.id())).thenReturn(true);
        var job = new AccountErasureJob(erasure);
        assertThat(job.name()).isEqualTo("account-erasure");
        assertThat(job.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(3, 0, 0, false));
        // The job item is read twice before the one erasure: the page's check and the one just before erasing.
        verify(erasure, times(2)).pending(pending.id()); verify(erasure).erase(pending.actor(), pending.id());
        verify(erasure, times(1)).erase(ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(erasure).drop(stale.id()); verify(erasure).drop(undated.id());
        verify(erasure, never()).drop(young.id()); verify(erasure, never()).drop(pending.id());
    }

    @Test void aFailedErasureStaysQueuedAndStopsTheSweepEvenOnAFullPage() {
        var erasure = mock(AccountErasureStore.class);
        var page = new ArrayList<AccountErasureStore.Queued>();
        for (int n = 0; n < AccountErasureStore.MAX_QUEUED; n++) page.add(queued(NOW));
        when(erasure.now()).thenReturn(NOW);
        when(erasure.queued(AccountErasureStore.MAX_QUEUED)).thenReturn(page);
        when(erasure.pending(ArgumentMatchers.any())).thenReturn(true);
        var job = new AccountErasureJob(erasure);
        assertThat(job.run(false)).as("a full page that all finished: more to do").isEqualTo(new MaintenanceJobRegistry.Page(AccountErasureStore.MAX_QUEUED, 0, 0, true));
        // DLV-1016: the job, not the store, catches a failed erasure and writes the line; the entry stays queued.
        var first = page.get(0);
        doThrow(new IllegalStateException("plans down")).when(erasure).erase(first.actor(), first.id());
        try (var log = LogCapture.start()) {
            assertThat(job.run(true)).isEqualTo(new MaintenanceJobRegistry.Page(AccountErasureStore.MAX_QUEUED - 1, 0, 1, false));
            var line = log.only("account.erasure");
            assertThat(line.getFormattedMessage()).isEqualTo("Erasure of deleted account " + first.id() + " did not complete (IllegalStateException); the next run tries again");
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(LogCapture.keys(line)).containsEntry("event.outcome", "failure").containsEntry("reason", "UNEXPECTED")
                .containsEntry("target.id", first.id()).containsEntry("error.type", "IllegalStateException");
        }
        // A job another run finished between the page's check and the erasure is counted as not finished and nothing is erased for it.
        var second = page.get(1);
        doNothing().when(erasure).erase(first.actor(), first.id());
        when(erasure.pending(second.id())).thenReturn(true, false);
        clearInvocations(erasure);
        assertThat(job.run(true)).isEqualTo(new MaintenanceJobRegistry.Page(AccountErasureStore.MAX_QUEUED - 1, 0, 1, false));
        verify(erasure, never()).erase(second.actor(), second.id());
        verify(erasure, never()).drop(ArgumentMatchers.any());
    }
}
