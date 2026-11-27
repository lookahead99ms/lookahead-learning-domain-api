package com.lookahead.domain.db.store.review;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Clock;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the append-only history as one insert-only item per change in Administrator Audit Events,
 * {@code RECORD#<kind>#<recordId> / STATUS#<time>#<id>} with the actor ({@code system} for the automatic close), the
 * statuses, whether a note was set and {@code occurredAt} (the one time attribute every audit item carries). A
 * history that could not be written is this store's exception; the services that record a change catch and log it
 * ({@code PlatformReviewService} and {@code DeliveryPlanService}, each in its {@code recordStatus}), so it never undoes the change.
 */
public final class PlatformReviewStatusHistoryStore implements PlatformReviewStatusHistory {
    static final String ACTION = "review.status";
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;
    public PlatformReviewStatusHistoryStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = Objects.requireNonNull(client, "client"); this.tables = Objects.requireNonNull(tables, "tables");
        this.clock = Objects.requireNonNull(clock, "clock");
    }
    static String partition(String kind, String recordId) {
        return "RECORD#" + DynamoItems.encode(kind) + "#" + DynamoItems.encode(recordId);
    }
    @Override public void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {
        var at = clock.instant();
        var attributes = new HashMap<String, AttributeValue>();
        attributes.put("recordId", DynamoItems.s(recordId));
        attributes.put("kind", DynamoItems.s(kind));
        attributes.put("actorId", actorId == null ? null : DynamoItems.s(actorId.toString()));
        attributes.put("fromStatus", fromStatus == null ? null : DynamoItems.s(fromStatus));
        attributes.put("toStatus", DynamoItems.s(toStatus));
        attributes.put("noteSet", DynamoItems.bool(noteSet));
        attributes.put("actor", DynamoItems.s(actorId == null ? "system" : actorId.toString()));
        attributes.put("action", DynamoItems.s(ACTION));
        attributes.put("occurredAt", DynamoItems.s(at.toString()));
        DynamoWrites.transaction(client).insertOnly(tables.administratorAuditEventsTableName(),
                DynamoItems.item(partition(kind, recordId),
                        DynamoItems.timeKey("STATUS", at, UUID.randomUUID()), 1, attributes)).commit();
    }
}
