package com.lookahead.domain.db.store.dynamodb;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

/**
 * Runtime read-only watermark reader. Full history, checksums and physical effects belong to
 * migration verify.
 */
// A technology helper of db.store.dynamodb: "Dynamo data version" is its subject, so the word stays
// in the name (DLV-1016).
public final class DynamoDataVersionStore {
  static final Map<String, AttributeValue> CURRENT_KEY =
      Map.of(
          "pk", AttributeValue.builder().s("DATA_VERSION#DOMAIN").build(),
          "sk", AttributeValue.builder().s("CURRENT").build());
  private final DynamoDbClient client;
  private final String table;

  public DynamoDataVersionStore(DynamoDbClient client, DynamoTableNames tables) {
    this.client = Objects.requireNonNull(client, "client");
    this.table = Objects.requireNonNull(tables, "tables").protectedOwnerIdentityTableName();
  }

  public long currentVersion() {
    var response =
        client.getItem(
            GetItemRequest.builder()
                .tableName(table)
                .key(CURRENT_KEY)
                .overrideConfiguration(c ->
                    c.apiCallTimeout(Duration.ofSeconds(2))
                        .apiCallAttemptTimeout(Duration.ofSeconds(1)))
                .consistentRead(true)
                .projectionExpression("pk, sk, #version, ledgerFormatVersion")
                .expressionAttributeNames(Map.of("#version", "version"))
                .build());
    if (response == null) throw new InvalidDataVersion();
    var item = response.item();
    if (item.isEmpty()) return 0;
    if (!CURRENT_KEY.get("pk").equals(item.get("pk"))
        || !CURRENT_KEY.get("sk").equals(item.get("sk"))
        || !AttributeValue.builder().n("1").build().equals(item.get("ledgerFormatVersion"))) {
      throw new InvalidDataVersion();
    }
    var version = item.get("version");
    if (version == null || version.n() == null || !version.n().matches("[1-9][0-9]{0,18}")) {
      throw new InvalidDataVersion();
    }
    try {
      return Long.parseLong(version.n());
    } catch (NumberFormatException invalid) {
      throw new InvalidDataVersion();
    }
  }

  /** Stable diagnostic; never embeds malformed table contents. */
  static final class InvalidDataVersion extends IllegalStateException {
    InvalidDataVersion() {
      super("Invalid data-version watermark");
    }
  }
}
