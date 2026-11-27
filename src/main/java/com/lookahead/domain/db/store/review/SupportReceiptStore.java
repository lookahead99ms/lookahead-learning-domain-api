package com.lookahead.domain.db.store.review;

import com.lookahead.domain.model.SupportFeedbackModels;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionReads;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionUnit;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
public class SupportReceiptStore implements SupportReceipt {
    static final int RETRIES = 3;
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    @Autowired
    public SupportReceiptStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public SupportReceiptStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.client = client; this.tables = tables; this.clock = clock; }

    private String table() { return tables.userAccountDetailsTableName(); }
    private static String receiptKey(String key) { return "SUPPORT#" + DynamoItems.encode(key); }
    private static String timeKey(Instant at, String key) { return "SUPPORTTIME#" + DynamoItems.time(at) + "#" + DynamoItems.encode(key); }

    /**
     * The {@link OwnerTransactionUnit#run} convention: a private unit committed here when no transaction is bound (the
     * support controller), else the caller's bound unit, as the SQL {@code TransactionTemplate} (REQUIRED) joins its
     * caller. Only a private unit commits here, so only it can lose the owner fence and retry; the bound unit's commit
     * reports a conflict to its own caller.
     */
    // DLV-1016: the two cases in which nothing is reserved (the key carries another request; the quota the caller passes in is used
    // up) are compared here, inside the unit that is fenced on the owner's revision, between its reads and its two inserts, so that
    // two replicas cannot both pass the quota. They are answered as Reservation.refused(...): no statement has been recorded at
    // either point, so the unit's commit writes nothing and makes no call. SupportFeedbackService refuses.
    @Override public Reservation reserve(String owner, String key, String digest, int perWindow, Duration window) {
        UUID account = UUID.fromString(owner);
        for (int attempt = 1; ; attempt++) {
            try {
                return OwnerTransactionUnit.run(client, tables, clock, unit -> {
                    unit.fence(account);
                    var existing = get(DynamoItems.account(account), receiptKey(key));
                    if (!existing.isEmpty()) {
                        DynamoItems.requireSchema(existing, OwnerTransactionUnit.SCHEMA);
                        if (!DynamoItems.string(existing, "requestHash").equals(digest)) return Reservation.refused(Refused.KEY_REUSED);
                        return new Reservation(new SupportFeedbackModels.Receipt(DynamoItems.string(existing, "reference"), DynamoItems.string(existing, "status")), false);
                    }
                    Instant now = unit.now(), since = now.minus(window);
                    long recent = OwnerTransactionReads.queryAll(client, table(), DynamoItems.account(account), "SUPPORTTIME#", "schemaVersion, createdAt").stream()
                            .filter(item -> DynamoItems.instant(item, "createdAt").isAfter(since)).count();
                    if (recent >= perWindow) return Reservation.refused(Refused.QUOTA_REACHED);
                    String reference = UUID.randomUUID().toString();
                    unit.statement(table(), DynamoItems.account(account), receiptKey(key), tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(account), receiptKey(key), OwnerTransactionUnit.SCHEMA, Map.of(
                            "requestHash", DynamoItems.s(digest), "reference", DynamoItems.s(reference), "status", DynamoItems.s("unconfirmed"),
                            "createdAt", DynamoItems.s(now.toString()), "updatedAt", DynamoItems.s(now.toString())))));
                    unit.statement(table(), DynamoItems.account(account), timeKey(now, key), tx -> tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(account), timeKey(now, key), OwnerTransactionUnit.SCHEMA, Map.of(
                            "createdAt", DynamoItems.s(now.toString()), "requestKey", DynamoItems.s(key)))));
                    return new Reservation(new SupportFeedbackModels.Receipt(reference, "unconfirmed"), true);
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
        return OwnerTransactionReads.get(client, table(), pk, sk);
    }
}
