package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.job.MaintenanceJobBeanRegistry;
import com.lookahead.domain.job.MaintenanceJobRegistry;
import com.lookahead.domain.job.StudyPlanPurgeJob;
import com.lookahead.domain.log.LogCapture;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StudyPlanPurgeStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();

    @Test void aDamagedJobIsDeferredNotDeletedAndTheLimitIsBounded() {
        var purge = new StudyPlanPurgeStore(client, tables, Clock.systemUTC());
        assertThatIllegalArgumentException().isThrownBy(() -> purge.queued(0));
        assertThatIllegalArgumentException().isThrownBy(() -> purge.queued(26));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(
                DynamoItems.item(StudyPlanStore.PURGE_PARTITION, "job-1", 1, Map.of("owner", DynamoItems.s("not-a-uuid"), "planId", DynamoItems.s("x")))).build());
        var page = purge.queued(5);
        assertThat(page).hasSize(1);
        // DLV-1016: the store fails on the damaged deletion and writes nothing; the job catches, logs and leaves it queued.
        assertThatIllegalStateException().isThrownBy(() -> purge.purge(page.getFirst())).withMessage("Stored item has a malformed UUID in owner");
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
        var registry = new MaintenanceJobBeanRegistry();
        new StudyPlanPurgeJob(purge, registry);
        try (var log = LogCapture.start()) {
            assertThat(registry.jobs().getFirst().run(false)).isEqualTo(MaintenanceJobRegistry.Page.NOTHING);
            assertThat(log.messages()).containsExactly("Plan purge job deferred: IllegalStateException");
        }
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    /** DLV-1016: the store registers no job; its Spring constructor takes the connection and the table names only. */
    @Test void theSpringConstructorBuildsAStoreThatReadsOnePageWhenAsked() {
        var purge = new StudyPlanPurgeStore(client, tables);
        verifyNoInteractions(client);
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items().build());
        assertThat(purge.queued(StudyPlanPurgeStore.MAX_JOBS)).isEmpty();
        verify(client).query(any(QueryRequest.class));
    }
}
