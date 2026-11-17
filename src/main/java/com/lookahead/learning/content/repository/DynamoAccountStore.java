package com.lookahead.learning.content.repository;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: subjects and grants on DynamoDB. {@code ACCOUNT#id / META} is the owner controller (revision, plan number);
 * {@code ACCOUNT#id / GRANT#topic} carries {@code topicId}, {@code grantedAt} and an optional {@code validUntil}
 * checked in code at read time.
 */
@Repository
public class DynamoAccountStore implements AccountStore {
    private final DynamoCloudAccounts accounts;

    @Autowired
    public DynamoAccountStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public DynamoAccountStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.accounts = new DynamoCloudAccounts(client, tables, clock); }

    @Override public void ensureSubject(UUID subject) {
        if (!get(accounts.client(), accounts.accounts(), account(subject), "META").isEmpty()) return;
        var tx = accounts.transaction();
        tx.insertOnly(accounts.accounts(), item(account(subject), "META", attrs("revision", n(1), "lastPlanNumber", n(0), "createdAt", time(accounts.now()))));
        try { tx.commit(); } catch (DynamoWrites.Conflict present) { /* created meanwhile: the same outcome as ON CONFLICT DO NOTHING */ }
    }
    /** A grant for {@code topic}, left as it is when one exists (ON CONFLICT DO NOTHING); for seeding and tests. */
    public void grant(UUID subject, String topic) {
        var tx = accounts.transaction();
        tx.insertOnly(accounts.accounts(), item(account(subject), grantSk(topic), attrs("topicId", s(topic), "grantedAt", time(accounts.now()))));
        try { tx.commit(); } catch (DynamoWrites.Conflict present) { /* already granted */ }
    }
    @Override public List<String> grantTopics(UUID subject) { return grantTopics(subject, accounts.now()); }
    /** The grants valid at {@code now} (an expired {@code validUntil} is checked here, never by TTL). */
    public List<String> grantTopics(UUID subject, Instant now) {
        return query(accounts.client(), accounts.accounts(), account(subject), "GRANT#").stream()
                .filter(item -> { Instant until = instant(item, "validUntil"); return until == null || until.isAfter(now); })
                .map(item -> DynamoItems.string(item, "topicId")).toList();
    }
    /** Deletes every grant of the subject (the SQL {@code DELETE FROM account_grants WHERE account_id=?}), in bounded transactions. */
    public void revokeAll(UUID subject) {
        var keys = new java.util.ArrayList<java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>>();
        for (var grant : query(accounts.client(), accounts.accounts(), account(subject), "GRANT#")) keys.add(key(account(subject), DynamoItems.string(grant, "sk")));
        for (int from = 0; from < keys.size(); from += DynamoWrites.MAX_ITEMS) {
            var tx = accounts.transaction();
            for (var key : keys.subList(from, Math.min(keys.size(), from + DynamoWrites.MAX_ITEMS))) tx.delete(accounts.accounts(), key, null);
            tx.commit();
        }
    }
    @Override public boolean authorEnabled(UUID subject) {
        var control = accounts.control(subject);
        return control != null && control.author() && control.enabled();
    }
}
