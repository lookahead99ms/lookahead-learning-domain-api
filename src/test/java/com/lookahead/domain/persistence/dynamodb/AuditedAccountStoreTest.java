package com.lookahead.domain.persistence.dynamodb;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditedAccountStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    private final AuditedAccountStore store = new AuditedAccountStore(client, DynamoTableNamesTest.names("dev"));
    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000970");
    private final UUID operationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final AuditedAccountStore.AuditEvent event = new AuditedAccountStore.AuditEvent(
            "system:membership-verifier", "membership.activated", "Verified gateway event", Instant.parse("2026-11-02T12:00:00Z"));

    private AuditedAccountStore.Mutation mutation(long revision) {
        return new AuditedAccountStore.Mutation(accountId, "membership:example", operationId, revision,
                SdkBytes.fromUtf8String("{\"active\":true}"), event);
    }

    @BeforeEach void emptyReads() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenReturn(ExecuteTransactionResponse.builder().build());
    }

    @Test void createCommitsAccountAuditAndDurableReceiptTogether() {
        assertThat(store.commit(mutation(0))).isEqualTo(new AuditedAccountStore.CommitResult(1, false));
        var request = transaction();
        assertThat(request.clientRequestToken()).isEqualTo(UUID.nameUUIDFromBytes((accountId + ":" + operationId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        assertThat(request.transactStatements()).hasSize(3);
        assertThat(request.transactStatements().getFirst().statement()).startsWith("INSERT INTO \"lookahead-dev-user-account-details\"");
        var audit = request.transactStatements().get(1);
        assertThat(audit.statement()).startsWith("INSERT INTO \"lookahead-dev-administrator-audit-events\"");
        assertThat(audit.parameters().get(1).s()).isEqualTo("EVENT#2026-11-02T12:00:00.000000000Z#" + operationId);
        assertThat(audit.parameters()).contains(s("system:membership-verifier"));
        assertThat(request.transactStatements().get(2).statement()).doesNotContain("ttl");
        assertThat(request.transactStatements()).allSatisfy(statement -> assertThat(statement.statement())
                .doesNotContain("protected-owner-identity", "user-study-plan-details"));
        verify(client).getItem(any(GetItemRequest.class));
        verifyNoMoreInteractions(client);
    }

    @Test void updateFencesTheExactRevisionAndSchemaWithBoundParameters() {
        var input = new AuditedAccountStore.Mutation(accountId, "a'\"; DELETE FROM x", operationId, 7,
                SdkBytes.fromUtf8String("new value"), event);
        assertThat(store.commit(input).revision()).isEqualTo(8);
        var update = transaction().transactStatements().getFirst();
        assertThat(update.statement()).contains("WHERE pk=? AND sk=? AND revision=? AND schemaVersion=1")
                .doesNotContain(input.recordId(), "DELETE FROM");
        assertThat(update.parameters().get(0)).isEqualTo(n(8));
        assertThat(update.parameters().get(4)).isEqualTo(n(7));
        var reads = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(reads.capture());
        assertThat(reads.getValue().consistentRead()).isTrue();
        assertThat(reads.getValue().key().get("pk")).isEqualTo(s("ACCOUNT#" + accountId));
    }

    @Test void lostSuccessResponseIsRecoveredOnlyFromDurableReceiptAndReplayNeverWritesAgain() {
        var receipt = new AtomicReference<Map<String, AttributeValue>>(Map.of());
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> GetItemResponse.builder().item(receipt.get()).build());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call -> {
            receipt.set(receiptOf(call.getArgument(0)));
            throw SdkClientException.create("synthetic response lost");
        });
        assertThat(store.commit(mutation(3))).isEqualTo(new AuditedAccountStore.CommitResult(4, true));
        assertThat(store.commit(mutation(3))).isEqualTo(new AuditedAccountStore.CommitResult(4, true));
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
        verify(client, times(3)).getItem(any(GetItemRequest.class));
    }

    @Test void replayKeyCannotBeReusedForDifferentPayloadRecordRevisionOrAudit() {
        store.commit(mutation(3));
        var receipt = receiptOf(transaction());
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(receipt).build());
        var original = mutation(3);
        for (var changed : new AuditedAccountStore.Mutation[]{mutation(4),
                new AuditedAccountStore.Mutation(accountId, "other", operationId, 3, original.payload(), event),
                new AuditedAccountStore.Mutation(accountId, original.recordId(), operationId, 3, SdkBytes.fromUtf8String("different"), event),
                new AuditedAccountStore.Mutation(accountId, original.recordId(), operationId, 3, original.payload(),
                        new AuditedAccountStore.AuditEvent(event.actor(), event.action(), "other reason", event.occurredAt()))}) {
            assertThatIllegalStateException().isThrownBy(() -> store.commit(changed)).withMessageContaining("different mutation");
        }
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void conflictsAndUnknownFailuresWithoutReceiptsNeverBecomeSuccess() {
        var conflict = TransactionCanceledException.builder().message("synthetic revision conflict").build();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(conflict);
        assertThatThrownBy(() -> store.commit(mutation(1))).isSameAs(conflict);
        var unknown = SdkClientException.create("synthetic timeout");
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(unknown);
        assertThatThrownBy(() -> store.commit(mutation(1))).isSameAs(unknown);
        verify(client, times(2)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void preservesOriginalFailureWhenReconciliationAlsoFails() {
        var timeout = SdkClientException.create("synthetic timeout");
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(timeout);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build())
                .thenThrow(SdkClientException.create("synthetic read unavailable"));
        assertThatThrownBy(() -> store.commit(mutation(0))).isSameAs(timeout)
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
    }

    @Test void readFailuresStopMutationBeforeAnyWrite() {
        when(client.getItem(any(GetItemRequest.class))).thenThrow(SdkClientException.create("synthetic unavailable"));
        assertThatThrownBy(() -> store.commit(mutation(0))).isInstanceOf(SdkClientException.class);
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void readsAreStrongOwnerScopedAndRejectMalformedOrUnknownSchema() {
        assertThat(store.read(accountId, "plan")).isEmpty();
        var item = new HashMap<String, AttributeValue>(Map.of("schemaVersion", n(1), "revision", n(2),
                "payload", AttributeValue.builder().b(SdkBytes.fromUtf8String("{}" )).build()));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> GetItemResponse.builder().item(item).build());
        assertThat(store.read(accountId, "plan").orElseThrow().revision()).isEqualTo(2);
        for (AttributeValue bad : new AttributeValue[]{n(0), n(-1), s("bad"), s("9999999999999999999999999")}) {
            item.put("revision", bad);
            assertThatIllegalStateException().isThrownBy(() -> store.read(accountId, "plan"));
        }
        item.remove("revision");
        assertThatIllegalStateException().isThrownBy(() -> store.read(accountId, "plan"));
        item.put("revision", n(2));
        item.put("payload", s("not binary"));
        assertThatIllegalStateException().isThrownBy(() -> store.read(accountId, "plan"));
        item.remove("payload");
        assertThatIllegalStateException().isThrownBy(() -> store.read(accountId, "plan"));
        item.put("schemaVersion", n(2));
        assertThatIllegalStateException().isThrownBy(() -> store.read(accountId, "plan"));
        var requests = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, atLeastOnce()).getItem(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(r -> {
            assertThat(r.consistentRead()).isTrue();
            assertThat(r.key()).containsEntry("pk", s("ACCOUNT#" + accountId)).containsEntry("sk", s("RECORD#v1#4:plan"));
        });
    }

    @Test void malformedReceiptCannotProveSuccess() {
        store.commit(mutation(0));
        var receipt = new HashMap<>(receiptOf(transaction()));
        receipt.put("revision", n(9));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(receipt).build());
        assertThatIllegalStateException().isThrownBy(() -> store.commit(mutation(0))).withMessageContaining("receipt");
    }

    @Test void rejectsOversizedEmptyInvalidUtf8AndOverflowingInputsBeforeIo() {
        for (long revision : new long[]{-1, Long.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> mutation(revision));
        }
        for (byte[] bytes : new byte[][]{new byte[0], new byte[245761]}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new AuditedAccountStore.Mutation(accountId, "x", operationId, 0,
                    SdkBytes.fromByteArray(bytes), event));
        }
        for (String record : new String[]{"", " ", "é".repeat(129), "\ud800"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new AuditedAccountStore.Mutation(accountId, record, operationId,
                    0, SdkBytes.fromUtf8String("{}"), event));
        }
        for (Instant time : new Instant[]{Instant.EPOCH.minusSeconds(1), Instant.parse("+10000-01-01T00:00:00Z")}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new AuditedAccountStore.AuditEvent("actor", "action", "reason", time));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditedAccountStore.AuditEvent(null, "action", "reason", Instant.EPOCH));
        verifyNoInteractions(client);
    }

    @Test void snapshotsCallerBytesBeforeFingerprintAndSubmission() {
        byte[] mutable = new byte[]{1, 2, 3};
        var input = new AuditedAccountStore.Mutation(accountId, "x", operationId, 0,
                SdkBytes.fromByteArrayUnsafe(mutable), event);
        mutable[0] = 9;
        store.commit(input);
        assertThat(transaction().transactStatements().getFirst().parameters().get(3).b().asByteArray())
                .containsExactly(1, 2, 3);
    }

    private ExecuteTransactionRequest transaction() {
        var request = ArgumentCaptor.forClass(ExecuteTransactionRequest.class);
        verify(client).executeTransaction(request.capture());
        return request.getValue();
    }
    private static Map<String, AttributeValue> receiptOf(ExecuteTransactionRequest request) {
        var parameters = request.transactStatements().get(2).parameters();
        return Map.of("schemaVersion", n(1), "fingerprint", parameters.get(2), "revision", parameters.get(3));
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
