package com.lookahead.domain.testaccounts;

import com.lookahead.domain.membership.MembershipItems;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoAccountDataErasureTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-10T06:00:00Z");
    private final UUID account = UUID.randomUUID();
    private final List<ExecuteTransactionRequest> captured = new ArrayList<>();

    private Map<String, AttributeValue> purchase(String paymentId) {
        UUID id = UUID.randomUUID();
        var stored = MembershipItems.storedPurchase(json, id, account, "learn", json.createObjectNode().put("id", "learn"), "f", "fixture-" + id, 1, "succeeded", now, now.plusSeconds(1),
                "checkout", account, paymentId, "succeeded", null, null, null, now);
        return MembershipItems.item(DynamoItems.account(account), MembershipItems.purchaseSk(id), stored, 1);
    }
    /** The subject at the given revision (null: none), the mock payment receipt of every key, nothing else. */
    private void stored(Long metaRevision) {
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            String pk = request.key().get("pk").s(), sk = request.key().get("sk").s();
            if (pk.startsWith("MOCKPAYMENT#")) return GetItemResponse.builder().item(DynamoItems.item(pk, "RECEIPT", 1, Map.of("paymentId", DynamoItems.s("pi_x")))).build();
            if (sk.equals("META") && metaRevision != null) return GetItemResponse.builder().item(DynamoItems.item(pk, "META", 1, Map.of("revision", DynamoItems.n(metaRevision)))).build();
            return GetItemResponse.builder().item(Map.of()).build();
        });
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call -> { captured.add(call.getArgument(0)); return ExecuteTransactionResponse.builder().build(); });
    }
    private static List<String> texts(ExecuteTransactionRequest request) { return request.transactStatements().stream().map(ParameterizedStatement::statement).toList(); }

    @Test void deletesPurchasesPointersReceiptsAndMockPaymentsInBoundedTransactionsEachFencedOnTheSubjectAndCallsTheOtherLanes() {
        var purchases = new ArrayList<Map<String, AttributeValue>>();
        for (int n = 0; n < 30; n++) purchases.add(purchase(n == 0 ? "pi_1" : null));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(purchases).build());
        stored(5L);
        var calls = new ArrayList<String>();
        PlanErasure plans = new PlanErasure() {
            @Override public void erase(UUID id) { calls.add("erase"); }
            @Override public void resetPlans(UUID id) { calls.add("reset"); }
        };
        AccountRecordsMaintenance records = mock(AccountRecordsMaintenance.class, withSettings().mockMaker(MockMakers.PROXY));
        Long after = new DynamoAccountDataErasure(client, tables, json, plans, records).eraseFenced(account, true);
        // 30 purchases x (item + pointer + mock receipt + mock id pointer) + 1 provider receipt = 121 deletes, 99 per transaction
        // behind the META fence: two transactions of 100 and 23 statements, META moved 5 -> 6 -> 7.
        assertThat(captured).hasSize(2);
        assertThat(captured.stream().mapToInt(r -> r.transactStatements().size()).sum()).isEqualTo(123);
        assertThat(texts(captured.get(0))).hasSize(100); assertThat(texts(captured.get(1))).hasSize(23);
        for (var request : captured) {
            var statements = texts(request);
            assertThat(statements.getFirst()).startsWith("UPDATE \"" + tables.userAccountDetailsTableName() + "\" SET \"revision\"=? WHERE pk=? AND sk=? AND revision=?");
            assertThat(statements.subList(1, statements.size())).allMatch(s -> s.startsWith("DELETE FROM "));
            assertThat(request.transactStatements().getFirst().parameters()).extracting(AttributeValue::s).contains(DynamoItems.account(account), "META");
        }
        assertThat(captured.get(0).transactStatements().getFirst().parameters()).extracting(AttributeValue::n).contains("6", "5");
        assertThat(captured.get(1).transactStatements().getFirst().parameters()).extracting(AttributeValue::n).contains("7", "6");
        assertThat(after).as("the revision the account stands at afterwards, for the caller's own fence").isEqualTo(7);
        assertThat(calls).containsExactly("erase");
        verify(records).clearAccessRecords(account);
        // The other lanes run before the fence is read, so their own revision moves never fail it.
        var order = inOrder(records, client);
        order.verify(records).clearAccessRecords(account);
        order.verify(client, atLeastOnce()).getItem(any(GetItemRequest.class));
    }

    @Test void withoutASubjectThereIsNoFenceAndWithoutPurchasesNothingIsWritten() {
        var purchases = List.of(purchase(null));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(purchases).build());
        stored(null);
        assertThat(new DynamoAccountDataErasure(client, tables, json, null, null).eraseFenced(account, false)).isNull();
        assertThat(captured).hasSize(1);
        assertThat(texts(captured.getFirst())).as("item, pointer, mock receipt and mock id, no fence").hasSize(4).allMatch(s -> s.startsWith("DELETE FROM "));
        captured.clear();
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
        stored(5L);
        assertThat(new DynamoAccountDataErasure(client, tables, json, null, null).eraseFenced(account, false)).as("nothing to delete: the revision stays").isEqualTo(5);
        new DynamoAccountDataErasure(client, tables, json, null, null).erase(account, false);
        assertThat(captured).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> new DynamoAccountDataErasure(null, tables, json, null, null));
    }
}
