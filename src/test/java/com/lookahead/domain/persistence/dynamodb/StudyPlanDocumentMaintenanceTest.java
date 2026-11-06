package com.lookahead.domain.persistence.dynamodb;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import static org.mockito.Mockito.*;

class StudyPlanDocumentMaintenanceTest {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNamesTest.names("dev");
    private final StudyPlanDocumentMaintenance maintenance = at(NOW);
    private StudyPlanDocumentMaintenance at(Instant time) {
        return new StudyPlanDocumentMaintenance(client, tables, Clock.fixed(time, ZoneOffset.UTC));
    }
    private Map<String, AttributeValue> job(int offset) {
        var id = new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(), UUID.randomUUID());
        var manifest = new StudyPlanDocumentCodec().encode(new byte[]{1}).manifest();
        var stage = new StudyPlanDocumentStore.Stage(id, manifest, NOW.plusSeconds(offset));
        return Map.of("pk", s("WORK#DOCUMENT#00"), "sk", s(stage.jobKey()), "schemaVersion", n(1), "documentPk", s(id.pk()));
    }
    private QueryResponse page(List<Map<String, AttributeValue>> items, Map<String, AttributeValue> last) {
        return QueryResponse.builder().items(items).lastEvaluatedKey(last).build();
    }
    private static Map<String, AttributeValue> key(Map<String, AttributeValue> job) {
        return Map.of("pk", job.get("pk"), "sk", job.get("sk"));
    }

    @Test void boundedStrongQueryPreservesCutoffAndContinuationWithoutScanning() {
        var first = job(-2); var second = job(-1);
        when(client.query(any(QueryRequest.class))).thenReturn(page(List.of(first), key(first)), page(List.of(second), Map.of()));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        var batch = maintenance.cleanDue(1, null);
        assertThat(batch.jobs()).extracting(StudyPlanDocumentMaintenance.JobResult::outcome).containsExactly(StudyPlanDocumentMaintenance.Outcome.RETAINED);
        var last = at(NOW.plusSeconds(10)).cleanDue(1, batch.next());
        assertThat(last.cutoff()).isEqualTo(NOW);
        assertThat(last.next()).isNull();
        var requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(requests.capture());
        var query = requests.getAllValues().getFirst();
        assertThat(query.tableName()).isEqualTo(tables.userAccountDetailsTableName());
        assertThat(query.consistentRead()).isTrue(); assertThat(query.scanIndexForward()).isTrue();
        assertThat(query.limit()).isEqualTo(1); assertThat(query.indexName()).isNull();
        assertThat(query.filterExpression()).isNull();
        assertThat(query.keyConditionExpression()).isEqualTo("pk = :pk AND sk <= :due");
        assertThat(query.expressionAttributeValues().get(":due").s()).isEqualTo("DUE#001793620800#\uffff");
        assertThat(requests.getAllValues().getLast().exclusiveStartKey()).isEqualTo(key(first));
        assertThat(requests.getAllValues().getLast().expressionAttributeValues()).isEqualTo(query.expressionAttributeValues());
        verify(client, never()).scan(any(ScanRequest.class));
    }

    @Test void malformedJobAndTransientFailureDoNotBlockLaterJobsOrDiscardTheirKeys() {
        var malformed = new HashMap<>(job(-3)); malformed.put("schemaVersion", n(2));
        var failed = job(-2); var retained = job(-1);
        when(client.query(any(QueryRequest.class))).thenReturn(page(List.of(malformed, failed, retained), key(retained)));
        when(client.getItem(any(GetItemRequest.class))).thenThrow(SdkClientException.create("synthetic read failure"))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());
        var batch = maintenance.cleanDue(3, null);
        assertThat(batch.jobs()).extracting(StudyPlanDocumentMaintenance.JobResult::outcome).containsExactly(
                StudyPlanDocumentMaintenance.Outcome.FAILED, StudyPlanDocumentMaintenance.Outcome.FAILED, StudyPlanDocumentMaintenance.Outcome.RETAINED);
        assertThat(batch.next().afterKey()).isEqualTo(retained.get("sk").s());
        verify(client, never()).deleteItem(any(DeleteItemRequest.class));
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test void corruptIdentitiesAndDeadlinesCannotReachDocumentCleanup() {
        var valid = job(-1);
        var invalid = new java.util.ArrayList<Map<String, AttributeValue>>();
        for (var change : List.of(Map.entry("pk", s("wrong")), Map.entry("documentPk", s("DOC#bad#uuid")),
                Map.entry("documentPk", s("OTHER#a#b")), Map.entry("documentPk", s("DOC#1-1-1-1-1#1-1-1-1-1")),
                Map.entry("sk", s("x")), Map.entry("sk", s("BAD#001793620800#"+valid.get("documentPk").s())),
                Map.entry("sk", s("DUE#xxxxxxxxxxxx#"+valid.get("documentPk").s())),
                Map.entry("sk", s("DUE#001793620800!"+valid.get("documentPk").s())),
                Map.entry("sk", s("DUE#001793620800#other")),
                Map.entry("sk", s("DUE#001793620801#"+valid.get("documentPk").s())),
                Map.entry("documentPk", n(1)))) {
            var item = new HashMap<>(valid); item.put(change.getKey(),change.getValue()); invalid.add(item);
        }
        var missing = new HashMap<>(valid); missing.remove("documentPk"); invalid.add(missing);
        when(client.query(any(QueryRequest.class))).thenReturn(page(invalid, Map.of()));
        assertThat(maintenance.cleanDue(25,null).jobs()).allMatch(j -> j.outcome() == StudyPlanDocumentMaintenance.Outcome.FAILED);
        verify(client, never()).getItem(any(GetItemRequest.class));
    }

    @Test void invalidBoundsAndFutureOrUnsupportedCutoffsFailBeforeDatabaseCalls() {
        for (int count : new int[]{0,-1,26}) assertThatIllegalArgumentException().isThrownBy(()->maintenance.cleanDue(count,null));
        assertThatIllegalArgumentException().isThrownBy(()->maintenance.cleanDue(1,new StudyPlanDocumentMaintenance.Cursor(NOW.plusSeconds(1),"A")));
        for (Instant time : List.of(Instant.EPOCH.minusSeconds(1),Instant.parse("+10000-01-01T00:00:00Z"))) {
            assertThatIllegalArgumentException().isThrownBy(()->at(time).cleanDue(1,null));
            assertThatIllegalArgumentException().isThrownBy(()->new StudyPlanDocumentMaintenance.Cursor(time,"A"));
        }
        for (String key : List.of("", "x".repeat(1025),"Z"))
            assertThatIllegalArgumentException().isThrownBy(()->new StudyPlanDocumentMaintenance.Cursor(NOW,key));
        assertThatNullPointerException().isThrownBy(()->new StudyPlanDocumentMaintenance.Cursor(null,"A"));
        assertThatNullPointerException().isThrownBy(()->new StudyPlanDocumentMaintenance.Cursor(NOW,null));
        verifyNoInteractions(client);
    }

    @Test void queryFailureIsSurfacedWithoutAnyCleanup() {
        var failure = SdkClientException.create("synthetic query failure");
        when(client.query(any(QueryRequest.class))).thenThrow(failure);
        assertThatThrownBy(()->maintenance.cleanDue(1,null)).isSameAs(failure);
        verify(client,never()).getItem(any(GetItemRequest.class));
    }

    @Test void malformedOrRepeatedContinuationAndOversizedResponsesFailBeforeCleanup() {
        var item = job(-1);
        for (var last : List.of(Map.of("pk",s("other"),"sk",item.get("sk")),
                Map.of("pk",s("WORK#DOCUMENT#00")), Map.of("pk",s("WORK#DOCUMENT#00"),"sk",n(1)),
                Map.of("pk",s("WORK#DOCUMENT#00"),"sk",item.get("sk"),"extra",s("x")))) {
            when(client.query(any(QueryRequest.class))).thenReturn(page(List.of(item),last));
            assertThatIllegalStateException().isThrownBy(()->maintenance.cleanDue(1,null));
        }
        when(client.query(any(QueryRequest.class))).thenReturn(page(List.of(item), key(item)));
        assertThatIllegalStateException().isThrownBy(()->maintenance.cleanDue(1,new StudyPlanDocumentMaintenance.Cursor(NOW,item.get("sk").s())));
        when(client.query(any(QueryRequest.class))).thenReturn(page(List.of(item,item), Map.of()));
        assertThatIllegalStateException().isThrownBy(()->maintenance.cleanDue(1,null));
        verify(client,never()).getItem(any(GetItemRequest.class));
    }

    @Test void emptySweepHasNoContinuationOrMutation() {
        when(client.query(any(QueryRequest.class))).thenReturn(page(List.of(),Map.of()));
        assertThat(maintenance.cleanDue(25,null)).isEqualTo(new StudyPlanDocumentMaintenance.Batch(NOW,List.of(),null));
        verify(client,never()).getItem(any(GetItemRequest.class));
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(int value) { return AttributeValue.builder().n(Integer.toString(value)).build(); }
}
