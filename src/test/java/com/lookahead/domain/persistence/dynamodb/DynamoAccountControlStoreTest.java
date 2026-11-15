package com.lookahead.domain.persistence.dynamodb;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoAccountControlStoreTest {
    private final DynamoDbClient client=mock(DynamoDbClient.class,withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    private final DynamoTableNames tables=DynamoTableNamesTest.names("dev");
    private final DynamoAccountControlStore store=new DynamoAccountControlStore(client,tables);
    private final UUID target=UUID.randomUUID();
    private final DynamoAccountControlStore.Owner owner=new DynamoAccountControlStore.Owner(UUID.randomUUID(),"https://issuer.test/pool","owner");
    private final Instant now=Instant.parse("2026-11-03T00:00:00Z");
    private DynamoAccountControlStore.Command command(){return new DynamoAccountControlStore.Command(owner,target,UUID.randomUUID(),1,DynamoAccountControlStore.Action.REMOVE,now);}

    @Test void invalidCommandsAreRejectedBeforeIo() {
        assertThatNullPointerException().isThrownBy(()->store.read(null));
        assertThatNullPointerException().isThrownBy(()->store.transition(null));
        assertThatNullPointerException().isThrownBy(()->new DynamoAccountControlStore(null,tables));
        assertThatNullPointerException().isThrownBy(()->new DynamoAccountControlStore(client,null));
        for(String value:new String[]{null,"","  ","é".repeat(2000)})
            assertThatIllegalArgumentException().isThrownBy(()->new DynamoAccountControlStore.Owner(UUID.randomUUID(),value,"subject"));
        for(long revision:new long[]{0,-1,Long.MAX_VALUE})
            assertThatIllegalArgumentException().isThrownBy(()->new DynamoAccountControlStore.Command(owner,target,UUID.randomUUID(),revision,DynamoAccountControlStore.Action.REMOVE,now));
        for(Instant time:List.of(Instant.EPOCH.minusSeconds(1),Instant.parse("+10000-01-01T00:00:00Z")))
            assertThatIllegalArgumentException().isThrownBy(()->new DynamoAccountControlStore.Command(owner,target,UUID.randomUUID(),1,DynamoAccountControlStore.Action.REMOVE,time));
        verifyNoInteractions(client);
    }

    @Test void stateAndTerminalMarkerAreReadInOneTransaction() {
        when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenAnswer(call->{
            TransactGetItemsRequest request=call.getArgument(0);assertThat(request.transactItems()).hasSize(2);
            assertThat(request.transactItems().get(0).get().tableName()).isEqualTo(tables.userAccountDetailsTableName());
            assertThat(request.transactItems().get(1).get().tableName()).isEqualTo(tables.administratorAuditEventsTableName());
            return TransactGetItemsResponse.builder().responses(ItemResponse.builder().build(),ItemResponse.builder().build()).build();
        });
        assertThat(store.read(target)).isEmpty();verify(client).transactGetItems(any(TransactGetItemsRequest.class));verifyNoMoreInteractions(client);
    }

    @Test void incompleteInvalidStateAndTerminalMarkerFailClosed() {
        when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenReturn(TransactGetItemsResponse.builder().build());
        assertThatIllegalStateException().isThrownBy(()->store.read(target));
        for(var row:List.of(changed(control(),"accountState",s("UNKNOWN")),changed(control(),"authGeneration",n(-1)),changed(control(),"author",s("not bool")),changed(control(),"issuer",n(1)))) {
            snapshot(row,Map.of());assertThatThrownBy(()->store.read(target)).isInstanceOf(RuntimeException.class);
        }
        var marker=changed(control(),"accountState",s("REMOVED"));marker.put("operationId",s(UUID.randomUUID().toString()));
        for(String field:List.of("admitted","author","managedTest")) {
            var invalid=changed(marker,field,AttributeValue.builder().bool(!field.equals("admitted")).build());snapshot(Map.of(),invalid);
            assertThatIllegalStateException().isThrownBy(()->store.read(target));
        }
        marker.put("operationId",s("1-1-1-1-1"));snapshot(Map.of(),marker);
        assertThatIllegalStateException().isThrownBy(()->store.read(target));
    }

    @Test void missingOwnerBindingStopsBeforeReadingTarget() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        assertThatIllegalStateException().isThrownBy(()->store.transition(command())).withMessageContaining("Owner identity");
        verify(client,never()).transactGetItems(any(TransactGetItemsRequest.class));verify(client,never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void allProtectedFactsUseInsertAndProviderIntentDoesNotUseSdkReplayToken() {
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call->{GetItemRequest request=call.getArgument(0);assertThat(request.consistentRead()).isTrue();
            return request.tableName().equals(tables.protectedOwnerIdentityTableName())
                ?GetItemResponse.builder().item(Map.of("schemaVersion",n(1),"issuer",s(owner.issuer()),"subject",s(owner.subject()))).build():GetItemResponse.builder().build();});
        snapshot(control(),Map.of());
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call->{
            ExecuteTransactionRequest request=call.getArgument(0);assertThat(request.clientRequestToken()).isNull();assertThat(request.transactStatements()).hasSize(5);
            assertThat(request.transactStatements().getFirst().statement()).contains("AND revision=?","AND authGeneration=?","AND managedTest=false");
            for(var statement:request.transactStatements().subList(1,5))assertThat(statement.statement()).startsWith("INSERT INTO");
            throw DynamoDbException.builder().statusCode(403).message("denied").build();
        });
        assertThatThrownBy(()->store.transition(command())).isInstanceOf(DynamoWrites.Unavailable.class).hasCauseInstanceOf(DynamoDbException.class)
                .satisfies(failure->assertThat(failure.getMessage()).doesNotContain("denied"));
        verify(client,times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
    }
    private Map<String,AttributeValue> control(){var row=new HashMap<String,AttributeValue>();row.putAll(Map.of("schemaVersion",n(1),"revision",n(1),"authGeneration",n(0),"accountState",s("ACTIVE"),"issuer",s(owner.issuer()),"subject",s("target"),"username",s("synthetic")));row.putAll(Map.of("admitted",AttributeValue.builder().bool(true).build(),"author",AttributeValue.builder().bool(false).build(),"managedTest",AttributeValue.builder().bool(false).build()));return row;}
    private Map<String,AttributeValue> changed(Map<String,AttributeValue> row,String key,AttributeValue value){var copy=new HashMap<>(row);copy.put(key,value);return copy;}
    private void snapshot(Map<String,AttributeValue> control,Map<String,AttributeValue> marker){when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenReturn(TransactGetItemsResponse.builder().responses(ItemResponse.builder().item(control).build(),ItemResponse.builder().item(marker).build()).build());}
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
}
