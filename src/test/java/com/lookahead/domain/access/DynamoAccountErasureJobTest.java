package com.lookahead.domain.access;

import com.lookahead.domain.sweeps.MaintenanceJobs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamoAccountErasureJobTest {
    @Test void aPageCountsFinishedAndDroppedAsCleanedAndStopsTheSweepAfterAFailure() {
        var erasure = mock(DynamoAccountErasure.class);
        when(erasure.resumePage(DynamoAccountErasure.MAX_QUEUED))
                .thenReturn(new DynamoAccountErasure.Resumed(2, 1, 0, true))
                .thenReturn(new DynamoAccountErasure.Resumed(1, 0, 1, true))
                .thenReturn(new DynamoAccountErasure.Resumed(0, 0, 0, false));
        var job = new DynamoAccountErasureJob(erasure);
        assertThat(job.name()).isEqualTo("account-erasure");
        assertThat(job.run(false)).isEqualTo(new MaintenanceJobs.Page(3, 0, 0, true));
        assertThat(job.run(true)).isEqualTo(new MaintenanceJobs.Page(1, 0, 1, false));
        assertThat(job.run(true)).isEqualTo(new MaintenanceJobs.Page(0, 0, 0, false));
        verify(erasure, times(3)).resumePage(DynamoAccountErasure.MAX_QUEUED);
    }
}
