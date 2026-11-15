package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.TestAccountRegistryLookup;
import com.lookahead.domain.membership.MembershipItems;
import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970 lane G: lane C's test-account registry for lane A (directory, removal and owner maintenance refuse managed
 * accounts): the SQL {@code EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)} is the
 * {@code ACCOUNT#id / TEST} row, whatever its creation state.
 */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoTestAccountRegistry implements TestAccountRegistryLookup {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;

    public DynamoTestAccountRegistry(DynamoDbClient client, DynamoTableNames tables) { this.client = client; this.tables = tables; }

    @Override public boolean isTestAccount(UUID account) {
        return !MembershipItems.get(client, tables.userAccountDetailsTableName(), DynamoItems.account(account), TestAccountItems.REGISTRY_SK).isEmpty();
    }
}
