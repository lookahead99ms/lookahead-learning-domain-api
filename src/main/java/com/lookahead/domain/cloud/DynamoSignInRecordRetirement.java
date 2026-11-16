package com.lookahead.domain.cloud;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.domain.sweeps.MaintenanceJobs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * DLV-970: the {@code sign-in-records} maintenance job. PostgreSQL keeps every sign-in row and reads the live ones by
 * index; on DynamoDB every request reads the account's whole {@code SESSION#} list, so dead records must go. One page
 * walks up to {@link #ACCOUNTS_PER_PAGE} directory entries and deletes, per account, the sessions not active for
 * {@link #RETENTION} (long past the 30-minute idle and 7-day absolute limits, revoked or not) and the challenges whose
 * expiry is older than the 15-minute rate-limit window. {@code FAMILY#} bindings stay: a revoked token family must
 * never be admitted again, and the binding without its session denies it. Nothing relies on TTL.
 */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoSignInRecordRetirement implements MaintenanceJobs.Job {
    public static final String NAME = "sign-in-records";
    public static final Duration RETENTION = Duration.ofDays(7);
    static final int ACCOUNTS_PER_PAGE = 25;
    private final DynamoDbClient client;
    private final String table;
    private final Clock clock;
    private String afterKey;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoSignInRecordRetirement(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public DynamoSignInRecordRetirement(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = client; this.table = tables.userAccountDetailsTableName(); this.clock = clock;
    }

    @Override public String name() { return NAME; }

    @Override public synchronized MaintenanceJobs.Page run(boolean afterFirstPage) {
        if (!afterFirstPage) afterKey = null;
        var request = QueryRequest.builder().tableName(table).consistentRead(true).limit(ACCOUNTS_PER_PAGE)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(":pk", DynamoItems.s(DIRECTORY), ":prefix", DynamoItems.s("PERSON#")));
        if (afterKey != null) request.exclusiveStartKey(Map.of("pk", DynamoItems.s(DIRECTORY), "sk", DynamoItems.s(afterKey)));
        var response = client.query(request.build());
        int cleaned = 0, failed = 0;
        for (var person : response.items()) {
            UUID id = DynamoItems.uuid(person, "accountId");
            try { cleaned += retire(id); }
            catch (RuntimeException failure) { failed++; }
        }
        afterKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty() ? response.lastEvaluatedKey().get("sk").s() : null;
        return new MaintenanceJobs.Page(cleaned, 0, failed, afterKey != null);
    }

    /** Deletes the account's dead sign-in records; the number of items removed. */
    int retire(UUID account) {
        Instant now = clock.instant(), sessionCutoff = now.minus(RETENTION), challengeCutoff = now.minus(DynamoCloudSignInRegistry.WINDOW);
        var keys = new ArrayList<Map<String, AttributeValue>>();
        for (var session : query(client, table, account(account), "SESSION#")) {
            Instant active = instant(session, "lastActiveAt");
            if (active != null && active.isBefore(sessionCutoff)) keys.add(key(account(account), DynamoItems.string(session, "sk")));
        }
        for (var challenge : query(client, table, account(account), "CHALLENGE#")) {
            Instant expires = instant(challenge, "expiresAt");
            if (expires != null && expires.isBefore(challengeCutoff)) keys.add(key(account(account), DynamoItems.string(challenge, "sk")));
        }
        for (int from = 0; from < keys.size(); from += DynamoWrites.MAX_ITEMS) {
            var tx = DynamoWrites.transaction(client);
            for (var key : keys.subList(from, Math.min(keys.size(), from + DynamoWrites.MAX_ITEMS))) tx.delete(table, key, null);
            tx.commit();
        }
        return keys.size();
    }
    static List<String> prefixes() { return List.of("SESSION#", "CHALLENGE#"); }
}
