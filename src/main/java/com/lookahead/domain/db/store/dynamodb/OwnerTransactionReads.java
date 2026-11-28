package com.lookahead.domain.db.store.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * DLV-970: the strongly consistent reads every owner-scoped store in this lane uses; SDK failures
 * become {@link OwnerTransactionUnit.OwnerReadUnavailable}.
 */
public final class OwnerTransactionReads {
  private OwnerTransactionReads() {}

  /**
   * The projection of a version item when only its membership is read: {@code schemaVersion} is
   * included so an existing item never comes back as an empty map (which the readers take for
   * "absent").
   */
  public static final String CONTENT_IDS = "schemaVersion, contentIds";

  public static Map<String, AttributeValue> get(
      DynamoDbClient client, String table, String pk, String sk) {
    return get(client, table, pk, sk, null);
  }

  /**
   * One strong read; {@code projection} (a ProjectionExpression of plain attribute names, or null
   * for all) bounds the bytes read.
   */
  public static Map<String, AttributeValue> get(
      DynamoDbClient client, String table, String pk, String sk, String projection) {
    var request =
        GetItemRequest.builder().tableName(table).key(DynamoItems.key(pk, sk)).consistentRead(true);
    if (projection != null) request.projectionExpression(projection);
    return read("GetItem", table, () -> client.getItem(request.build()).item());
  }

  /**
   * Every item of {@code pk} whose sort key starts with {@code prefix}, in sort-key order, through
   * all pages.
   */
  public static List<Map<String, AttributeValue>> queryAll(
      DynamoDbClient client, String table, String pk, String prefix) {
    return queryAll(client, table, pk, prefix, null);
  }

  /**
   * As {@link #queryAll(DynamoDbClient, String, String, String)}, reading only {@code projection}
   * when given.
   */
  public static List<Map<String, AttributeValue>> queryAll(
      DynamoDbClient client, String table, String pk, String prefix, String projection) {
    var items = new ArrayList<Map<String, AttributeValue>>();
    Map<String, AttributeValue> start = null;
    do {
      var query = QueryRequest.builder().tableName(table).consistentRead(true);
      if (prefix == null)
        query
            .keyConditionExpression("pk = :pk")
            .expressionAttributeValues(Map.of(":pk", DynamoItems.s(pk)));
      else
        query
            .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
            .expressionAttributeValues(
                Map.of(":pk", DynamoItems.s(pk), ":prefix", DynamoItems.s(prefix)));
      if (projection != null) query.projectionExpression(projection);
      if (start != null) query.exclusiveStartKey(start);
      var response = read("Query", table, () -> client.query(query.build()));
      items.addAll(response.items());
      start =
          response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
              ? response.lastEvaluatedKey()
              : null;
    } while (start != null);
    return items;
  }

  public static <T> T read(Supplier<T> call) {
    try {
      return call.get();
    } catch (SdkException failure) {
      throw new OwnerTransactionUnit.OwnerReadUnavailable(failure);
    }
  }

  /**
   * As above, naming the operation and table for the ERROR line a failed read ends in (plain logs,
   * owner 2026-11-17).
   */
  public static <T> T read(String operation, String table, Supplier<T> call) {
    try {
      return call.get();
    } catch (SdkException failure) {
      throw new OwnerTransactionUnit.OwnerReadUnavailable(operation, table, failure);
    }
  }
}
