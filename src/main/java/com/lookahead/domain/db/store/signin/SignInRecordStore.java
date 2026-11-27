package com.lookahead.domain.db.store.signin;

import static com.lookahead.domain.db.store.signin.AccountItems.*;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.util.SignInRules;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * The DynamoDB side of the {@code sign-in-records} maintenance job: one page of the account directory, and the removal
 * of one account's dead sign-in records (sessions not active for {@link SignInRules#RECORD_RETENTION}, and challenges whose
 * expiry is older than the rate-limit window, {@link SignInRules#WINDOW}; the two rules are {@link SignInRules#deadSession}
 * and {@link SignInRules#deadChallenge}). {@code FAMILY#} bindings stay. Nothing relies on TTL.
 */
@Component
public final class SignInRecordStore {
    static final int ACCOUNTS_PER_PAGE = 25;
    /** The accounts of one directory page, read one at a time in the page's order, and where the next page starts (null at the end). */
    public record DirectoryPage(Iterable<UUID> accounts, String afterKey) {}
    private final DynamoDbClient client;
    private final String table;
    private final Clock clock;
    @Autowired
    public SignInRecordStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public SignInRecordStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = client; this.table = tables.userAccountDetailsTableName(); this.clock = clock;
    }
    /** One strong page of up to {@link #ACCOUNTS_PER_PAGE} directory entries after {@code afterKey} (null starts at the beginning). */
    public DirectoryPage page(String afterKey) {
        var request = QueryRequest.builder().tableName(table).consistentRead(true).limit(ACCOUNTS_PER_PAGE)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(":pk", DynamoItems.s(DIRECTORY), ":prefix", DynamoItems.s("PERSON#")));
        if (afterKey != null) request.exclusiveStartKey(Map.of("pk", DynamoItems.s(DIRECTORY), "sk", DynamoItems.s(afterKey)));
        var response = client.query(request.build());
        Iterable<UUID> accounts = () -> response.items().stream().map(person -> DynamoItems.uuid(person, "accountId")).iterator();
        return new DirectoryPage(accounts, response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty() ? response.lastEvaluatedKey().get("sk").s() : null);
    }
    /** Deletes the account's dead sign-in records; the number of items removed. */
    public int retire(UUID account) {
        Instant now = clock.instant();
        var keys = new ArrayList<Map<String, AttributeValue>>();
        for (var session : query(client, table, account(account), "SESSION#")) {
            Instant active = instant(session, "lastActiveAt");
            if (SignInRules.deadSession(active, now)) keys.add(key(account(account), DynamoItems.string(session, "sk")));
        }
        for (var challenge : query(client, table, account(account), "CHALLENGE#")) {
            Instant expires = instant(challenge, "expiresAt");
            if (SignInRules.deadChallenge(expires, now)) keys.add(key(account(account), DynamoItems.string(challenge, "sk")));
        }
        for (int from = 0; from < keys.size(); from += DynamoWrites.MAX_ITEMS) {
            var tx = DynamoWrites.transaction(client);
            for (var key : keys.subList(from, Math.min(keys.size(), from + DynamoWrites.MAX_ITEMS))) tx.delete(table, key, null);
            tx.commit();
        }
        return keys.size();
    }
}
