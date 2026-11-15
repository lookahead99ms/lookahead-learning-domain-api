package com.lookahead.domain.review.platform;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-970: the two review.platform database leftovers on DynamoDB: record numbers (a fenced PartiQL UPDATE) and status history (one insert-only item). */
class DynamoReviewRecordsTest {
    static final DynamoTableNames TABLES = DynamoTableNames.local();
    static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));

    /** P1: no UpdateItem ADD (the runtime role is PartiQL-only): a strong read, then UPDATE under the value read, re-read and retried on a lost race. */
    @Test void recordNumbersAdvanceOneCounterPerKindThroughAFencedPartiqlUpdate() {
        var counters = new java.util.HashMap<String, Long>(Map.of("SEQUENCE#review-record", 11L, "SEQUENCE#support-request", 33L));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            assertThat(request.consistentRead()).isTrue();
            assertThat(request.tableName()).isEqualTo(TABLES.userAccountDetailsTableName());
            assertThat(request.key().get("sk").s()).isEqualTo("COUNTER");
            Long last = counters.get(request.key().get("pk").s());
            return GetItemResponse.builder().item(last == null ? Map.of() : DynamoItems.item(request.key().get("pk").s(), "COUNTER", 1, Map.of("lastAssigned", DynamoItems.n(last)))).build();
        });
        var captor = ArgumentCaptor.forClass(ExecuteTransactionRequest.class);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenReturn(ExecuteTransactionResponse.builder().build());
        var numbers = new ReviewRecordNumbers.Dynamo(client, TABLES, attempt -> { throw new AssertionError("no retry without a conflict"); });
        assertThat(numbers.next("review")).isEqualTo(12);
        assertThat(numbers.next("request")).isEqualTo(34);
        // The SQL store's kind selection: anything but request (null included) is the review counter.
        assertThat(numbers.next(null)).isEqualTo(12);
        verify(client, times(3)).executeTransaction(captor.capture());
        var update = captor.getAllValues().getFirst().transactStatements();
        assertThat(update).hasSize(1);
        assertThat(update.getFirst().statement()).isEqualTo("UPDATE \"" + TABLES.userAccountDetailsTableName() + "\" SET \"lastAssigned\"=? WHERE pk=? AND sk=? AND \"lastAssigned\"=?");
        assertThat(update.getFirst().parameters()).extracting(value -> value.s() != null ? value.s() : value.n()).containsExactly("12", "SEQUENCE#review-record", "COUNTER", "11");
        assertThat(captor.getAllValues().get(1).transactStatements().getFirst().parameters()).extracting(value -> value.s() != null ? value.s() : value.n()).containsExactly("34", "SEQUENCE#support-request", "COUNTER", "33");
        assertThat(ReviewRecordNumbers.Dynamo.partition("request'); DROP TABLE x; --")).isEqualTo("SEQUENCE#review-record");
        verify(client, never()).updateItem(any(UpdateItemRequest.class));
        // A counter that was never migrated is inserted at 1, never silently started elsewhere.
        counters.clear();
        assertThat(numbers.next("review")).isEqualTo(1);
        verify(client, times(4)).executeTransaction(captor.capture());
        var insert = captor.getAllValues().getLast().transactStatements().getFirst();
        assertThat(insert.statement()).startsWith("INSERT INTO \"" + TABLES.userAccountDetailsTableName() + "\" VALUE {'lastAssigned': ?, 'pk': ?, 'schemaVersion': ?, 'sk': ?}");
        assertThat(insert.parameters().getFirst().n()).isEqualTo("1");
    }

    @Test void aLostCounterRaceRereadsAndRetriesAndAnOutageOrMalformedCounterIsNeverANumber() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> GetItemResponse.builder()
                .item(DynamoItems.item("SEQUENCE#review-record", "COUNTER", 1, Map.of("lastAssigned", DynamoItems.n(40 + reads.incrementAndGet())))).build());
        var conflict = TransactionCanceledException.builder().cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build()).build();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(conflict).thenReturn(ExecuteTransactionResponse.builder().build());
        var waits = new java.util.concurrent.atomic.AtomicInteger();
        var numbers = new ReviewRecordNumbers.Dynamo(client, TABLES, attempt -> waits.incrementAndGet());
        // Read 41 and lose the race, read 42 and win: the answer is 43, the value this writer wrote.
        assertThat(numbers.next("review")).isEqualTo(43);
        assertThat(waits).hasValue(1);
        verify(client, times(2)).executeTransaction(any(ExecuteTransactionRequest.class));
        // A race lost every time surfaces as the store's conflict (409 through the handlers) after the retry budget.
        reset(client);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(DynamoItems.item("SEQUENCE#review-record", "COUNTER", 1, Map.of("lastAssigned", DynamoItems.n(1)))).build());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(conflict);
        assertThatThrownBy(() -> numbers.next("review")).isInstanceOf(com.lookahead.domain.persistence.dynamodb.DynamoWrites.Conflict.class);
        verify(client, times(ReviewRecordNumbers.Dynamo.ATTEMPTS)).executeTransaction(any(ExecuteTransactionRequest.class));
        // A store failure surfaces as Unavailable (503 through the handlers), never as a number.
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.builder().message("socket").build());
        assertThatThrownBy(() -> numbers.next("review")).isInstanceOf(com.lookahead.domain.persistence.dynamodb.DynamoWrites.Unavailable.class);
        when(client.getItem(any(GetItemRequest.class))).thenThrow(SdkClientException.builder().message("socket").build());
        assertThatThrownBy(() -> numbers.next("review")).isInstanceOf(SdkClientException.class);
        // A malformed counter fails closed before any write.
        reset(client);
        for (var item : java.util.List.of(Map.of("pk", DynamoItems.s("SEQUENCE#review-record"), "sk", DynamoItems.s("COUNTER"), "schemaVersion", DynamoItems.n(1)),
                DynamoItems.item("SEQUENCE#review-record", "COUNTER", 2, Map.of("lastAssigned", DynamoItems.n(1))),
                DynamoItems.item("SEQUENCE#review-record", "COUNTER", 1, Map.of("lastAssigned", DynamoItems.n(-1))),
                DynamoItems.item("SEQUENCE#review-record", "COUNTER", 1, Map.of("lastAssigned", DynamoItems.s("x"))))) {
            when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
            assertThatThrownBy(() -> numbers.next("review")).isInstanceOf(IllegalStateException.class);
        }
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void statusHistoryIsOneInsertOnlyAuditItemWithTheActorOrSystem() {
        var captor = ArgumentCaptor.forClass(ExecuteTransactionRequest.class);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenReturn(ExecuteTransactionResponse.builder().build());
        var history = new ReviewStatusHistory.Dynamo(client, TABLES, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID actor = UUID.randomUUID();
        history.record("REV-000001", "review", actor, "new", "accepted", true);
        history.record("SUP-000001", "request", null, null, "closed", false);
        verify(client, times(2)).executeTransaction(captor.capture());
        var first = captor.getAllValues().get(0).transactStatements();
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().statement()).startsWith("INSERT INTO \"" + TABLES.administratorAuditEventsTableName() + "\" VALUE {")
                .contains("'actorId': ?", "'fromStatus': ?", "'toStatus': ?", "'noteSet': ?", "'actor': ?", "'action': ?", "'occurredAt': ?").doesNotContain("recordedAt");
        var keys = List.of(first.getFirst().statement().replaceAll("^.*VALUE \\{", "").replace("}", "").split(", "));
        var values = first.getFirst().parameters();
        assertThat(values.get(keys.indexOf("'pk': ?")).s()).isEqualTo("RECORD#" + DynamoItems.encode("review") + "#" + DynamoItems.encode("REV-000001"));
        assertThat(values.get(keys.indexOf("'sk': ?")).s()).startsWith("STATUS#" + DynamoItems.time(NOW) + "#");
        assertThat(values.get(keys.indexOf("'actor': ?")).s()).isEqualTo(actor.toString());
        assertThat(values.get(keys.indexOf("'noteSet': ?")).bool()).isTrue();
        assertThat(values.get(keys.indexOf("'schemaVersion': ?")).n()).isEqualTo("1");
        var second = captor.getAllValues().get(1).transactStatements().getFirst();
        assertThat(second.statement()).doesNotContain("'actorId'", "'fromStatus'");
        var secondKeys = List.of(second.statement().replaceAll("^.*VALUE \\{", "").replace("}", "").split(", "));
        assertThat(second.parameters().get(secondKeys.indexOf("'actor': ?")).s()).isEqualTo("system");
        assertThat(second.parameters().get(secondKeys.indexOf("'pk': ?")).s()).isEqualTo(ReviewStatusHistory.Dynamo.partition("request", "SUP-000001"));
    }

    @Test void aHistoryThatWasNotWrittenIsLoggedWithoutTheSdkMessageAndNeverThrown() {
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.builder().message("socket to 127.0.0.1:4400").build());
        var history = new ReviewStatusHistory.Dynamo(client, TABLES, Clock.systemUTC());
        try (var log = LogCapture.start()) {
            assertThatCode(() -> history.record("DLV-42", "ticket", UUID.randomUUID(), null, "backlog", false)).doesNotThrowAnyException();
            var line = log.only("platform-review.history");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "HISTORY_NOT_WRITTEN").containsEntry("target.id", "DLV-42");
            assertThat(line.getFormattedMessage()).isEqualTo("Status history for DLV-42 was not written (Unavailable < SdkClientException); the change itself is saved");
            log.containsNone("4400");
        }
    }
}
