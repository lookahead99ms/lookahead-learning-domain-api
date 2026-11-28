package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionReads;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * The DynamoDB Local fixture of this lane's database tests: loopback only, local table names,
 * tables created only if absent.
 */
public final class StudyPlanStoreLocalFixture {
  public static final DynamoTableNames TABLES = DynamoTableNames.local();

  private StudyPlanStoreLocalFixture() {}

  public static final class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant now) {
      this.now = now;
    }

    public void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  public static DynamoDbClient connect() {
    URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
    if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()))
      throw new IllegalArgumentException("Loopback fixture required");
    var client =
        DynamoDbClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("synthetic", "synthetic")))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    for (String name : TABLES.all()) {
      try {
        client.describeTable(DescribeTableRequest.builder().tableName(name).build());
        continue;
      } catch (ResourceNotFoundException absent) {
        /* create it */
      }
      client.createTable(
          CreateTableRequest.builder()
              .tableName(name)
              .billingMode(BillingMode.PAY_PER_REQUEST)
              .keySchema(
                  KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                  KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
              .attributeDefinitions(
                  AttributeDefinition.builder()
                      .attributeName("pk")
                      .attributeType(ScalarAttributeType.S)
                      .build(),
                  AttributeDefinition.builder()
                      .attributeName("sk")
                      .attributeType(ScalarAttributeType.S)
                      .build())
              .build());
    }
    return client;
  }

  /** A fresh owner with its META item (what the identity slice writes at account creation). */
  public static UUID owner(DynamoDbClient client) {
    UUID owner = UUID.randomUUID();
    client.putItem(
        PutItemRequest.builder()
            .tableName(TABLES.userAccountDetailsTableName())
            .item(
                DynamoItems.item(
                    DynamoItems.account(owner),
                    "META",
                    1,
                    Map.of(
                        "revision",
                        DynamoItems.n(1),
                        "lastPlanNumber",
                        DynamoItems.n(0),
                        "createdAt",
                        DynamoItems.s("2026-11-10T00:00:00Z"))))
            .build());
    return owner;
  }

  public static Map<String, AttributeValue> item(
      DynamoDbClient client, String table, String pk, String sk) {
    return client
        .getItem(
            GetItemRequest.builder()
                .tableName(table)
                .consistentRead(true)
                .key(DynamoItems.key(pk, sk))
                .build())
        .item();
  }

  public static List<Map<String, AttributeValue>> items(
      DynamoDbClient client, String pk, String prefix) {
    return OwnerTransactionReads.queryAll(client, TABLES.userAccountDetailsTableName(), pk, prefix);
  }

  public static void purge(DynamoDbClient client, String table, String pk) {
    for (var item : OwnerTransactionReads.queryAll(client, table, pk, null))
      client.deleteItem(
          DeleteItemRequest.builder()
              .tableName(table)
              .key(DynamoItems.key(pk, item.get("sk").s()))
              .build());
  }
}
