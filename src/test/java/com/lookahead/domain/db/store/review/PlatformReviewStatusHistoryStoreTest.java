package com.lookahead.domain.db.store.review;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.review.PlatformReviewFixtures;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-970: the two review.platform database leftovers on DynamoDB: record numbers (a fenced PartiQL UPDATE) and status history (one insert-only item). */
class PlatformReviewStatusHistoryStoreTest {
    static final DynamoTableNames TABLES = DynamoTableNames.local();
    static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));

    /** P1: no UpdateItem ADD (the runtime role is PartiQL-only): a strong read, then UPDATE under the value read, re-read and retried on a lost race. */
    @Test void recordNumbersAdvanceOneCounterPerKindThroughAFencedPartiqlUpdate() {
        var counters = new HashMap<String, Long>(Map.of("SEQUENCE#review-record", 11L, "SEQUENCE#support-request", 33L));
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
        var numbers = new PlatformReviewRecordNumberStore(client, TABLES, attempt -> { throw new AssertionError("no retry without a conflict"); });
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
        assertThat(PlatformReviewRecordNumberStore.partition("request'); DROP TABLE x; --")).isEqualTo("SEQUENCE#review-record");
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
        var reads = new AtomicInteger();
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> GetItemResponse.builder()
                .item(DynamoItems.item("SEQUENCE#review-record", "COUNTER", 1, Map.of("lastAssigned", DynamoItems.n(40 + reads.incrementAndGet())))).build());
        var conflict = TransactionCanceledException.builder().cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build()).build();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(conflict).thenReturn(ExecuteTransactionResponse.builder().build());
        var waits = new AtomicInteger();
        var numbers = new PlatformReviewRecordNumberStore(client, TABLES, attempt -> waits.incrementAndGet());
        // Read 41 and lose the race, read 42 and win: the answer is 43, the value this writer wrote.
        assertThat(numbers.next("review")).isEqualTo(43);
        assertThat(waits).hasValue(1);
        verify(client, times(2)).executeTransaction(any(ExecuteTransactionRequest.class));
        // A race lost every time surfaces as the store's conflict (409 through the handlers) after the retry budget.
        reset(client);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(DynamoItems.item("SEQUENCE#review-record", "COUNTER", 1, Map.of("lastAssigned", DynamoItems.n(1)))).build());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(conflict);
        assertThatThrownBy(() -> numbers.next("review")).isInstanceOf(DynamoWrites.Conflict.class);
        verify(client, times(PlatformReviewRecordNumberStore.ATTEMPTS)).executeTransaction(any(ExecuteTransactionRequest.class));
        // A store failure surfaces as Unavailable (503 through the handlers), never as a number.
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.builder().message("socket").build());
        assertThatThrownBy(() -> numbers.next("review")).isInstanceOf(DynamoWrites.Unavailable.class);
        when(client.getItem(any(GetItemRequest.class))).thenThrow(SdkClientException.builder().message("socket").build());
        assertThatThrownBy(() -> numbers.next("review")).isInstanceOf(SdkClientException.class);
        // A malformed counter fails closed before any write.
        reset(client);
        for (var item : List.of(Map.of("pk", DynamoItems.s("SEQUENCE#review-record"), "sk", DynamoItems.s("COUNTER"), "schemaVersion", DynamoItems.n(1)),
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
        var history = new PlatformReviewStatusHistoryStore(client, TABLES, Clock.fixed(NOW, ZoneOffset.UTC));
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
        assertThat(second.parameters().get(secondKeys.indexOf("'pk': ?")).s()).isEqualTo(PlatformReviewStatusHistoryStore.partition("request", "SUP-000001"));
    }

    /** DLV-1016: the store throws and writes no line; the service that records the change (here the delivery plan's, creating DLV-42) logs it and goes on. */
    @Test void aHistoryThatWasNotWrittenIsTheStoresExceptionAndTheServiceLogsItWithoutTheSdkMessage(@TempDir Path root) throws Exception {
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.builder().message("socket to 127.0.0.1:4400").build());
        var history = new PlatformReviewStatusHistoryStore(client, TABLES, Clock.systemUTC());
        try (var log = LogCapture.start()) {
            assertThatThrownBy(() -> history.record("DLV-42", "ticket", UUID.randomUUID(), null, "backlog", false)).isInstanceOf(DynamoWrites.Unavailable.class);
            assertThat(log.lines()).isEmpty();
        }
        var mapper = JsonMapper.builder().build();
        var owner = new AccountPrincipal(UUID.randomUUID(), "owner@lookahead.test", "Owner", true);
        var access = mock(StaffAccessService.class);
        when(access.permissions(owner)).thenReturn(StaffPermissions.forRoles(true, Set.of()));
        PlatformReviewFixtures.seedPlan(root);
        var plans = PlatformReviewFixtures.delivery(root, mapper, access, history, Clock.systemUTC(), "dev");
        var item = mapper.createObjectNode();
        item.put("title", "New work"); item.put("summary", "Why"); item.put("type", "story"); item.put("statusId", "backlog"); item.put("priorityId", "P2"); item.put("stageId", "stage-1");
        item.putArray("labels").add("web");
        try (var log = LogCapture.start()) {
            var created = plans.create(owner, 7L, item);
            assertThat(created.version()).as("the change itself is saved").isEqualTo(8);
            String id = created.item().path("id").asString();
            var line = log.only("platform-review.history");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "HISTORY_NOT_WRITTEN").containsEntry("target.id", id).containsEntry("event.outcome", "failure")
                    .containsEntry("error.type", "Unavailable < SdkClientException");
            assertThat(line.getFormattedMessage()).isEqualTo("Status history for " + id + " was not written (Unavailable < SdkClientException); the change itself is saved");
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            log.containsNone("4400");
        }
    }
}
