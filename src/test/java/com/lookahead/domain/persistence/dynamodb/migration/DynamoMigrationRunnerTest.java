package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-970 (P9): the runner's lock handling around a failed step, and status as one ledger read; the store is a mock. */
class DynamoMigrationRunnerTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T06:00:00Z"), ZoneOffset.UTC);
    private final DynamoMigrationStep failing = new DynamoMigrationStep() {
        @Override public int version() { return 1; }
        @Override public String name() { return "first"; }
        @Override public String definition() { return "test step"; }
        @Override public void apply(DynamoDbClient client, DynamoTableNames tables) { throw new IllegalStateException("apply failed"); }
        @Override public void verify(DynamoDbClient client, DynamoTableNames tables) { }
    };
    private final DynamoMigrationRunner runner = new DynamoMigrationRunner(client, DynamoTableNames.local(), new DynamoMigrationCatalog(List.of(failing)), clock);

    @Test void aFailedReleaseIsAttachedToTheStepFailureNeverSubstitutedForIt() {
        // Empty store: no lock, no CURRENT, no history. Acquire and renew succeed; the step fails; the release's store is gone.
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().build());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class)))
                .thenReturn(ExecuteTransactionResponse.builder().build(), ExecuteTransactionResponse.builder().build())
                .thenThrow(SdkClientException.create("store went away"));
        assertThatThrownBy(() -> runner.migrate("run-a")).isInstanceOf(IllegalStateException.class).hasMessage("apply failed")
                .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement().isInstanceOf(DynamoWrites.Unavailable.class));
        verify(client, times(3)).executeTransaction(any(ExecuteTransactionRequest.class));
        verify(client, never()).putItem(any(PutItemRequest.class));
        verify(client, never()).updateItem(any(UpdateItemRequest.class));
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test void statusIsOneCheckedLedgerReadAndNeverWrites() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().build());
        assertThat(runner.status()).isEqualTo(new DynamoMigrationRunner.Outcome(0, 0, List.of(), List.of(), false));
        verify(client, times(1)).getItem(any(GetItemRequest.class));
        verify(client, times(1)).query(any(QueryRequest.class));
        verifyNoMoreInteractions(client);
    }
}
