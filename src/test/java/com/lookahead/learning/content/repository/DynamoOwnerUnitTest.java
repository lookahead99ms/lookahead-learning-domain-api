package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoOwnerUnitTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T06:00:00Z"), ZoneOffset.UTC);
    private final UUID owner = UUID.randomUUID();

    private void meta(long revision) {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(DynamoItems.item(DynamoItems.account(owner), "META", 1,
                Map.of("revision", DynamoItems.n(revision), "lastPlanNumber", DynamoItems.n(4)))).build());
    }

    @Test void aMissingMetaIsRefusedAndAnUnansweredReadIsUnavailable() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        var unit = new DynamoOwnerUnit(client, tables, clock);
        assertThatThrownBy(() -> unit.fence(owner)).isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("AUTHENTICATION_REQUIRED"));
        when(client.getItem(any(GetItemRequest.class))).thenThrow(SdkClientException.create("down"));
        assertThatThrownBy(() -> new DynamoOwnerUnit(client, tables, clock).fence(owner)).isInstanceOf(DynamoOwnerUnit.DynamoOwnerUnavailable.class);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(DynamoItems.item(DynamoItems.account(owner), "META", 2, Map.of("revision", DynamoItems.n(1)))).build());
        assertThatIllegalStateException().isThrownBy(() -> new DynamoOwnerUnit(client, tables, clock).fence(owner));
    }

    @Test void commitSendsTheFenceFirstThenOneStatementPerItemAndNothingWhenNothingWasWritten() {
        meta(7);
        var unit = new DynamoOwnerUnit(client, tables, clock);
        var fence = unit.fence(owner);
        assertThat(unit.fence(owner)).isSameAs(fence);
        assertThat(unit.hasWrites()).isFalse();
        assertThat(fence.allocatePlanNumber()).isEqualTo(5);
        assertThat(fence.lastPlanNumber()).isEqualTo(5);
        fence.countFreeCreation();
        assertThat(unit.hasWrites()).isTrue(); // moved counters are written even without an item statement
        unit.statement(tables.userAccountDetailsTableName(), "ACCOUNT#x", "PLAN#1", tx -> tx.insertOnly(tables.userAccountDetailsTableName(), DynamoItems.item("ACCOUNT#x", "PLAN#1", 1, Map.of("v", DynamoItems.n(1)))));
        unit.statement(tables.userAccountDetailsTableName(), "ACCOUNT#x", "PLAN#1", tx -> tx.insertOnly(tables.userAccountDetailsTableName(), DynamoItems.item("ACCOUNT#x", "PLAN#1", 1, Map.of("v", DynamoItems.n(2)))));
        assertThat(unit.state("k", () -> "first")).isEqualTo("first");
        assertThat(unit.state("k", () -> "second")).isEqualTo("first");
        unit.commit();
        var captor = ArgumentCaptor.forClass(ExecuteTransactionRequest.class);
        verify(client).executeTransaction(captor.capture());
        var statements = captor.getValue().transactStatements();
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0).statement()).startsWith("UPDATE").contains("\"revision\"=?", "\"lastPlanNumber\"=?", "\"freePlanCreations\"=?").endsWith("revision=?");
        assertThat(statements.get(0).parameters()).contains(DynamoItems.n(8), DynamoItems.n(5), DynamoItems.n(1), DynamoItems.n(7));
        assertThat(statements.get(1).statement()).startsWith("INSERT");
        assertThat(statements.get(1).parameters()).contains(DynamoItems.n(2));
        assertThat(captor.getValue().clientRequestToken()).isEqualTo(unit.id().toString());
        assertThatIllegalStateException().isThrownBy(unit::commit);
        assertThatIllegalStateException().isThrownBy(() -> unit.statement("t", "a", "b", tx -> { }));
        var empty = new DynamoOwnerUnit(client, tables, clock);
        empty.fence(owner);
        empty.commit();
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
        assertThat(empty.startedAt()).isEqualTo(clock.instant());
        assertThat(empty.now()).isEqualTo(clock.instant());
        assertThat(empty.tables()).isSameAs(tables);
        assertThat(empty.client()).isSameAs(client);
    }

    @Test void theDynamodbConfigurationSuppliesThisManager() {
        assertThat(new DynamoOwnerTransactionConfiguration().dynamoOwnerTransactionManager(client, tables)).isInstanceOf(DynamoOwnerTransactionManager.class);
    }

    @Test void aFailedConditionSurfacesAsConflict() {
        meta(1);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build(), CancellationReason.builder().code("None").build()).build());
        var unit = new DynamoOwnerUnit(client, tables, clock);
        unit.fence(owner);
        unit.statement("t", "a", "b", tx -> tx.insertOnly(tables.userAccountDetailsTableName(), DynamoItems.item("a", "b", 1, Map.of())));
        assertThatThrownBy(unit::commit).isInstanceOfSatisfying(DynamoWrites.Conflict.class, conflict -> assertThat(conflict.only(0)).isTrue());
    }

    @Test void runUsesTheBoundUnitInsideATransactionAndAPrivateOneOutside() {
        meta(1);
        var manager = new DynamoOwnerTransactionManager(client, tables, clock);
        var template = new TransactionTemplate(manager);
        assertThat(DynamoOwnerUnit.current()).isNull();
        template.executeWithoutResult(status -> {
            var bound = DynamoOwnerUnit.current();
            assertThat(bound).isNotNull();
            DynamoOwnerUnit same = DynamoOwnerUnit.run(client, tables, clock, unit -> unit);
            assertThat(same).isSameAs(bound);
            bound.statement("t", "a", "b", tx -> tx.insertOnly(tables.userAccountDetailsTableName(), DynamoItems.item("a", "b", 1, Map.of())));
            bound.fence(owner);
            verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
            // A nested REQUIRED transaction joins; REQUIRES_NEW gets its own unit and the outer one comes back.
            template.executeWithoutResult(inner -> assertThat(DynamoOwnerUnit.current()).isSameAs(bound));
            var fresh = new TransactionTemplate(manager);
            fresh.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            fresh.executeWithoutResult(inner -> assertThat(DynamoOwnerUnit.current()).isNotSameAs(bound));
            assertThat(DynamoOwnerUnit.current()).isSameAs(bound);
        });
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
        assertThat(DynamoOwnerUnit.current()).isNull();
        // A failure rolls back: nothing is sent.
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            DynamoOwnerUnit.current().statement("t", "a", "c", tx -> tx.insertOnly(tables.userAccountDetailsTableName(), DynamoItems.item("a", "c", 1, Map.of())));
            throw new IllegalStateException("validation failed");
        })).isInstanceOf(IllegalStateException.class);
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
        assertThat(DynamoOwnerUnit.current()).isNull();
        // Outside a transaction: a private unit commits right after the call.
        DynamoOwnerUnit.run(client, tables, clock, unit -> { unit.fence(owner); unit.statement("t", "a", "d", tx -> tx.insertOnly(tables.userAccountDetailsTableName(), DynamoItems.item("a", "d", 1, Map.of()))); return null; });
        verify(client, times(2)).executeTransaction(any(ExecuteTransactionRequest.class));
    }
}
