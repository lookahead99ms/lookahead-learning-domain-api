package com.lookahead.domain.persistence.dynamodb;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.MockMakers;

class StudyPlanDocumentRetirementTest {
    private final DynamoDbClient client=mock(DynamoDbClient.class,withSettings().mockMaker(MockMakers.PROXY));
    private final StudyPlanDocumentRetirement store=new StudyPlanDocumentRetirement(client,DynamoTableNamesTest.names("dev"));
    private static final String PARTITION="WORK#RETIRED-DOCUMENT#00";
    private final StudyPlanDocumentStore.DocumentId id=new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(),UUID.randomUUID());

    @Test void invalidBoundsAndCursorsNeverReachStorage() {
        for(int limit:new int[]{0,-1,26,Integer.MAX_VALUE}) assertThatIllegalArgumentException().isThrownBy(()->store.cleanPage(limit,null));
        for(String key:new String[]{""," ","x".repeat(1025)}) assertThatIllegalArgumentException().isThrownBy(()->store.cleanPage(1,key));
        verifyNoInteractions(client);
    }
    @Test void oversizedOrNonadvancingQueryResponsesFailBeforeCleanup() {
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(Map.of(),Map.of()).build());
        assertThatIllegalStateException().isThrownBy(()->store.cleanPage(1,null));
        for(var cursor:List.of(Map.of("pk",s("wrong"),"sk",s("b")),Map.of("pk",s(PARTITION),"sk",s("a")),
                Map.of("pk",s(PARTITION),"sk",s("b"),"extra",s("value")),Map.of("pk",s(PARTITION)))) {
            when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().lastEvaluatedKey(cursor).build());
            assertThatIllegalStateException().isThrownBy(()->store.cleanPage(1,"a"));
        }
        verify(client,never()).getItem(any(GetItemRequest.class));verify(client,never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }
    @Test void strongBoundedQueryPreservesOpaqueContinuationAcrossDeletedJobs() {
        when(client.query(any(QueryRequest.class))).thenAnswer(invocation->{
            QueryRequest request=invocation.getArgument(0);assertThat(request.consistentRead()).isTrue();assertThat(request.limit()).isEqualTo(25);
            assertThat(request.scanIndexForward()).isTrue();assertThat(request.exclusiveStartKey()).containsEntry("sk",s("prior"));
            return QueryResponse.builder().lastEvaluatedKey(Map.of("pk",s(PARTITION),"sk",s("z"))).build();
        });
        var page=store.cleanPage(25,"prior");assertThat(page.afterKey()).isEqualTo("z");assertThat(page.jobs()).isEmpty();
    }
    @Test void malformedJobsAreIsolatedWithoutDeletingAnyData() {
        for(var item:List.of(Map.<String,AttributeValue>of(),Map.of("pk",s(PARTITION),"sk",s("bad")),
                job("bad"),job("DOC#1-1-1-1-1#1-1-1-1-1"),job("DOC#bad#bad"))) {
            when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(item).build());
            assertThat(store.cleanPage(25,null).jobs().getFirst().outcome()).isEqualTo(StudyPlanDocumentRetirement.Outcome.FAILED);
        }
        verify(client,never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }
    @Test void onlyPublishedStateCanRetireAndMalformedOperationIdentitiesFailClosed() {
        var stage=new StudyPlanDocumentStore.Stage(id,new StudyPlanDocumentCodec().encode(new byte[]{1}).manifest(),Instant.parse("2026-11-03T12:00:00Z"));
        var item=new java.util.HashMap<>(Map.of("pk",s(id.pk()),"sk",s("MANIFEST"),"schemaVersion",n(1),"lifecycle",s("STAGING"),
                "byteLength",n(1),"chunkCount",n(1),"sha256",s(stage.manifest().sha256()),"abandonAfter",n(stage.abandonAfter().getEpochSecond()),"publicationId",s(UUID.randomUUID().toString())));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(invocation->GetItemResponse.builder().item(item).build());
        var event=new AuditedAccountStore.AuditEvent("system:fixture","plan.deleted","Fixture",stage.abandonAfter());
        assertThatIllegalStateException().isThrownBy(()->store.retire(stage,"plan",UUID.randomUUID(),1,event));
        item.put("lifecycle",s("RETIRED"));item.put("retirementId",s("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"));
        assertThatIllegalStateException().isThrownBy(()->store.cleanRetired(id));
        verify(client,never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }
    private Map<String,AttributeValue> job(String pk) {return Map.of("pk",s(PARTITION),"sk",s(pk),"schemaVersion",n(1),"documentPk",s(pk),"retirementId",s(UUID.randomUUID().toString()));}
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
}
