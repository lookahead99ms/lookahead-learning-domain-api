package com.lookahead.domain;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.repository.DynamoAccountStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DLV-970: the local-test seed on DynamoDB: {@code ACCOUNT#id / META} subjects and {@code GRANT#topic} items, insert-only. */
@Component
@Profile("accounts & local-test")
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoFixtureSeedStore implements FixtureSeedStore {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final DynamoAccountStore accounts;
    private final Clock clock;
    @Autowired
    public DynamoFixtureSeedStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public DynamoFixtureSeedStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = client; this.tables = tables; this.clock = clock; this.accounts = new DynamoAccountStore(client, tables, clock);
    }
    @Override public boolean insertSubject(UUID id) {
        var tx = DynamoWrites.transaction(client);
        tx.insertOnly(tables.userAccountDetailsTableName(), item(account(id), "META", attrs("revision", n(1), "lastPlanNumber", n(0), "createdAt", time(clock.instant()))));
        try { tx.commit(); return true; } catch (DynamoWrites.Conflict present) { return false; }
    }
    @Override public void grant(UUID id, String topic) { accounts.grant(id, topic); }
}
