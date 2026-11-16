package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoPlanPurgeTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();

    @Test void aDamagedJobIsDeferredNotDeletedAndTheLimitIsBounded() {
        var purge = new DynamoPlanPurge(client, tables, Clock.systemUTC());
        assertThatIllegalArgumentException().isThrownBy(() -> purge.cleanPage(0));
        assertThatIllegalArgumentException().isThrownBy(() -> purge.cleanPage(26));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(
                DynamoItems.item(DynamoPlanRepository.PURGE_PARTITION, "job-1", 1, Map.of("owner", DynamoItems.s("not-a-uuid"), "planId", DynamoItems.s("x")))).build());
        assertThat(purge.cleanPage(5)).isZero();
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void registersWithTheSweepRunnerWhenLaneDProvidesOne() {
        var registered = new java.util.HashMap<String, Runnable>();
        PlanMaintenanceJobs jobs = registered::put;
        @SuppressWarnings("unchecked") ObjectProvider<PlanMaintenanceJobs> provider = mock(ObjectProvider.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
        doAnswer(invocation -> { invocation.<java.util.function.Consumer<PlanMaintenanceJobs>>getArgument(0).accept(jobs); return null; }).when(provider).ifAvailable(any());
        new DynamoPlanPurge(client, tables, provider);
        assertThat(registered).containsKey("plan-purge");
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items().build());
        registered.get("plan-purge").run();
        verify(client).query(any(QueryRequest.class));
    }
}
