package com.lookahead.domain.persistence.dynamodb;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoStaffAliasBootstrapTest {
    private final DynamoDbClient client=mock(DynamoDbClient.class,withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    private final DynamoTableNames tables=DynamoTableNamesTest.names("dev");
    private final DynamoStaffAliasBootstrap bootstrap=new DynamoStaffAliasBootstrap(client,tables);
    private final UUID id=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-11-03T00:00:00Z");
    private final List<ItemResponse> absent=java.util.Collections.nCopies(10,ItemResponse.builder().build());

    @Test void rejectsInvalidArgumentsBeforeIo() {
        assertThatNullPointerException().isThrownBy(()->new DynamoStaffAliasBootstrap(null,tables));
        assertThatNullPointerException().isThrownBy(()->new DynamoStaffAliasBootstrap(client,null));
        assertThatNullPointerException().isThrownBy(()->bootstrap.initialize(null,now,1));
        assertThatNullPointerException().isThrownBy(()->bootstrap.initialize(id,null,1));
        for(int pages:new int[]{0,10001})assertThatIllegalArgumentException().isThrownBy(()->bootstrap.initialize(id,now,pages));
        for(Instant time:List.of(Instant.EPOCH.minusSeconds(1),Instant.parse("+10000-01-01T00:00:00Z")))
            assertThatIllegalArgumentException().isThrownBy(()->bootstrap.initialize(id,time,1));
        verifyNoInteractions(client);
    }

    @Test void readsAreAtomicAndWritesAreTenDistinctInsertsWithoutRequestToken() {
        when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenAnswer(call->{
            TransactGetItemsRequest request=call.getArgument(0);assertThat(request.transactItems()).hasSize(10);
            assertThat(request.transactItems().stream().map(x->x.get().tableName()+x.get().key()).distinct().count()).isEqualTo(10);
            return TransactGetItemsResponse.builder().responses(absent).build();
        });
        when(client.scan(any(ScanRequest.class))).thenAnswer(call->{ScanRequest request=call.getArgument(0);
            assertThat(request.consistentRead()).isTrue();assertThat(request.limit()).isEqualTo(100);
            return ScanResponse.builder().build();});
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call->{
            ExecuteTransactionRequest request=call.getArgument(0);assertThat(request.clientRequestToken()).isNull();
            assertThat(request.transactStatements()).hasSize(10);
            for(var statement:request.transactStatements())assertThat(statement.statement()).startsWith("INSERT INTO");
            return ExecuteTransactionResponse.builder().build();});
        assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(id,now,10)).withMessageContaining("no durable");
    }

    @Test void incompleteAndMalformedDurableReadsFailClosed() {
        when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenReturn(TransactGetItemsResponse.builder().build());
        assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(id,now,1)).withMessageContaining("Incomplete");
        var base=Map.of("schemaVersion",n(1),"initializationId",s(id.toString()),"lastAssigned",n(0),"revision",n(0));
        var bad=new ArrayList<Map<String,AttributeValue>>();
        for(String field:List.of("schemaVersion","initializationId","lastAssigned","revision")){var row=new HashMap<>(base);row.remove(field);bad.add(row);}
        for(AttributeValue value:List.of(n(-1),n(2147483648L),s("bad"))){var row=new HashMap<>(base);row.put("lastAssigned",value);bad.add(row);}
        for(var row:bad){var records=new ArrayList<>(absent);records.set(0,ItemResponse.builder().item(row).build());
            when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenReturn(TransactGetItemsResponse.builder().responses(records).build());
            assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(id,now,1));}
        verify(client,never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void malformedAndRepeatedContinuationNeverPassEmptyInspection() {
        when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenReturn(TransactGetItemsResponse.builder().responses(absent).build());
        for(var key:List.of(Map.of("pk",s("x")),Map.of("pk",s(""),"sk",s("x")),Map.of("pk",s("x"),"sk",n(2)),Map.of("pk",s("x"),"sk",s("")),Map.of("pk",s("x"),"sk",s("y")))){
            when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().lastEvaluatedKey(key).build());
            assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(id,now,10)).withMessageContaining("continuation");
        }
        verify(client,never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
}
