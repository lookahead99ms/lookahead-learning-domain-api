package com.lookahead.domain.db.store.testaccounts;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.membership.MembershipItems;
import java.util.UUID;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970 lane G: lane C's test-account registry for lane A (directory, removal and owner
 * maintenance refuse managed accounts): the SQL {@code EXISTS(SELECT 1 FROM test_account_registry
 * WHERE account_id=?)} is the {@code ACCOUNT#id / TEST} row, whatever its creation state.
 */
@Component
public class TestAccountRegistryStore implements TestAccountRegistry {
  private final DynamoDbClient client;
  private final DynamoTableNames tables;

  public TestAccountRegistryStore(DynamoDbClient client, DynamoTableNames tables) {
    this.client = client;
    this.tables = tables;
  }

  @Override
  public boolean isTestAccount(UUID account) {
    return !MembershipItems.get(
            client,
            tables.userAccountDetailsTableName(),
            DynamoItems.account(account),
            TestAccountItems.REGISTRY_SK)
        .isEmpty();
  }
}
