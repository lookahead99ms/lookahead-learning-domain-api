package com.lookahead.domain.testaccounts;

import com.lookahead.domain.membership.MembershipItems;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Instant;
import java.util.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970 (lane C): the DynamoDB item families of test accounts.
 * <ul>
 *   <li>{@code ACCOUNT#<id> / TEST}: the registry row (username, initialization, creationState, creatorId, lastChangedBy,
 *   lastChangedAt, createdAt, revision); {@code TESTACCOUNTS#00 / ENTRY#<encoded username>#<id>}: its directory
 *   projection with the same fields, written in the same transaction (lists never scan).</li>
 *   <li>Audit table, insert-only: {@code TEST#<id> / RESET#<mutationKey>} reset receipts (accountId, mutationKey, actorId,
 *   preset, occurredAt: the one time attribute every audit item of this service carries); the "scenario start" of an
 *   account is its latest receipt.</li>
 *   <li>{@code CREATOR#<id> / CREATE#<mutationKey>}: a creation reservation (accountId, username, state, createdAt,
 *   revision); {@code ACCOUNT#<id> / CREATION}: pointer back to creator and key.</li>
 * </ul>
 */
public final class TestAccountItems {
    private TestAccountItems() {}
    public static final int SCHEMA = MembershipItems.SCHEMA;
    public static final String REGISTRY_SK = "TEST";
    public static final String DIRECTORY_PK = "TESTACCOUNTS#00", ENTRY_PREFIX = "ENTRY#";
    public static final String RESET_PREFIX = "RESET#";
    public static final String CREATE_PREFIX = "CREATE#", CREATION_SK = "CREATION";

    public static String resetPk(UUID account) { return "TEST#" + account; }
    public static String resetSk(UUID key) { return RESET_PREFIX + key; }
    public static String entrySk(String username, UUID account) { return ENTRY_PREFIX + DynamoItems.encode(username) + "#" + account; }
    public static String creatorPk(UUID creator) { return "CREATOR#" + creator; }
    public static String createSk(UUID key) { return CREATE_PREFIX + key; }

    /** The account's reset receipts (Reset, Restore, create), oldest first by the time they happened (parsed, never compared as text). */
    public static List<ObjectNode> resets(DynamoDbClient client, DynamoTableNames tables, ObjectMapper mapper, UUID account) {
        var rows = new ArrayList<ObjectNode>();
        for (var item : MembershipItems.query(client, tables.administratorAuditEventsTableName(), resetPk(account), RESET_PREFIX)) rows.add(MembershipItems.row(item, mapper));
        rows.sort(MembershipItems.byTime("occurredAt").thenComparing(r -> r.path("mutationKey").asText()));
        return rows;
    }
    /** When the account last started from its scenario (latest reset receipt), or null when never. */
    public static Instant latestResetAt(DynamoDbClient client, DynamoTableNames tables, UUID account) {
        Instant latest = null;
        for (var item : MembershipItems.query(client, tables.administratorAuditEventsTableName(), resetPk(account), RESET_PREFIX)) {
            Instant at = DynamoItems.instant(item, "occurredAt");
            if (latest == null || at.isAfter(latest)) latest = at;
        }
        return latest;
    }
    public static Map<String, AttributeValue> resetItem(UUID account, UUID key, UUID actor, String preset, Instant at) {
        return DynamoItems.item(resetPk(account), resetSk(key), SCHEMA, Map.of("accountId", DynamoItems.s(account.toString()), "mutationKey", DynamoItems.s(key.toString()),
                "actorId", DynamoItems.s(actor.toString()), "preset", DynamoItems.s(preset), "occurredAt", DynamoItems.s(at.toString())));
    }
}
