package com.lookahead.domain.access;

import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DLV-970: role labels on DynamoDB: the owner flag from the control item, numbers from the permanent alias allocator. */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoStaffAliasStore implements StaffAliasStore {
    private final DynamoCloudAccounts accounts;
    private final com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasStore aliases;

    @Autowired
    public DynamoStaffAliasStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    DynamoStaffAliasStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.accounts = new DynamoCloudAccounts(client, tables, clock);
        this.aliases = new com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasStore(client, tables, clock);
    }
    @Override public boolean owner(UUID accountId) { var control = accounts.control(accountId); return control != null && control.author(); }
    @Override public int number(UUID accountId, String role) { return aliases.assign(accountId, role).number(); }
}
