package com.lookahead.domain.db.store.access;

import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DLV-970: role labels on DynamoDB: the owner flag from the control item, numbers from the permanent alias allocator. */
@Component
public class StaffAliasStore implements StaffAlias {
    private final AccountAggregateStore accounts;
    private final StaffAliasNumberStore aliases;

    @Autowired
    public StaffAliasStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    StaffAliasStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.accounts = new AccountAggregateStore(client, tables, clock);
        this.aliases = new StaffAliasNumberStore(client, tables, clock);
    }
    @Override public boolean owner(UUID accountId) { var control = accounts.control(accountId); return control != null && control.author(); }
    @Override public int number(UUID accountId, String role) { return aliases.assign(accountId, role).number(); }
}
