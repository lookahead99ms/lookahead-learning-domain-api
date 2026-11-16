package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** The read paths and fail-closed branches that DynamoDB Local does not reach. */
class DynamoPlanRepositoryTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final JsonMapper mapper = new JsonMapper();
    private final DynamoPlanRepository repository = new DynamoPlanRepository(client, tables, mapper, Clock.fixed(Instant.parse("2026-11-10T06:00:00Z"), ZoneOffset.UTC));
    private final UUID owner = UUID.randomUUID(), plan = UUID.randomUUID(), key = UUID.randomUUID();

    private void item(String sk, Map<String, AttributeValue> item) {
        when(client.getItem(argThat((GetItemRequest request) -> request != null && request.key().get("sk").s().equals(sk))))
                .thenReturn(GetItemResponse.builder().item(item).build());
    }
    private Map<String, AttributeValue> receipt(int status, Map<String, AttributeValue> extra) {
        var attributes = new java.util.HashMap<>(Map.of("requestHash", DynamoItems.s("sha256:h"), "planId", DynamoItems.s(plan.toString()), "status", DynamoItems.n(status)));
        attributes.putAll(extra);
        return DynamoItems.item(DynamoItems.account(owner), "MUTATION#" + key, 1, attributes);
    }

    @Test void receiptsAnswerDeletedWhenThePlanTombstoneExistsExceptTheDeleteReceiptItself() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        assertThat(repository.findReceipt(owner, key)).isEmpty();
        item("MUTATION#" + key, receipt(200, Map.of("response", AttributeValue.builder().b(SdkBytes.fromUtf8String("{\"ok\":true}")).build())));
        item("PLAN#" + plan, DynamoItems.item(DynamoItems.account(owner), "PLAN#" + plan, 1, Map.of("deleted", DynamoItems.bool(true))));
        var deleted = repository.findReceipt(owner, key).orElseThrow();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.response()).isNull();
        assertThat(deleted.planId()).isEqualTo(plan);
        item("MUTATION#" + key, receipt(204, Map.of()));
        var removal = repository.findReceipt(owner, key).orElseThrow();
        assertThat(removal.deleted()).isFalse();
        assertThat(removal.status()).isEqualTo(204);
        item("PLAN#" + plan, DynamoItems.item(DynamoItems.account(owner), "PLAN#" + plan, 1, Map.of("deleted", DynamoItems.bool(false))));
        item("MUTATION#" + key, receipt(200, Map.of("response", AttributeValue.builder().b(SdkBytes.fromUtf8String("{\"ok\":true}")).build())));
        var live = repository.findReceipt(owner, key).orElseThrow();
        assertThat(live.deleted()).isFalse();
        assertThat(live.response().path("ok").asBoolean()).isTrue();
        item("MUTATION#" + key, receipt(200, Map.of("deleted", DynamoItems.bool(true))));
        assertThat(repository.findReceipt(owner, key).orElseThrow().deleted()).isTrue();
        repository.tombstoneReceipts(owner, plan);
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void plansAndVersionsFailClosedOnDamagedItemsAndHideTombstones() {
        item("PLAN#" + plan, DynamoItems.item(DynamoItems.account(owner), "PLAN#" + plan, 2, Map.of("deleted", DynamoItems.bool(false))));
        assertThatIllegalStateException().isThrownBy(() -> repository.findPlan(owner, plan, false));
        item("PLAN#" + plan, DynamoItems.item(DynamoItems.account(owner), "PLAN#" + plan, 1, Map.of("deleted", DynamoItems.bool(true))));
        assertThat(repository.findPlan(owner, plan, false)).isEmpty();
        assertThat(repository.findVersion(owner, plan, UUID.randomUUID())).isEmpty();
        item("PLAN#" + plan, DynamoItems.item(DynamoItems.account(owner), "PLAN#" + plan, 1, Map.of("planId", DynamoItems.s(plan.toString()), "revision", DynamoItems.n(3),
                "currentVersion", DynamoItems.s(UUID.randomUUID().toString()), "goal", DynamoItems.s("g"), "progress", DynamoItems.json(mapper.createObjectNode()),
                "createdAt", DynamoItems.s("2026-11-10T06:00:00Z"), "updatedAt", DynamoItems.s("2026-11-10T06:00:00Z"), "name", DynamoItems.s("n"), "planNumber", DynamoItems.n(1))));
        assertThat(repository.findPlan(owner, plan, false).orElseThrow().revision()).isEqualTo(3);
        UUID version = UUID.randomUUID();
        item("VERSION#" + version, DynamoItems.item(DynamoItems.plan(owner, plan), "VERSION#" + version, 1, Map.of("document", AttributeValue.builder().b(SdkBytes.fromUtf8String("[1]")).build())));
        assertThatIllegalStateException().isThrownBy(() -> repository.findVersion(owner, plan, version));
        item("VERSION#" + version, DynamoItems.item(DynamoItems.plan(owner, plan), "VERSION#" + version, 1, Map.of("document", AttributeValue.builder().b(SdkBytes.fromUtf8String("{not json")).build())));
        assertThatIllegalStateException().isThrownBy(() -> repository.findVersion(owner, plan, version));
        UUID missing = UUID.randomUUID();
        item("VERSION#" + missing, Map.of());
        assertThat(repository.findVersion(owner, plan, missing)).isEmpty();
        when(client.getItem(any(GetItemRequest.class))).thenThrow(SdkClientException.create("down"));
        assertThatThrownBy(() -> repository.findPlan(owner, plan, false)).isInstanceOf(DynamoOwnerUnit.DynamoOwnerUnavailable.class);
    }

    /** DLV-970 review Q4/Q8: a LastEvaluatedKey page, more than 100 ids (two BatchGetItem calls) and a plan whose item is gone (dropped, not retried). */
    @Test void listPagesTheProjectionBatchesAHundredKeysAndDropsAPlanWhoseItemIsGone() {
        item("META", DynamoItems.item(DynamoItems.account(owner), "META", 1, Map.of("revision", DynamoItems.n(1))));
        var ids = java.util.stream.IntStream.range(0, 150).mapToObj(i -> UUID.randomUUID()).toList();
        var rows = ids.stream().map(id -> DynamoItems.item(DynamoItems.account(owner), "PLANLIST#" + id, 1, Map.of("planId", DynamoItems.s(id.toString())))).toList();
        when(client.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(rows.subList(0, 70)).lastEvaluatedKey(DynamoItems.key(DynamoItems.account(owner), "PLANLIST#" + ids.get(69))).build(),
                QueryResponse.builder().items(rows.subList(70, 150)).build());
        UUID gone = ids.get(149);
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation -> {
            var keys = invocation.<BatchGetItemRequest>getArgument(0).requestItems().get(tables.userAccountDetailsTableName()).keys();
            assertThat(keys.size()).isBetween(1, 100);
            var found = keys.stream().map(key -> key.get("sk").s()).filter(sk -> !sk.equals("PLAN#" + gone)).map(sk -> DynamoItems.item(DynamoItems.account(owner), sk, 1,
                    Map.of("planId", DynamoItems.s(sk.substring("PLAN#".length())), "revision", DynamoItems.n(1), "currentVersion", DynamoItems.s(UUID.randomUUID().toString()),
                            "goal", DynamoItems.s("g"), "progress", DynamoItems.json(mapper.createObjectNode()), "createdAt", DynamoItems.s("2026-11-10T06:00:00Z"),
                            "updatedAt", DynamoItems.s("2026-11-10T06:00:00Z"), "name", DynamoItems.s("n"), "planNumber", DynamoItems.n(1), "deleted", DynamoItems.bool(false)))).toList();
            return BatchGetItemResponse.builder().responses(Map.of(tables.userAccountDetailsTableName(), found)).build();
        });
        var page = repository.list(owner, 150, 0);
        assertThat(page).extracting(record -> record.id()).containsExactlyElementsOf(ids.subList(0, 149));
        verify(client, times(2)).query(any(QueryRequest.class));
        verify(client, times(2)).batchGetItem(any(BatchGetItemRequest.class));
        // The fence is read once per list: a missing owner is refused before any projection read.
        verify(client, times(1)).getItem(any(GetItemRequest.class));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        assertThatThrownBy(() -> repository.list(owner, 10, 0)).isInstanceOfSatisfying(com.lookahead.learning.content.exception.AccountFailure.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test void listReadsTheProjectionInOrderAndRejectsBadPages() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        item("META", DynamoItems.item(DynamoItems.account(owner), "META", 1, Map.of("revision", DynamoItems.n(1))));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(
                DynamoItems.item(DynamoItems.account(owner), "PLANLIST#b", 1, Map.of("planId", DynamoItems.s(b.toString()))),
                DynamoItems.item(DynamoItems.account(owner), "PLANLIST#a", 1, Map.of("planId", DynamoItems.s(a.toString())))).build());
        var plans = List.of(a, b).stream().map(id -> DynamoItems.item(DynamoItems.account(owner), "PLAN#" + id, 1, Map.of("planId", DynamoItems.s(id.toString()), "revision", DynamoItems.n(1),
                "currentVersion", DynamoItems.s(UUID.randomUUID().toString()), "goal", DynamoItems.s("g"), "progress", DynamoItems.json(mapper.createObjectNode()),
                "createdAt", DynamoItems.s("2026-11-10T06:00:00Z"), "updatedAt", DynamoItems.s("2026-11-10T06:00:00Z"), "name", DynamoItems.s("n"), "planNumber", DynamoItems.n(1), "deleted", DynamoItems.bool(false)))).toList();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(BatchGetItemResponse.builder().responses(Map.of(tables.userAccountDetailsTableName(), plans)).build());
        assertThat(repository.list(owner, 10, 0)).extracting(record -> record.id()).containsExactly(b, a);
        assertThat(repository.list(owner, 1, 1)).extracting(record -> record.id()).containsExactly(a);
        assertThat(repository.list(owner, 5, 2)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> repository.list(owner, -1, 0));
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(BatchGetItemResponse.builder().responses(Map.of(tables.userAccountDetailsTableName(), plans))
                .unprocessedKeys(Map.of(tables.userAccountDetailsTableName(), KeysAndAttributes.builder().keys(DynamoItems.key("a", "b")).build())).build());
        assertThatThrownBy(() -> repository.list(owner, 10, 0)).isInstanceOf(DynamoOwnerUnit.DynamoOwnerUnavailable.class);
    }

    @Test void grantsComeFromTheAccountRepositoryOrMembershipsNeverFromStaffScopes() {
        var accounts = mock(AccountRepository.class);
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of("staff:documents", "learn:core-java"));
        repository.accounts(accounts);
        assertThat(repository.grants(owner)).containsExactly("learn:core-java");
        var memberships = mock(com.lookahead.domain.membership.MembershipGrants.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
        when(memberships.topics(owner)).thenReturn(Set.of("grow:x"));
        var fallback = new DynamoPlanRepository(client, tables, mapper, Clock.systemUTC());
        fallback.memberships(memberships);
        assertThat(fallback.grants(owner)).containsExactly("grow:x");
        var publication = mock(com.lookahead.learning.content.service.ProtectedContentPolicy.class);
        fallback.publication(publication);
        assertThat(fallback.grants(owner)).containsExactly("grow:x");
        verify(client, never()).getItem(any(GetItemRequest.class));
    }

    @Test void oversizedProgressIsRefusedBeforeAnythingIsSent() {
        item("META", DynamoItems.item(DynamoItems.account(owner), "META", 1, Map.of("revision", DynamoItems.n(1))));
        var progress = mapper.createObjectNode().put("notes", "x".repeat(DynamoPlanRepository.PROGRESS_BYTES + 1));
        assertThatIllegalStateException().isThrownBy(() -> repository.createPlan(owner, plan, UUID.randomUUID(), "g", progress, "n", 1));
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }
}
