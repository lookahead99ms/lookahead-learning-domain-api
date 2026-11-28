package com.lookahead.domain.db.store.dynamodb;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Real DynamoDB Local: the PartiQL transaction semantics every adapter relies on. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoWritesDatabaseTest {
  private static DynamoDbClient client;
  private static final DynamoTableNames TABLES = DynamoTableNames.local();
  private static final String ACCOUNTS = TABLES.userAccountDetailsTableName(),
      AUDIT = TABLES.administratorAuditEventsTableName();
  private final String pk = "ACCOUNT#" + UUID.randomUUID();

  @BeforeAll
  static void connect() {
    URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
    if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()))
      throw new IllegalArgumentException("Loopback fixture required");
    client =
        DynamoDbClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("synthetic", "synthetic")))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    for (String name : List.of(ACCOUNTS, AUDIT)) {
      try {
        client.describeTable(DescribeTableRequest.builder().tableName(name).build());
        continue;
      } catch (ResourceNotFoundException absent) {
        /* create */
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
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
  }

  private Map<String, AttributeValue> read(String table, String sk) {
    return client
        .getItem(
            GetItemRequest.builder()
                .tableName(table)
                .consistentRead(true)
                .key(DynamoItems.key(pk, sk))
                .build())
        .item();
  }

  @Test
  void insertIsRejectedOnAnExistingKeyAndNothingElseInTheTransactionLands() {
    var meta =
        DynamoItems.item(
            pk, "META", 1, Map.of("revision", DynamoItems.n(1), "name", DynamoItems.s("Ann")));
    var event = DynamoItems.item(pk, "EVENT#1", 1, Map.of("action", DynamoItems.s("admit")));
    DynamoWrites.transaction(client)
        .insertOnly(ACCOUNTS, meta)
        .insertOnly(AUDIT, event)
        .commit("t-" + pk);
    assertThat(read(ACCOUNTS, "META").get("name").s()).isEqualTo("Ann");
    assertThat(read(AUDIT, "EVENT#1")).isNotEmpty();
    // The same event again, with a different second item: the duplicate cancels everything.
    var other = DynamoItems.item(pk, "GRANT#x", 1, Map.of());
    var conflict =
        catchThrowableOfType(
            DynamoWrites.Conflict.class,
            () ->
                DynamoWrites.transaction(client)
                    .insertOnly(ACCOUNTS, other)
                    .insertOnly(
                        AUDIT,
                        DynamoItems.item(
                            pk, "EVENT#1", 1, Map.of("action", DynamoItems.s("forged"))))
                    .commit());
    assertThat(conflict.reasons().get(1)).isEqualTo("DuplicateItem");
    assertThat(conflict.only(1)).isTrue();
    assertThat(read(ACCOUNTS, "GRANT#x")).isEmpty();
    assertThat(read(AUDIT, "EVENT#1").get("action").s()).isEqualTo("admit");
  }

  @Test
  void updatesAndDeletesAreFencedByRevisionAndAMissingItemFailsTheTransaction() {
    DynamoWrites.transaction(client)
        .insertOnly(
            ACCOUNTS,
            DynamoItems.item(
                pk,
                "META",
                1,
                Map.of(
                    "revision",
                    DynamoItems.n(1),
                    "name",
                    DynamoItems.s("Ann"),
                    "old",
                    DynamoItems.s("x"))))
        .commit();
    var key = DynamoItems.key(pk, "META");
    DynamoWrites.transaction(client)
        .update(
            ACCOUNTS,
            key,
            Map.of(
                "revision",
                DynamoItems.n(2),
                "name",
                DynamoItems.s("Bea"),
                "old",
                DynamoItems.nul()),
            "revision=?",
            DynamoItems.n(1))
        .commit();
    var stored = read(ACCOUNTS, "META");
    assertThat(stored.get("name").s()).isEqualTo("Bea");
    assertThat(stored.get("revision").n()).isEqualTo("2");
    assertThat(DynamoItems.stringOrNull(stored, "old")).isNull();
    // Stale revision: conflict, nothing changes.
    assertThat(
            catchThrowableOfType(
                    DynamoWrites.Conflict.class,
                    () ->
                        DynamoWrites.transaction(client)
                            .updateAtRevision(
                                ACCOUNTS,
                                key,
                                Map.of("revision", DynamoItems.n(2), "name", DynamoItems.s("Cat")),
                                1)
                            .commit())
                .reasons())
        .containsExactly("ConditionalCheckFailed");
    assertThat(read(ACCOUNTS, "META").get("name").s()).isEqualTo("Bea");
    DynamoWrites.transaction(client)
        .updateAtRevision(
            ACCOUNTS, key, Map.of("revision", DynamoItems.n(3), "name", DynamoItems.s("Cat")), 2)
        .commit();
    assertThat(read(ACCOUNTS, "META").get("name").s()).isEqualTo("Cat");
    // An update of a missing item is a conflict, not a silent upsert.
    assertThat(
            catchThrowableOfType(
                    DynamoWrites.Conflict.class,
                    () ->
                        DynamoWrites.transaction(client)
                            .update(
                                ACCOUNTS,
                                DynamoItems.key(pk, "MISSING"),
                                Map.of("x", DynamoItems.n(1)),
                                null)
                            .commit())
                .reasons())
        .containsExactly("ConditionalCheckFailed");
    assertThat(read(ACCOUNTS, "MISSING")).isEmpty();
    // Condition-only check passes when true and cancels the whole transaction when false.
    DynamoWrites.transaction(client)
        .check(ACCOUNTS, key, 1, "revision=?", DynamoItems.n(3))
        .insertOnly(ACCOUNTS, DynamoItems.item(pk, "GRANT#a", 1, Map.of()))
        .commit();
    assertThat(read(ACCOUNTS, "GRANT#a")).isNotEmpty();
    assertThat(
            catchThrowableOfType(
                    DynamoWrites.Conflict.class,
                    () ->
                        DynamoWrites.transaction(client)
                            .check(ACCOUNTS, key, 1, "revision=?", DynamoItems.n(9))
                            .insertOnly(ACCOUNTS, DynamoItems.item(pk, "GRANT#b", 1, Map.of()))
                            .commit())
                .only(0))
        .isTrue();
    assertThat(read(ACCOUNTS, "GRANT#b")).isEmpty();
    assertThat(read(ACCOUNTS, "META").get("revision").n()).isEqualTo("3");
    // Delete under a stale fence fails; under the right one succeeds; a missing item fails.
    assertThat(
            catchThrowableOfType(
                    DynamoWrites.Conflict.class,
                    () ->
                        DynamoWrites.transaction(client)
                            .delete(ACCOUNTS, key, "revision=?", DynamoItems.n(1))
                            .commit())
                .reasons())
        .containsExactly("ConditionalCheckFailed");
    DynamoWrites.transaction(client).delete(ACCOUNTS, key, "revision=?", DynamoItems.n(3)).commit();
    assertThat(read(ACCOUNTS, "META")).isEmpty();
    // A delete of a missing item is a silent no-op even with a condition (idempotent erasure).
    assertThatCode(() -> DynamoWrites.transaction(client).delete(ACCOUNTS, key, null).commit())
        .doesNotThrowAnyException();
    assertThatCode(() ->
            DynamoWrites.transaction(client)
                .delete(ACCOUNTS, key, "schemaVersion=?", DynamoItems.n(1))
                .commit())
        .doesNotThrowAnyException();
    // One item may appear once per transaction: check + delete on the same key is rejected by the
    // service, not a conflict.
    assertThatThrownBy(() ->
            DynamoWrites.transaction(client)
                .check(ACCOUNTS, DynamoItems.key(pk, "GRANT#a"), 1, null)
                .delete(ACCOUNTS, DynamoItems.key(pk, "GRANT#a"), null)
                .commit())
        .isInstanceOf(DynamoWrites.Unavailable.class);
  }

  @Test
  void theSameTokenReplaysAnIdenticalCommitWithoutARepeatedWrite() {
    var item = DynamoItems.item(pk, "META", 1, Map.of("revision", DynamoItems.n(1)));
    String token = UUID.randomUUID().toString();
    DynamoWrites.transaction(client).insertOnly(ACCOUNTS, item).commit(token);
    // Same token, same statements: the SDK/service treats it as the earlier request.
    assertThatCode(() -> DynamoWrites.transaction(client).insertOnly(ACCOUNTS, item).commit(token))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> DynamoWrites.transaction(client).insertOnly(ACCOUNTS, item).commit())
        .isInstanceOf(DynamoWrites.Conflict.class);
  }
}
