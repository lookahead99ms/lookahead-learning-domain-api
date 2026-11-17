package com.lookahead.domain.cloud;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.LogCapture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-969 cleanup on a mocked store; the conditional deletes themselves are proven by the DynamoDB suite. */
class CloudAdmissionCleanupTest {
    final AdmissionCleanupStore store=mock(AdmissionCleanupStore.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    final CloudAdmissionCleanup cleanup=new CloudAdmissionCleanup(store);

    @Test void expiryIsSharedWithTheWaitingList(){assertThat(CloudAdmissionCleanup.WAITING_EXPIRY.toHours()).isEqualTo(48);}
    @Test void expireReportsTheStoresCount() {
        when(store.expire()).thenReturn(2);
        assertThat(cleanup.expire()).isEqualTo(2);
        assertThat(cleanup.run()).isEqualTo(2);
        verify(store,times(2)).expire();
    }
    /** DLV-969: failures are logged by type only (never the message, which may carry a secret) and the next run tries again. */
    @Test void scheduledRunSwallowsFailuresSoTheSchedulerKeepsRunning() {
        when(store.expire()).thenThrow(new IllegalStateException("password=synthetic-secret"));
        try(var log=LogCapture.start()){
            assertThat(cleanup.run()).isZero();
            var failed=log.only("admission.cleanup");
            assertThat(failed.getLevel()).isEqualTo(Level.WARN);
            assertThat(failed.getFormattedMessage()).isEqualTo("Admission cleanup skipped this run: IllegalStateException; the next run tries again");
            assertThat(LogCapture.keys(failed)).containsEntry("reason","UNEXPECTED").containsEntry("error.type","IllegalStateException");
            log.containsNone("synthetic-secret");
        }
        assertThatThrownBy(cleanup::expire).isInstanceOf(IllegalStateException.class);
    }
}
