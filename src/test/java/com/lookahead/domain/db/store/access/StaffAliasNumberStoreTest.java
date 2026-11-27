package com.lookahead.domain.db.store.access;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNamesTest;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StaffAliasNumberStoreTest {
    private final DynamoDbClient client=mock(DynamoDbClient.class,withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables=DynamoTableNamesTest.names("dev");
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"),ZoneOffset.UTC);
    private final UUID id=UUID.randomUUID();
    private final String initialized=UUID.randomUUID().toString();

    @Test void rejectsUnsupportedRolesAndNullDependenciesBeforeIo() {
        var store=new StaffAliasNumberStore(client,tables,clock);
        for(String role:new String[]{null,"","owner","Admin","admin#injection"}) {
            assertThatIllegalArgumentException().isThrownBy(()->store.assign(id,role));
            assertThatIllegalArgumentException().isThrownBy(()->store.read(id,role));
        }
        assertThatNullPointerException().isThrownBy(()->store.assign(null,"admin"));
        assertThatNullPointerException().isThrownBy(()->new StaffAliasNumberStore(null,tables,clock));
        assertThatNullPointerException().isThrownBy(()->new StaffAliasNumberStore(client,null,clock));
        assertThatNullPointerException().isThrownBy(()->new StaffAliasNumberStore(client,tables,null));
        assertThatNullPointerException().isThrownBy(()->new StaffAliasNumberStore(client,tables,clock,null));
        verifyNoInteractions(client);
    }

    @Test void allReadsAreStrongAndOnlyTheCounterUsesUpdate() {
        prepareReads();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call->{
            ExecuteTransactionRequest request=call.getArgument(0);
            assertThat(request.clientRequestToken()).isNull();
            var statements=request.transactStatements();assertThat(statements).hasSize(3);
            assertThat(statements.get(0).statement()).startsWith("UPDATE \""+tables.userAccountDetailsTableName())
                    .contains("AND lastAssigned=? AND revision=? AND initializationId=?");
            for(var statement:statements.subList(1,3))assertThat(statement.statement()).startsWith("INSERT INTO \""+tables.administratorAuditEventsTableName());
            throw DynamoDbException.builder().statusCode(403).message("denied").build();
        });
        assertThatThrownBy(()->new StaffAliasNumberStore(client,tables,clock).assign(id,"admin")).isInstanceOf(DynamoWrites.Unavailable.class).hasCauseInstanceOf(DynamoDbException.class);
        verify(client,times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void invalidClockCannotWrite() {
        prepareReads();
        for(Instant time:new Instant[]{Instant.EPOCH.minusSeconds(1),Instant.parse("+10000-01-01T00:00:00Z")}) {
            assertThatIllegalStateException().isThrownBy(()->new StaffAliasNumberStore(client,tables,Clock.fixed(time,ZoneOffset.UTC)).assign(id,"admin"));
        }
        verify(client,never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void nonConflictCancellationDoesNotSpendRetryBudget() {
        prepareReads();
        for(String code:new String[]{"ValidationError","ProvisionedThroughputExceeded","AccessDenied"}) {
            var failure=TransactionCanceledException.builder().cancellationReasons(CancellationReason.builder().code(code).build()).build();
            when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(failure);
            assertThatThrownBy(()->new StaffAliasNumberStore(client,tables,clock,attempt->{throw new AssertionError("Must not retry");}).assign(id,"admin"))
                    .isInstanceOf(DynamoWrites.Unavailable.class).hasCause(failure);
        }
        var noReasons=TransactionCanceledException.builder().build();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(noReasons);
        assertThatThrownBy(()->new StaffAliasNumberStore(client,tables,clock).assign(id,"admin")).isInstanceOf(DynamoWrites.Unavailable.class).hasCause(noReasons);
        verify(client,times(4)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void combinedCounterAndInsertRaceRetriesButUnrelatedValidationDoesNot() {
        prepareReads();
        var waits=new AtomicInteger();
        var conflict=TransactionCanceledException.builder().cancellationReasons(
                CancellationReason.builder().code("ConditionalCheckFailed").build(),
                CancellationReason.builder().code("None").build(),
                CancellationReason.builder().code("ValidationError").build()).build();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(conflict);
        // P3: the exhausted retry budget surfaces as the 409 conflict (ConditionalCheckFailed is among the reasons), never the raw cancellation.
        assertThatThrownBy(()->new StaffAliasNumberStore(client,tables,clock,attempt->waits.incrementAndGet()).assign(id,"admin")).isInstanceOf(DynamoWrites.Conflict.class)
                .satisfies(failure->assertThat(((DynamoWrites.Conflict)failure).reasons()).containsExactly("ConditionalCheckFailed","None","ValidationError"));
        assertThat(waits).hasValue(4);
        verify(client,times(5)).executeTransaction(any(ExecuteTransactionRequest.class));
        clearInvocations(client);
        for(String first:new String[]{"None","ValidationError","AccessDenied"}) {
            var failure=TransactionCanceledException.builder().cancellationReasons(
                    CancellationReason.builder().code(first).build(),
                    CancellationReason.builder().code("None").build(),
                    CancellationReason.builder().code("ValidationError").build()).build();
            when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(failure);
            assertThatThrownBy(()->new StaffAliasNumberStore(client,tables,clock,attempt->{throw new AssertionError("Must not retry");}).assign(id,"admin"))
                    .isInstanceOf(DynamoWrites.Unavailable.class).hasCause(failure);
        }
        verify(client,times(3)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void interruptedBackoffStopsAndRestoresThreadFlag() {
        prepareReads();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call->{
            Thread.currentThread().interrupt();
            throw TransactionCanceledException.builder().cancellationReasons(CancellationReason.builder().code("TransactionConflict").build()).build();
        });
        try {
            assertThatIllegalStateException().isThrownBy(()->new StaffAliasNumberStore(client,tables,clock).assign(id,"admin"))
                    .withMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {Thread.interrupted();}
        verify(client,times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    private void prepareReads() {
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call->{
            GetItemRequest request=call.getArgument(0);assertThat(request.consistentRead()).isTrue();
            String sk=request.key().get("sk").s();
            if(sk.equals("COUNTER"))return GetItemResponse.builder().item(Map.of("schemaVersion",n(1),"lastAssigned",n(0),"revision",n(0),"initializationId",s(initialized))).build();
            if(sk.equals("INITIALIZED"))return GetItemResponse.builder().item(Map.of("schemaVersion",n(1),"initializationId",s(initialized))).build();
            return GetItemResponse.builder().build();
        });
    }
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
}
