package com.lookahead.domain.persistence.dynamodb;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoWritesTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final Map<String, AttributeValue> item = DynamoItems.item("ACCOUNT#a", "META", 1, Map.of("revision", DynamoItems.n(4), "name", DynamoItems.s("Ann")));
    private final Map<String, AttributeValue> key = DynamoItems.key("ACCOUNT#a", "META");

    @Test void rendersPartiqlStatementsInOrderWithBoundParametersAndAnOptionalToken() {
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenReturn(ExecuteTransactionResponse.builder().build());
        var writes = DynamoWrites.transaction(client)
                .insertOnly("audit", item)
                .updateAtRevision("accounts", key, Map.of("revision", DynamoItems.n(5), "name", DynamoItems.s("Bea")), 4)
                .update("accounts", key, Map.of("x", DynamoItems.n(1), "old", DynamoItems.nul()), "accountState=? AND admitted=?", DynamoItems.s("ACTIVE"), DynamoItems.bool(true))
                .delete("accounts", DynamoItems.key("A", "C"), "revision=?", DynamoItems.n(1))
                .delete("accounts", DynamoItems.key("A", "D"), null)
                .check("owner", DynamoItems.key("LOCK", "L"), 1, "runId=?", DynamoItems.s("x"));
        assertThat(writes.size()).isEqualTo(6);
        assertThat(writes.statements()).containsExactly(
                "INSERT INTO \"audit\" VALUE {'name': ?, 'pk': ?, 'revision': ?, 'schemaVersion': ?, 'sk': ?}",
                "UPDATE \"accounts\" SET \"name\"=? SET \"revision\"=? WHERE pk=? AND sk=? AND revision=?",
                "UPDATE \"accounts\" SET \"old\"=? SET \"x\"=? WHERE pk=? AND sk=? AND accountState=? AND admitted=?",
                "DELETE FROM \"accounts\" WHERE pk=? AND sk=? AND revision=?",
                "DELETE FROM \"accounts\" WHERE pk=? AND sk=?",
                "UPDATE \"owner\" SET schemaVersion=? WHERE pk=? AND sk=? AND schemaVersion=? AND runId=?");
        writes.commit("token-1");
        var captor = ArgumentCaptor.forClass(ExecuteTransactionRequest.class);
        verify(client).executeTransaction(captor.capture());
        var statements = captor.getValue().transactStatements();
        assertThat(captor.getValue().clientRequestToken()).isEqualTo("token-1");
        assertThat(statements.get(0).parameters()).extracting(value -> value.s() != null ? value.s() : value.n())
                .containsExactly("Ann", "ACCOUNT#a", "4", "1", "META");
        assertThat(statements.get(1).parameters()).extracting(value -> value.s() != null ? value.s() : value.n())
                .containsExactly("Bea", "5", "ACCOUNT#a", "META", "4");
        assertThat(statements.get(2).parameters()).hasSize(6);
        assertThat(statements.get(5).parameters()).extracting(value -> value.s() != null ? value.s() : value.n())
                .containsExactly("1", "LOCK", "L", "1", "x");
    }

    @Test void classifyIsTheOneMappingLibrariesWithTheirOwnTransactionsUse() {
        var cancelled = TransactionCanceledException.builder().cancellationReasons(CancellationReason.builder().code("None").build(),
                CancellationReason.builder().code("ConditionalCheckFailed").build()).build();
        assertThat(DynamoWrites.classify(cancelled)).isInstanceOfSatisfying(DynamoWrites.Conflict.class, conflict -> {
            assertThat(conflict.reasons()).containsExactly("None", "ConditionalCheckFailed");
            assertThat(conflict.getCause()).isNull();
        });
        var failed = ConditionalCheckFailedException.builder().message("x").build();
        assertThat(DynamoWrites.classify(failed)).isInstanceOfSatisfying(DynamoWrites.Conflict.class, conflict -> assertThat(conflict.reasons()).containsExactly("ConditionalCheckFailed"));
        var uncancelled = TransactionCanceledException.builder().build();
        assertThat(DynamoWrites.classify(uncancelled)).isInstanceOf(DynamoWrites.Unavailable.class).hasCause(uncancelled);
        var socket = SdkClientException.builder().message("socket").build();
        assertThat(DynamoWrites.classify(socket)).isInstanceOf(DynamoWrites.Unavailable.class).hasCause(socket).hasMessage("DynamoDB write unavailable: SdkClientException");
        verifyNoInteractions(client);
    }

    @Test void refusesMalformedInputsBeforeAnyRequest() {
        var writes = DynamoWrites.transaction(client);
        assertThatThrownBy(() -> writes.updateAtRevision("accounts", key, Map.of("name", DynamoItems.s("x")), 4)).hasMessage("Update must carry revision + 1");
        assertThatThrownBy(() -> writes.updateAtRevision("accounts", key, Map.of("revision", DynamoItems.n(9)), 4)).hasMessage("Update must carry revision + 1");
        assertThatThrownBy(() -> writes.update("accounts", key, Map.of("pk", DynamoItems.s("x")), null)).hasMessage("Key attributes cannot be updated");
        assertThatThrownBy(() -> writes.update("accounts", key, Map.of(), null)).hasMessage("An update needs something to set");
        assertThatThrownBy(() -> writes.update("accounts", key, Map.of("bad name", DynamoItems.s("x")), null)).hasMessage("Invalid attribute name");
        assertThatThrownBy(() -> writes.update("accounts", key, Map.of("x", DynamoItems.s("x")), "revision=?")).hasMessage("WHERE placeholders and parameters differ");
        assertThatThrownBy(() -> writes.update("accounts", key, Map.of("x", DynamoItems.s("x")), "revision=1; DELETE")).hasMessage("Invalid WHERE clause");
        assertThatThrownBy(() -> writes.delete("accounts", Map.of("pk", DynamoItems.s("x")), null)).hasMessage("A key is pk and sk");
        assertThatThrownBy(() -> writes.insertOnly("accounts", Map.of("pk", DynamoItems.s("x")))).hasMessage("An item needs pk and sk");
        assertThatThrownBy(() -> writes.insertOnly("a\"b", item)).hasMessage("Invalid table name");
        assertThatThrownBy(() -> DynamoWrites.transaction(client).commit()).hasMessage("Nothing to commit");
        var full = DynamoWrites.transaction(client);
        for (int i = 0; i < DynamoWrites.MAX_ITEMS; i++) full.insertOnly("t", item);
        assertThatThrownBy(() -> full.insertOnly("t", item)).hasMessageContaining("at most 100");
        verifyNoInteractions(client);
    }

    @Test void aFailedConditionOrDuplicateIsAConflictNamingTheStatementAndAnythingElseIsUnavailable() {
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("None").build(), CancellationReason.builder().code("ValidationError").message("Duplicate primary key exists in table").build()).build());
        var conflict = catchThrowableOfType(DynamoWrites.Conflict.class, () -> DynamoWrites.transaction(client).insertOnly("a", item).insertOnly("b", item).commit());
        assertThat(conflict.reasons()).containsExactly("None", "DuplicateItem");
        assertThat(conflict.only(1)).isTrue();
        assertThat(conflict.only(0)).isFalse();
        assertThat(conflict.only(5)).isFalse();
        assertThat(conflict).hasMessage("DynamoDB transaction conflict: None, DuplicateItem");

        reset(client);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        assertThat(catchThrowableOfType(DynamoWrites.Conflict.class, () -> DynamoWrites.transaction(client).insertOnly("a", item).commit()).reasons())
                .containsExactly("ConditionalCheckFailed");

        reset(client);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ValidationError").build()).build());
        assertThatThrownBy(() -> DynamoWrites.transaction(client).insertOnly("a", item).commit()).isInstanceOf(DynamoWrites.Unavailable.class)
                .hasMessage("DynamoDB write unavailable: TransactionCanceledException");

        reset(client);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.builder().message("socket").build());
        assertThatThrownBy(() -> DynamoWrites.transaction(client).insertOnly("a", item).commit()).isInstanceOf(DynamoWrites.Unavailable.class)
                .hasMessage("DynamoDB write unavailable: SdkClientException");

        reset(client);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().build(), CancellationReason.builder().code("TransactionConflict").build()).build());
        assertThat(catchThrowableOfType(DynamoWrites.Conflict.class, () -> DynamoWrites.transaction(client).insertOnly("a", item).commit()).reasons())
                .containsExactly("None", "TransactionConflict");
    }
}
