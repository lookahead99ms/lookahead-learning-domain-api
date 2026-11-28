package com.lookahead.domain.db.store.testaccounts;

import com.lookahead.domain.db.store.access.AccountRecords;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970: account data erasure on DynamoDB. Plans and free ledgers go through lane B's {@link
 * TestAccountStudyPlanErasure}, grants and the staff assignment through lane A's {@link
 * AccountRecords} (each its own committed operation). Then the membership purchases, their owner
 * pointers, provider-event receipts and fixture mock payments are deleted in bounded transactions,
 * every one fenced on the account's {@code ACCOUNT#id / META} revision as this erasure read it
 * (after the other lanes ran) and moving it: the retired PostgreSQL store serialised erasure and
 * checkout behind a row lock; here a checkout that commits a purchase in between moves the same
 * revision, so the delete fails with {@link DynamoWrites.Conflict} and a rerun deletes the new
 * purchase too (deletes are idempotent, so a crash resumes by rerunning as well). Audit events are
 * insert-only: the Local fixture purge of {@code membership_audit} has no DynamoDB equivalent.
 * Lifetime counters and permanent labels stay.
 */
public final class TestAccountDataErasureStore {
  /** Deletes per transaction, leaving room for the META fence. */
  static final int BATCH = DynamoWrites.MAX_ITEMS - 1;

  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final ObjectMapper json;
  private final TestAccountStudyPlanErasure plans;
  private final AccountRecords records;

  public TestAccountDataErasureStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      ObjectMapper json,
      TestAccountStudyPlanErasure plans,
      AccountRecords records) {
    this.client = Objects.requireNonNull(client);
    this.tables = Objects.requireNonNull(tables);
    this.json = Objects.requireNonNull(json);
    this.plans = plans;
    this.records = records;
  }

  /**
   * Erases the account's test data, answering the META revision the account stands at afterwards
   * (null when it has no subject), so a caller committing more under that account fences on it and
   * a checkout between the two conflicts.
   */
  Long eraseFenced(UUID id) {
    if (plans != null) plans.erase(id);
    if (records != null) records.clearAccessRecords(id);
    String table = tables.userAccountDetailsTableName();
    // The fence first, the purchases second: a purchase committed before this read is in the list,
    // one committed after it moves META.
    var meta = MembershipItems.get(client, table, DynamoItems.account(id), MembershipItems.META_SK);
    Long revision = meta.isEmpty() ? null : DynamoItems.number(meta, "revision");
    var keys = keysOf(id);
    for (int from = 0; from < keys.size(); from += BATCH) {
      var tx = DynamoWrites.transaction(client);
      if (revision != null) {
        tx.updateAtRevision(
            table,
            DynamoItems.key(DynamoItems.account(id), MembershipItems.META_SK),
            Map.of("revision", DynamoItems.n(revision + 1)),
            revision);
        revision++;
      }
      for (var key : keys.subList(from, Math.min(keys.size(), from + BATCH)))
        tx.delete(table, key, null);
      tx.commit();
    }
    return revision;
  }

  /** The keys this erasure deletes, in delete order; for tests and diagnostics too. */
  List<Map<String, AttributeValue>> keysOf(UUID id) {
    String table = tables.userAccountDetailsTableName();
    var keys = new ArrayList<Map<String, AttributeValue>>();
    for (var p : MembershipItems.purchases(client, tables, json, id)) {
      UUID purchase = UUID.fromString(p.path("id").asText());
      keys.add(DynamoItems.key(DynamoItems.account(id), MembershipItems.purchaseSk(purchase)));
      keys.add(DynamoItems.key(MembershipItems.purchasePointerPk(purchase), "ACCOUNT"));
      String paymentId = p.path("providerPaymentId").asText(null);
      if (paymentId != null)
        keys.add(
            DynamoItems.key(
                MembershipItems.providerEventPk("mock", paymentId + ":succeeded"),
                MembershipItems.RECEIPT_SK));
      String key = p.path("idempotencyKey").asText(null);
      if (key != null) {
        var mock =
            MembershipItems.get(
                client, table, MembershipItems.mockPaymentPk(key), MembershipItems.RECEIPT_SK);
        if (!mock.isEmpty()) {
          keys.add(DynamoItems.key(MembershipItems.mockPaymentPk(key), MembershipItems.RECEIPT_SK));
          keys.add(
              DynamoItems.key(
                  MembershipItems.mockPaymentIdPk(DynamoItems.string(mock, "paymentId")), "KEY"));
        }
      }
    }
    return keys;
  }
}
