package com.lookahead.domain.persistence.dynamodb;

import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoStaffAliasVerifierTest {
    private final DynamoDbClient client=mock(DynamoDbClient.class);
    private final DynamoTableNames tables=DynamoTableNamesTest.names("dev");
    private final DynamoStaffAliasVerifier verifier=new DynamoStaffAliasVerifier(client,tables);
    private final DynamoStaffAliasVerifier.Limits limits=new DynamoStaffAliasVerifier.Limits(2,10,10);
    private final String id=UUID.randomUUID().toString();
    private final String time="2026-11-03T12:00:00Z";
    private static final List<String> ROLES=List.of("co-author","admin","reviewer","tester","learner");

    @Test void invalidInputsCannotReadAnything() {
        for(int[] values:new int[][]{{0,1,1},{101,1,1},{1,0,1},{1,10001,1},{1,1,0},{1,1,100001}})
            assertThatIllegalArgumentException().isThrownBy(()->new DynamoStaffAliasVerifier.Limits(values[0],values[1],values[2]));
        assertThatNullPointerException().isThrownBy(()->verifier.verify(null));
        assertThatNullPointerException().isThrownBy(()->new DynamoStaffAliasVerifier(null,tables));
        assertThatNullPointerException().isThrownBy(()->new DynamoStaffAliasVerifier(client,null));
        verifyNoInteractions(client);
    }

    @Test void strongBoundedFilteredScanContinuesThroughEmptyPagesAndNeverWrites() {
        counters();var cursor=Map.of("pk",s("ACCOUNT#unrelated"),"sk",s("EVENT#1"));
        when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().lastEvaluatedKey(cursor).build(),
                ScanResponse.builder().items(markers()).build());
        assertThat(verifier.verify(limits).pages()).isEqualTo(2);
        var captor=org.mockito.ArgumentCaptor.forClass(ScanRequest.class);verify(client,times(2)).scan(captor.capture());
        for(var request:captor.getAllValues()) {
            assertThat(request.consistentRead()).isTrue();assertThat(request.limit()).isEqualTo(2);
            assertThat(request.filterExpression()).contains("begins_with");assertThat(request.tableName()).isEqualTo(tables.administratorAuditEventsTableName());
        }
        assertThat(captor.getAllValues().get(1).exclusiveStartKey()).isEqualTo(cursor);
        verify(client,times(10)).getItem(any(GetItemRequest.class));verifyNoMoreInteractions(client);
    }

    @Test void incompleteOrRepeatingCursorCannotReturnSuccess() {
        counters();
        for(var cursor:List.of(Map.of("pk",s("x")),Map.of("pk",s(""),"sk",s("x")),Map.of("pk",s("x"),"sk",s("")),Map.of("pk",n(1),"sk",s("x")))) {
            when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().lastEvaluatedKey(cursor).build());
            assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits));
        }
        when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().lastEvaluatedKey(Map.of("pk",s("x"),"sk",s("y"))).build());
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("continuation");
    }

    @Test void malformedAliasKeysNumbersAndTimesFailClosedWithoutEchoingIdentity() {
        counters();
        var base=alias();
        List<Map<String,AttributeValue>> bad=new ArrayList<>();
        for(String field:List.of("pk","sk","schemaVersion","number","assignedAt")) {var item=new HashMap<>(base);item.remove(field);bad.add(item);}
        for(String pk:List.of("STAFF_ALIAS#bad","STAFF_ALIAS#1-1-1-1-1","unexpected"))bad.add(changed(base,"pk",s(pk)));
        for(String sk:List.of("WRONG#admin","ROLE#owner"))bad.add(changed(base,"sk",s(sk)));
        for(AttributeValue num:List.of(n(0),n(-1),n(2147483648L),s("bad")))bad.add(changed(base,"number",num));
        for(String value:List.of("bad","1969-01-01T00:00:00Z","+10000-01-01T00:00:00Z","2026-11-03T12:00:00.000Z"))bad.add(changed(base,"assignedAt",s(value)));
        for(var item:bad) {
            when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(item).build());
            assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).satisfies(error->assertThat(error.getMessage()).doesNotContain(id));
        }
    }

    @Test void malformedCounterAndClaimRecordsFailClosed() {
        for(String field:List.of("schemaVersion","lastAssigned","revision","initializationId")) {
            var item=new HashMap<>(counter());item.remove(field);
            when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
            assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits));
        }
        for(var item:List.of(changed(counter(),"lastAssigned",n(-1)),changed(counter(),"revision",s("bad")),changed(counter(),"initializationId",s("invalid")))) {
            when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
            assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits));
        }
        counters();
        for(String sk:List.of("NUMBER#1","NUMBER#0000000000","NUMBER#2147483648","UNKNOWN")) {
            when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(changed(claim(),"sk",s(sk))).build());
            assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits));
        }
    }

    @Test void duplicateRecordsOnScanAreRejectedRatherThanCountedTwice() {
        counters();
        for(var item:List.of(alias(),claim(),markers().getFirst())) {
            when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(item,item).build());
            assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("Duplicate");
        }
    }

    @Test void counterRevisionOnlyChangeInvalidatesInspection() {
        counters();var before=GetItemResponse.builder().item(counter()).build();
        var after=GetItemResponse.builder().item(changed(counter(),"revision",n(1))).build();
        when(client.getItem(any(GetItemRequest.class))).thenReturn(before,before,before,before,before,after,after,after,after,after);
        when(client.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(markers()).build());
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("changed during");
    }

    private void counters(){when(client.getItem(any(GetItemRequest.class))).thenAnswer(call->{
        GetItemRequest request=call.getArgument(0);assertThat(request.consistentRead()).isTrue();
        assertThat(request.tableName()).isEqualTo(tables.userAccountDetailsTableName());
        return GetItemResponse.builder().item(counter()).build();
    });}
    private Map<String,AttributeValue> counter(){return Map.of("schemaVersion",n(1),"lastAssigned",n(1),"revision",n(0),"initializationId",s(id));}
    private Map<String,AttributeValue> alias(){return Map.of("pk",s("STAFF_ALIAS#"+id),"sk",s("ROLE#admin"),"schemaVersion",n(1),"number",n(1),"assignedAt",s(time));}
    private Map<String,AttributeValue> claim(){return Map.of("pk",s("STAFF_ROLE#admin"),"sk",s("NUMBER#0000000001"),"schemaVersion",n(1),"accountId",s(id),"assignedAt",s(time));}
    private List<Map<String,AttributeValue>> markers(){return ROLES.stream().map(role->Map.of("pk",s("STAFF_ROLE#"+role),"sk",s("INITIALIZED"),"schemaVersion",n(1),"initializationId",s(id))).toList();}
    private Map<String,AttributeValue> changed(Map<String,AttributeValue> base,String key,AttributeValue value){var result=new HashMap<>(base);result.put(key,value);return result;}
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
}
