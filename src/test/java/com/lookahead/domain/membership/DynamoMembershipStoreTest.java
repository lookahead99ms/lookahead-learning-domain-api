package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.lookahead.domain.testaccounts.TestAccountItems;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Guard paths and SDK outcomes of the membership store with a mocked client; the flows run on DynamoDB Local. */
class DynamoMembershipStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-10T06:00:00Z");
    private final DynamoMembershipStore store = new DynamoMembershipStore(client, tables, json, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID account = UUID.randomUUID();

    private void empty() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
    }
    private void meta(long revision) {
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            boolean meta = request.key().get("sk").s().equals("META");
            return GetItemResponse.builder().item(meta ? DynamoItems.item(request.key().get("pk").s(), "META", 1, Map.of("revision", DynamoItems.n(revision))) : Map.of()).build();
        });
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
    }
    private static TransactionCanceledException cancelled(String code) {
        return TransactionCanceledException.builder().cancellationReasons(CancellationReason.builder().code(code).build()).build();
    }

    @Test void dependenciesAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> new DynamoMembershipStore(null, tables, json));
        assertThatNullPointerException().isThrownBy(() -> new DynamoMembershipStore(client, null, json));
        assertThatNullPointerException().isThrownBy(() -> new DynamoMembershipStore(client, tables, null));
        assertThatNullPointerException().isThrownBy(() -> new DynamoMembershipStore(client, tables, json, null));
        verifyNoInteractions(client);
    }

    @Test void absentItemsReadAsNothing() {
        empty();
        assertThat(store.definition("learn")).isNull();
        assertThat(store.catalog()).isEmpty();
        assertThat(store.activeSale(now)).isNull();
        assertThat(store.purchase(account, UUID.randomUUID())).isNull();
        assertThat(store.accessIds(account)).isEmpty();
        assertThat(store.activeSnapshots(account, now)).isEmpty();
        assertThat(store.staffCreatedTestAccount(account)).isFalse();
        assertThat(store.replacementsScheduled(UUID.randomUUID())).isZero();
        assertThat(store.pendingRequest(account, "awaiting_owner_approval")).isNull();
        assertThat(store.requestedBy(UUID.randomUUID())).isNull();
        assertThat(store.approvedRequest(account)).isNull();
        assertThatThrownBy(() -> store.changePrice(account, "learn", 100)).isInstanceOf(AccountFailure.class).hasMessageContaining("Unknown membership plan");
        assertThatThrownBy(() -> store.stopSale(account, UUID.randomUUID(), now)).isInstanceOf(AccountFailure.class).hasMessageContaining("Sale not found");
        assertThatThrownBy(() -> store.transact(account, work -> { work.lockAccount(account); return null; })).isInstanceOf(AccountFailure.class).hasMessageContaining("Account not found");
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void catalogIsSortedByPriceThenId() {
        var expensive = DynamoItems.item("CATALOG", "PLAN#b", 1, Map.of("definition", DynamoItems.json(json.readTree("{\"id\":\"b\"}")), "priceCents", DynamoItems.n(20), "revision", DynamoItems.n(1)));
        var cheap = DynamoItems.item("CATALOG", "PLAN#c", 1, Map.of("definition", DynamoItems.json(json.readTree("{\"id\":\"c\"}")), "priceCents", DynamoItems.n(10), "revision", DynamoItems.n(1)));
        var same = DynamoItems.item("CATALOG", "PLAN#a", 1, Map.of("definition", DynamoItems.json(json.readTree("{\"id\":\"a\"}")), "priceCents", DynamoItems.n(20), "revision", DynamoItems.n(1)));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(expensive, cheap, same)).build());
        assertThat(store.catalog()).extracting(p -> p.path("id").asText()).containsExactly("c", "a", "b");
        assertThat(store.catalog().getFirst().path("priceCents").asInt()).isEqualTo(10);
    }

    @Test void unavailableIsSurfacedAtOnceAndConflictsAreBounded() {
        meta(4);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.create("down"));
        assertThatThrownBy(() -> store.transact(account, work -> { work.lockAccount(account); work.audit(account, "x", "subject", json.createObjectNode()); return null; }))
                .isInstanceOf(DynamoWrites.Unavailable.class);
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
        reset(client); meta(4);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(cancelled("ConditionalCheckFailed"));
        assertThatThrownBy(() -> store.transact(account, work -> { work.lockAccount(account); work.audit(account, "x", "subject", json.createObjectNode()); return null; }))
                .isInstanceOf(DynamoWrites.Conflict.class);
        verify(client, times(DynamoMembershipStore.ATTEMPTS)).executeTransaction(any(ExecuteTransactionRequest.class));
        reset(client); meta(4);
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(cancelled("ConditionalCheckFailed"));
        assertThatThrownBy(() -> store.startSale(account, new MembershipService.Sale("spring", "g", now, now.plusSeconds(5), List.of("monthly-promo")))).isInstanceOf(DynamoWrites.Conflict.class);
        verify(client, times(DynamoMembershipStore.ATTEMPTS)).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    private ObjectNode purchaseRow(UUID id, Instant createdAt, String status, String providerStatus) {
        return MembershipItems.storedPurchase(json, id, account, "learn", json.createObjectNode().put("id", "learn"), "f", "k-" + id, 1, status, now, now.plusSeconds(10),
                "checkout", account, null, providerStatus, null, null, null, createdAt);
    }
    private Map<String, AttributeValue> purchaseItem(ObjectNode row) {
        return MembershipItems.item(DynamoItems.account(account), MembershipItems.purchaseSk(UUID.fromString(row.path("id").asText())), row, 1);
    }
    private Map<String, AttributeValue> saleItem(UUID id, Instant startsAt, boolean withStoppedAt) {
        var row = json.createObjectNode();
        row.put("id", id.toString()).put("template", "spring").put("greeting", "g").put("startsAt", startsAt.toString()).put("endsAt", startsAt.plusSeconds(3600).toString())
                .put("createdBy", account.toString()).put("createdAt", startsAt.toString()).put("revision", 1);
        if (withStoppedAt) row.putNull("stoppedAt");
        row.set("planIds", json.valueToTree(List.of("monthly-promo")));
        return MembershipItems.item(MembershipItems.SALES_PK, MembershipItems.saleSk(startsAt, id), row, 1);
    }
    /** Answers each partition's query from the map (by partition key), everything else empty. */
    private void partitions(Map<String, List<Map<String, AttributeValue>>> byPk) {
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> {
            QueryRequest request = call.getArgument(0);
            return QueryResponse.builder().items(byPk.getOrDefault(request.expressionAttributeValues().get(":pk").s(), List.of())).build();
        });
    }

    @Test void chronologyIsParsedAndNeverComparedAsText() {
        // Instant.toString prints 0, 3, 6 or 9 fraction digits: as text "…00Z" sorts after "…00.000001Z" although it is earlier.
        Instant earlier = now, later = now.plusNanos(1_000);
        assertThat(earlier.toString()).isGreaterThan(later.toString());
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), oldSale = UUID.randomUUID(), newSale = UUID.randomUUID();
        empty();
        partitions(Map.of(
                DynamoItems.account(account), List.of(purchaseItem(purchaseRow(second, later, "pending", "awaiting_owner_approval")), purchaseItem(purchaseRow(first, earlier, "pending", "awaiting_owner_approval"))),
                MembershipItems.SALES_PK, List.of(saleItem(oldSale, earlier, true), saleItem(newSale, later, true))));
        assertThat(MembershipItems.purchases(client, tables, json, account)).as("newest first").extracting(p -> p.path("id").asText()).containsExactly(second.toString(), first.toString());
        assertThat(store.pendingRequest(account, "awaiting_owner_approval").id()).as("the oldest request waits first").isEqualTo(first);
        assertThat(store.activeSale(now.plusSeconds(1)).path("id").asText()).as("the sale that started last").isEqualTo(newSale.toString());
    }

    @Test void theLatestApprovalBreaksATieOnTheEventId() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), adminA = UUID.randomUUID(), adminB = UUID.randomUUID();
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000001"), high = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        empty();
        var events = new HashMap<String, List<Map<String, AttributeValue>>>();
        events.put(DynamoItems.account(account), List.of(purchaseItem(purchaseRow(a, now, "succeeded", "succeeded")), purchaseItem(purchaseRow(b, now.plusSeconds(1), "succeeded", "succeeded"))));
        events.put(MembershipItems.auditPk(a.toString()), List.of(MembershipItems.auditItem(a.toString(), high, adminA, "purchase-approved", json.createObjectNode(), now)));
        events.put(MembershipItems.auditPk(b.toString()), List.of(MembershipItems.auditItem(b.toString(), low, adminB, "purchase-approved", json.createObjectNode(), now)));
        partitions(events);
        var approval = store.approvedRequest(account);
        assertThat(approval.purchaseId()).as("same instant: the greater event id, whatever the purchase ids").isEqualTo(a);
        assertThat(approval.approvedBy()).isEqualTo(adminA);
        // A later approval wins regardless of ids.
        events.put(MembershipItems.auditPk(b.toString()), List.of(MembershipItems.auditItem(b.toString(), low, adminB, "purchase-approved", json.createObjectNode(), now.plusNanos(1_000))));
        assertThat(store.approvedRequest(account).purchaseId()).isEqualTo(b);
        // An approval at or before the latest reset does not count.
        events.put(TestAccountItems.resetPk(account), List.of(TestAccountItems.resetItem(account, UUID.randomUUID(), adminA, "scenario", now.plusSeconds(5))));
        assertThat(store.approvedRequest(account)).isNull();
    }

    @Test void anAttributeThatWasNeverWrittenReadsAsUnset() {
        UUID purchase = UUID.randomUUID(), sale = UUID.randomUUID();
        var row = purchaseRow(purchase, now, "succeeded", "succeeded");
        row.remove("supersededAt"); row.remove("providerStatus");
        var registry = new HashMap<>(Map.of("accountId", DynamoItems.s(account.toString()), "username", DynamoItems.s("learner05@lookahead.dev"),
                "creationState", DynamoItems.s("active"), "initialization", DynamoItems.s("scenario"), "revision", DynamoItems.n(1)));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            boolean test = request.key().get("sk").s().equals(TestAccountItems.REGISTRY_SK);
            return GetItemResponse.builder().item(test ? DynamoItems.item(request.key().get("pk").s(), TestAccountItems.REGISTRY_SK, 1, registry) : Map.of()).build();
        });
        partitions(Map.of(DynamoItems.account(account), List.of(purchaseItem(row)), MembershipItems.SALES_PK, List.of(saleItem(sale, now, false))));
        assertThat(purchaseItem(row)).doesNotContainKeys("supersededAt", "providerStatus");
        var read = MembershipItems.purchases(client, tables, json, account).getFirst();
        assertThat(MembershipItems.grantsAt(read, now.plusSeconds(1))).isTrue();
        assertThat(read.path("supersededAt").isNull()).as("the row carries the field as NULL, as the SQL row would, for the service's own checks").isTrue();
        assertThat(read.path("providerStatus").isNull()).isTrue();
        assertThat(store.accessIds(account)).containsExactly(purchase);
        assertThat(store.activeSnapshots(account, now.plusSeconds(1))).hasSize(1);
        assertThat(store.activeSale(now.plusSeconds(1)).path("id").asText()).as("no stoppedAt attribute: still running").isEqualTo(sale.toString());
        assertThat(store.staffCreatedTestAccount(account)).as("no creatorId attribute: no creator").isFalse();
        registry.put("creatorId", DynamoItems.nul());
        assertThat(store.staffCreatedTestAccount(account)).as("NULL creatorId: no creator").isFalse();
        registry.put("creatorId", DynamoItems.s(UUID.randomUUID().toString()));
        assertThat(store.staffCreatedTestAccount(account)).isTrue();
        // Inside a transaction the same purchase blocks a second request, and a superseded one does not.
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            boolean meta = request.key().get("sk").s().equals("META");
            return GetItemResponse.builder().item(meta ? DynamoItems.item(request.key().get("pk").s(), "META", 1, Map.of("revision", DynamoItems.n(1))) : Map.of()).build();
        });
        row.put("status", "pending");
        partitions(Map.of(DynamoItems.account(account), List.of(purchaseItem(row))));
        assertThat(store.<List<String>>transact(account, work -> { work.lockAccount(account); return work.pendingProviderStatuses(account, "awaiting_owner_approval"); })).containsExactly("");
    }

    @Test void theSnapshotGuardsItsAccountAndStatementShapes() {
        meta(1);
        var captured = new java.util.ArrayList<ExecuteTransactionRequest>();
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenAnswer(call -> { captured.add(call.getArgument(0)); return ExecuteTransactionResponse.builder().build(); });
        UUID other = UUID.randomUUID(), purchase = UUID.randomUUID();
        var plan = json.createObjectNode().put("id", "learn").put("priceCents", 6999);
        assertThatThrownBy(() -> store.transact(account, work -> { work.audit(account, "x", "s", json.createObjectNode()); return null; })).isInstanceOf(IllegalStateException.class).hasMessageContaining("locked account");
        assertThatThrownBy(() -> store.transact(account, work -> { work.lockAccount(account); work.paymentAttempts(purchase); return null; })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.transact(account, work -> { work.lockAccount(account);
            work.insertPurchase(new MembershipStore.NewPurchase(purchase, other, "learn", plan, "f", "k", 1, now, now, "checkout", account, null)); return null; })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.transact(account, work -> { work.lockAccount(account); work.supersede(other, purchase, now); return null; })).isInstanceOf(IllegalStateException.class);
        store.transact(account, work -> {
            work.lockAccount(account);
            assertThat(work.purchaseAccount(purchase)).isNull();
            assertThat(work.purchase(other, purchase, true)).isNull();
            assertThat(work.purchasesByKey(account, "k")).isEmpty();
            assertThat(work.pendingProviderStatuses(account, "awaiting_owner_approval")).isEmpty();
            assertThat(work.definition("learn")).isNull();
            assertThat(work.activeSale(now)).isNull();
            assertThat(work.replacementsScheduled(purchase)).isZero();
            assertThat(work.accessIds(account)).isEmpty();
            assertThat(work.decisions(purchase)).isEmpty();
            work.insertPurchase(new MembershipStore.NewPurchase(purchase, account, "learn", plan, "f", "k", 6999, now, now.plusSeconds(1), "checkout", account, null));
            assertThat(work.purchasesByKey(account, "k")).containsExactly(purchase);
            assertThat(work.paymentAttempts(purchase)).isZero();
            work.recordAttempt(purchase, "error", "network");
            work.failValidation(purchase);
            work.decline(purchase, "declined_by_owner");
            work.settle(purchase, "succeeded", "pi_1", "succeeded");
            assertThat(work.recordProviderEvent("pi_1:succeeded", purchase)).isTrue();
            assertThat(work.recordProviderEvent("pi_1:succeeded", purchase)).isFalse();
            work.supersede(account, UUID.randomUUID(), now); // unknown purchase: nothing to supersede
            work.audit(account, "purchase-succeeded", purchase.toString(), json.createObjectNode());
            var row = work.purchase(account, purchase, false);
            assertThat(row.path("paymentAttempts").isMissingNode()).isTrue();
            assertThat(row.path("status").asText()).isEqualTo("succeeded");
            return null;
        });
        assertThat(captured).hasSize(1);
        var statements = captured.getFirst().transactStatements().stream().map(ParameterizedStatement::statement).toList();
        assertThat(statements).hasSize(6); // META fence, purchase, pointer, receipt, audit, catalog... no: catalog META absent so no check
        assertThat(statements.getFirst()).startsWith("UPDATE \"" + tables.userAccountDetailsTableName() + "\" SET \"revision\"=?");
        assertThat(statements.stream().filter(s -> s.startsWith("INSERT INTO \"" + tables.administratorAuditEventsTableName()))).hasSize(1);
        assertThat(statements.stream().filter(s -> s.startsWith("INSERT INTO \"" + tables.userAccountDetailsTableName()))).hasSize(3);
    }
}
