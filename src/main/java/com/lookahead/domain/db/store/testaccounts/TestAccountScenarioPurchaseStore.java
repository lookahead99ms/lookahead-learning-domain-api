package com.lookahead.domain.db.store.testaccounts;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipItems;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: the fixture purchases as the same {@code ACCOUNT#id / MEMBERSHIP#id} items checkout
 * writes, with their owner pointer and, for a paid fixture, the mock provider's record, in one
 * transaction each, committed at once (the test-account body that asked for them may still fail
 * afterwards: a Reset supersedes whatever it left). Fixture writes do not bump the account
 * revision; the reset's commit tells them from a checkout's row by {@link
 * MembershipItems#FIXTURE_FINGERPRINT}.
 */
@Component
public class TestAccountScenarioPurchaseStore implements TestAccountScenarioPurchase {
  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final ObjectMapper json;
  private final Clock clock;

  @Autowired
  public TestAccountScenarioPurchaseStore(
      DynamoDbClient client, DynamoTableNames tables, ObjectMapper json) {
    this(client, tables, json, Clock.systemUTC());
  }

  public TestAccountScenarioPurchaseStore(
      DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, Clock clock) {
    this.client = Objects.requireNonNull(client);
    this.tables = Objects.requireNonNull(tables);
    this.json = Objects.requireNonNull(json);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public ObjectNode definition(String id) {
    var item =
        MembershipItems.get(
            client,
            tables.userAccountDetailsTableName(),
            MembershipItems.CATALOG_PK,
            MembershipItems.planSk(id));
    if (item.isEmpty()) throw new IllegalStateException("Scenario membership is unavailable");
    var row = MembershipItems.row(item, json);
    var result = (ObjectNode) row.path("definition").deepCopy();
    result.put("priceCents", row.path("priceCents").asInt());
    return result;
  }

  @Override
  public void insertPurchase(
      UUID id,
      UUID account,
      UUID actor,
      ObjectNode plan,
      Instant startsAt,
      Instant endsAt,
      int price,
      String discount,
      UUID replaces,
      boolean staff,
      String payment,
      Instant superseded) {
    Instant now = clock.instant();
    JsonNode discountNode = discount == null ? null : json.readTree(discount);
    var stored =
        MembershipItems.storedPurchase(
            json,
            id,
            account,
            plan.path("id").asText(),
            plan,
            MembershipItems.FIXTURE_FINGERPRINT,
            "fixture-" + id,
            price,
            "succeeded",
            startsAt,
            endsAt,
            staff ? "staff-upgrade" : "checkout",
            actor,
            payment,
            staff ? "not-required" : "succeeded",
            replaces,
            superseded,
            discountNode,
            now);
    String table = tables.userAccountDetailsTableName();
    var tx =
        DynamoWrites.transaction(client)
            .insertOnly(
                table,
                MembershipItems.item(
                    DynamoItems.account(account), MembershipItems.purchaseSk(id), stored, 1))
            .insertOnly(
                table,
                DynamoItems.item(
                    MembershipItems.purchasePointerPk(id),
                    "ACCOUNT",
                    MembershipItems.SCHEMA,
                    Map.of("accountId", DynamoItems.s(account.toString()))));
    if (payment != null) {
      String key = "fixture-" + id;
      tx.insertOnly(
              table,
              DynamoItems.item(
                  MembershipItems.mockPaymentPk(key),
                  MembershipItems.RECEIPT_SK,
                  MembershipItems.SCHEMA,
                  Map.of(
                      "idempotencyKey",
                      DynamoItems.s(key),
                      "paymentId",
                      DynamoItems.s(payment),
                      "amountCents",
                      DynamoItems.n(price),
                      "currency",
                      DynamoItems.s("USD"),
                      "status",
                      DynamoItems.s("succeeded"))))
          .insertOnly(
              table,
              DynamoItems.item(
                  MembershipItems.mockPaymentIdPk(payment),
                  "KEY",
                  MembershipItems.SCHEMA,
                  Map.of("idempotencyKey", DynamoItems.s(key))));
    }
    tx.commit();
  }

  @Override
  public void audit(UUID actor, String action, String subject, JsonNode detail) {
    DynamoWrites.transaction(client)
        .insertOnly(
            tables.administratorAuditEventsTableName(),
            MembershipItems.auditItem(
                subject, UUID.randomUUID(), actor, action, detail, clock.instant()))
        .commit();
  }
}
