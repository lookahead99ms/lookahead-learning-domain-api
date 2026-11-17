package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.service.SupportFeedbackService;
import com.lookahead.learning.content.service.SupportReceiptStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: support receipts in DynamoDB. {@code ACCOUNT#id / SUPPORT#key} holds the request hash, unique reference and
 * status; {@code ACCOUNT#id / SUPPORTTIME#time#key} is the hour-window projection written in the same transaction. The
 * five-per-hour quota is read strongly and committed under the owner META revision, so two replicas cannot both pass it.
 */
@Repository
@Profile("accounts")
public class DynamoSupportReceiptRepository implements SupportReceiptStore {
    static final int PER_HOUR = 5;
    static final int RETRIES = 3;
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoSupportReceiptRepository(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    DynamoSupportReceiptRepository(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.client = client; this.tables = tables; this.clock = clock; }

    private String table() { return tables.userAccountDetailsTableName(); }
    private static String receiptKey(String key) { return "SUPPORT#" + DynamoItems.encode(key); }
    private static String timeKey(Instant at, String key) { return "SUPPORTTIME#" + DynamoItems.time(at) + "#" + DynamoItems.encode(key); }

    /**
     * The {@link DynamoOwnerUnit#run} convention: a private unit committed here when no transaction is bound (the
     * support controller), else the caller's bound unit, as the SQL {@code TransactionTemplate} (REQUIRED) joins its
     * caller. Only a private unit commits here, so only it can lose the owner fence and retry; the bound unit's commit
     * reports a conflict to its own caller.
     */
    @Override public Reservation reserve(String owner, String key, String digest) {
        UUID account = UUID.fromString(owner);
        for (int attempt = 1; ; attempt++) {
            try {
                return DynamoOwnerUnit.run(client, tables, clock, unit -> {
                    unit.fence(account);
                    var existing = get(DynamoItems.account(account), receiptKey(key));
                    if (!existing.isEmpty()) {
                        DynamoItems.requireSchema(existing, DynamoOwnerUnit.SCHEMA);
                        if (!DynamoItems.string(existing, "requestHash").equals(digest)) throw new AccountFailure(409, "FEEDBACK_KEY_REUSED", "Use the same message when checking an existing submission");
                        return new Reservation(new SupportFeedbackService.Receipt(DynamoItems.string(existing, "reference"), DynamoItems.string(existing, "status")), false);
                    }
                    Instant now = unit.now(), since = now.minusSeconds(3600);
                    long recent = DynamoOwnerReads.queryAll(client, table(), DynamoItems.account(account), "SUPPORTTIME#", "schemaVersion, createdAt").stream()
                            .filter(item -> DynamoItems.instant(item, "createdAt").isAfter(since)).count();
                    if (recent >= PER_HOUR) throw new AccountFailure(429, "FEEDBACK_RATE_LIMIT", "Please wait before sending another message");
                    String reference = UUID.randomUUID().toString();
                    unit.statement(table(), DynamoItems.account(account), receiptKey(key), tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(account), receiptKey(key), DynamoOwnerUnit.SCHEMA, Map.of(
                            "requestHash", DynamoItems.s(digest), "reference", DynamoItems.s(reference), "status", DynamoItems.s("unconfirmed"),
                            "createdAt", DynamoItems.s(now.toString()), "updatedAt", DynamoItems.s(now.toString())))));
                    unit.statement(table(), DynamoItems.account(account), timeKey(now, key), tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(account), timeKey(now, key), DynamoOwnerUnit.SCHEMA, Map.of(
                            "createdAt", DynamoItems.s(now.toString()), "requestKey", DynamoItems.s(key)))));
                    return new Reservation(new SupportFeedbackService.Receipt(reference, "unconfirmed"), true);
                });
            } catch (DynamoWrites.Conflict moved) { if (attempt >= RETRIES) throw moved; }
        }
    }

    @Override public void accepted(String owner, String key) {
        UUID account = UUID.fromString(owner);
        if (get(DynamoItems.account(account), receiptKey(key)).isEmpty()) return;
        DynamoWrites.transaction(client).update(table(), DynamoItems.key(DynamoItems.account(account), receiptKey(key)),
                Map.of("status", DynamoItems.s("accepted"), "updatedAt", DynamoItems.s(clock.instant().toString())), null).commit();
    }

    @Override public void release(String owner, String key) {
        UUID account = UUID.fromString(owner);
        var existing = get(DynamoItems.account(account), receiptKey(key));
        if (existing.isEmpty() || !"unconfirmed".equals(DynamoItems.string(existing, "status"))) return;
        DynamoWrites.transaction(client)
                .delete(table(), DynamoItems.key(DynamoItems.account(account), receiptKey(key)), "\"status\"=?", DynamoItems.s("unconfirmed"))
                .delete(table(), DynamoItems.key(DynamoItems.account(account), timeKey(DynamoItems.instant(existing, "createdAt"), key)), null)
                .commit();
    }

    private Map<String, AttributeValue> get(String pk, String sk) {
        return DynamoOwnerReads.get(client, table(), pk, sk);
    }
}
