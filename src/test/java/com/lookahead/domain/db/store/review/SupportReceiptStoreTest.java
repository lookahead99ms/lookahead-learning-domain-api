package com.lookahead.domain.db.store.review;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.util.SupportFeedbackRules;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class SupportReceiptStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final SupportReceiptStore receipts = new SupportReceiptStore(client, tables, Clock.fixed(Instant.parse("2026-11-10T06:00:00Z"), ZoneOffset.UTC));
    private final UUID owner = UUID.randomUUID();

    @Test void aReservationThatKeepsLosingTheOwnerRevisionGivesUpAfterThreeAttempts() {
        when(client.getItem(argThat((GetItemRequest r) -> r != null && r.key().get("sk").s().equals("META"))))
                .thenReturn(GetItemResponse.builder().item(DynamoItems.item(DynamoItems.account(owner), "META", 1, Map.of("revision", DynamoItems.n(1)))).build());
        when(client.getItem(argThat((GetItemRequest r) -> r != null && r.key().get("sk").s().startsWith("SUPPORT#")))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items().build());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build()).build());
        assertThatThrownBy(() -> receipts.reserve(owner.toString(), "key-1234567890123456", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW)).isInstanceOf(DynamoWrites.Conflict.class);
        verify(client, times(3)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void acceptanceAndReleaseOfAMissingOrConfirmedKeyWriteNothing() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        receipts.accepted(owner.toString(), "key-1234567890123456");
        receipts.release(owner.toString(), "key-1234567890123456");
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(DynamoItems.item(DynamoItems.account(owner), "SUPPORT#x", 1,
                Map.of("status", DynamoItems.s("accepted"), "createdAt", DynamoItems.s("2026-11-10T06:00:00Z")))).build());
        receipts.release(owner.toString(), "key-1234567890123456");
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
        receipts.accepted(owner.toString(), "key-1234567890123456");
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
    }
}
