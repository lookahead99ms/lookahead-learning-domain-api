package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-970 lane G's membership facts against stored purchase items: open, paid, ended under the owner fence. */
class MembershipFactsStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-10T06:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final MembershipFactsStore facts = new MembershipFactsStore(client, tables, json, new MembershipStore(client, tables, json, clock), clock);
    private final UUID account = UUID.randomUUID();
    private final List<Map<String, AttributeValue>> purchases = new ArrayList<>();
    private final List<ExecuteTransactionRequest> committed = new ArrayList<>();
    private Long metaRevision = 3L;
    private int planPrice = 6999;

    {
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> {
            QueryRequest request = call.getArgument(0);
            return QueryResponse.builder().items(request.expressionAttributeValues().get(":pk").s().equals(DynamoItems.account(account)) ? List.copyOf(purchases) : List.of()).build();
        });
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            String pk = request.key().get("pk").s(), sk = request.key().get("sk").s();
            if (sk.equals("META") && pk.equals(DynamoItems.account(account)) && metaRevision != null) return GetItemResponse.builder().item(DynamoItems.item(pk, "META", 1, Map.of("revision", DynamoItems.n(metaRevision)))).build();
            if (pk.equals(MembershipItems.CATALOG_PK) && sk.startsWith(MembershipItems.PLAN_PREFIX)) return GetItemResponse.builder().item(DynamoItems.item(pk, sk, 1,
                    Map.of("definition", DynamoItems.json(json.createObjectNode().put("id", sk.substring(5))), "priceCents", DynamoItems.n(planPrice), "revision", DynamoItems.n(1)))).build();
            return GetItemResponse.builder().item(Map.of()).build();
        });
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call -> { committed.add(call.getArgument(0)); return ExecuteTransactionResponse.builder().build(); });
    }
    private UUID purchase(String planId, int price, String status, Instant endsAt, Instant supersededAt, boolean withAttribute) {
        UUID id = UUID.randomUUID();
        ObjectNode row = MembershipItems.storedPurchase(json, id, account, planId, json.createObjectNode().put("id", planId), "f", "k-" + id, price, status, now.minusSeconds(60), endsAt,
                "checkout", account, null, "succeeded", null, supersededAt, null, now.minusSeconds(60));
        if (!withAttribute) row.remove("supersededAt");
        purchases.add(MembershipItems.item(DynamoItems.account(account), MembershipItems.purchaseSk(id), row, 2));
        return id;
    }

    @Test void dependenciesAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> new MembershipFactsStore(null, tables, json, new MembershipStore(client, tables, json, clock), clock));
    }

    @Test void anyPurchaseNotSupersededIsOpenWhateverItsStatusOrHowTheAttributeIsAbsent() {
        assertThat(facts.hasOpenPurchases(account)).isFalse();
        purchase("learn", 6999, "failed", now.plusSeconds(10), now.minusSeconds(1), true);
        assertThat(facts.hasOpenPurchases(account)).as("superseded, even though failed counts as a row").isFalse();
        purchase("learn", 6999, "failed", now.plusSeconds(10), null, true);
        assertThat(facts.hasOpenPurchases(account)).as("NULL supersededAt: open, status does not matter").isTrue();
        purchases.clear();
        purchase("learn", 6999, "pending", now.plusSeconds(10), null, false);
        assertThat(facts.hasOpenPurchases(account)).as("attribute never written: open").isTrue();
    }

    @Test void aPaidMembershipIsRunningNotSupersededAndPaidForOrPriced() {
        assertThat(facts.hasPaidMembership(account)).isFalse();
        purchase("free", 0, "succeeded", now.plusSeconds(3600), null, true);
        planPrice = 0;
        assertThat(facts.hasPaidMembership(account)).as("free of charge and free today").isFalse();
        planPrice = 6999;
        assertThat(facts.hasPaidMembership(account)).as("the plan is priced today").isTrue();
        planPrice = 0; purchases.clear();
        purchase("free", 100, "succeeded", now.plusSeconds(3600), null, true);
        assertThat(facts.hasPaidMembership(account)).as("something was paid").isTrue();
        purchases.clear();
        purchase("learn", 6999, "succeeded", now.minusSeconds(1), null, true);
        assertThat(facts.hasPaidMembership(account)).as("ended").isFalse();
        purchases.clear();
        purchase("learn", 6999, "succeeded", now.plusSeconds(3600), now.minusSeconds(1), true);
        assertThat(facts.hasPaidMembership(account)).as("superseded").isFalse();
        purchases.clear();
        purchase("learn", 6999, "failed", now.plusSeconds(3600), null, true);
        assertThat(facts.hasPaidMembership(account)).as("not succeeded or scheduled").isFalse();
        purchases.clear();
        planPrice = 6999;
        purchase("learn", 0, "scheduled", now.plusSeconds(3600), null, false);
        assertThat(facts.hasPaidMembership(account)).as("scheduled, priced, attribute never written").isTrue();
    }

    @Test void endingMembershipsSupersedesTheOpenOnesUnderTheOwnerFenceAndNothingWhenNothingIsOpen() {
        facts.endMemberships(account);
        assertThat(committed).as("no purchases: no transaction").isEmpty();
        UUID open = purchase("learn", 6999, "succeeded", now.plusSeconds(3600), null, true);
        UUID absent = purchase("grow", 100, "pending", now.plusSeconds(3600), null, false);
        purchase("free", 0, "succeeded", now.plusSeconds(3600), now.minusSeconds(5), true);
        facts.endMemberships(account);
        assertThat(committed).hasSize(1);
        var statements = committed.getFirst().transactStatements();
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0).statement()).startsWith("UPDATE \"" + tables.userAccountDetailsTableName() + "\" SET \"revision\"=? WHERE pk=? AND sk=? AND revision=?");
        assertThat(statements.get(0).parameters()).extracting(AttributeValue::n).contains("4", "3");
        for (var statement : statements.subList(1, 3)) {
            assertThat(statement.statement()).startsWith("UPDATE ").contains("\"supersededAt\"=?").endsWith("AND revision=?");
            assertThat(statement.parameters()).extracting(AttributeValue::s).containsAnyOf(MembershipItems.purchaseSk(open), MembershipItems.purchaseSk(absent)).contains(now.toString());
            assertThat(statement.parameters().getLast().n()).as("at the purchase's own revision").isEqualTo("2");
        }
        // No subject: the account was never created, nothing to end; an open purchase without a META is left (404 swallowed).
        metaRevision = null; committed.clear();
        facts.endMemberships(account);
        assertThat(committed).isEmpty();
    }
}
