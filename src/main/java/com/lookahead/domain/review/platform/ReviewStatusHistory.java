package com.lookahead.domain.review.platform;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DLV-971: every status change on a record (kind review or request) or on a delivery ticket (kind ticket) is one
 * append-only row in review_status_events (V22); S3 holds current state only. {@code actorId} is null for the
 * automatic close. A history failure never undoes the change it describes: it is logged and the call succeeds.
 */
@FunctionalInterface
public interface ReviewStatusHistory {
    void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet);

    final class Jdbc implements ReviewStatusHistory {
        private final JdbcTemplate jdbc;
        public Jdbc(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {
            try {
                jdbc.update("INSERT INTO review_status_events(id,record_id,kind,actor_id,from_status,to_status,note_set) VALUES (?,?,?,?,?,?,?)",
                        UUID.randomUUID(), recordId, kind, actorId, fromStatus, toStatus, noteSet);
            } catch (RuntimeException failure) {
                String state = com.lookahead.domain.Events.sqlState(failure);
                var line = com.lookahead.domain.Events.failed(com.lookahead.domain.Events.EVENT, "platform-review.history", "HISTORY_NOT_WRITTEN")
                        .addKeyValue("target.id", recordId).addKeyValue("error.type", com.lookahead.domain.Events.types(failure));
                if (state != null) line = line.addKeyValue("db.sqlState", state);
                line.log("Status history for {} was not written ({}{}); the change itself is saved", recordId,
                        com.lookahead.domain.Events.types(failure), state == null ? "" : ", SQL state " + state);
            }
        }
    }

    /**
     * DLV-970: the same append-only history as one insert-only item per change in Administrator Audit Events,
     * {@code RECORD#<kind>#<recordId> / STATUS#<time>#<id>} with the actor ({@code system} for the automatic close), the
     * statuses, whether a note was set and {@code occurredAt} (the one time attribute every audit item carries). As with
     * SQL, a history that was not written is logged and never undoes the change.
     */
    final class Dynamo implements ReviewStatusHistory {
        static final String ACTION = "review.status";
        private final software.amazon.awssdk.services.dynamodb.DynamoDbClient client;
        private final com.lookahead.domain.persistence.dynamodb.DynamoTableNames tables;
        private final java.time.Clock clock;
        public Dynamo(software.amazon.awssdk.services.dynamodb.DynamoDbClient client, com.lookahead.domain.persistence.dynamodb.DynamoTableNames tables, java.time.Clock clock) {
            this.client = java.util.Objects.requireNonNull(client, "client"); this.tables = java.util.Objects.requireNonNull(tables, "tables");
            this.clock = java.util.Objects.requireNonNull(clock, "clock");
        }
        static String partition(String kind, String recordId) {
            return "RECORD#" + com.lookahead.domain.persistence.dynamodb.DynamoItems.encode(kind) + "#" + com.lookahead.domain.persistence.dynamodb.DynamoItems.encode(recordId);
        }
        @Override public void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {
            try {
                var at = clock.instant();
                var attributes = new java.util.HashMap<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>();
                attributes.put("recordId", com.lookahead.domain.persistence.dynamodb.DynamoItems.s(recordId));
                attributes.put("kind", com.lookahead.domain.persistence.dynamodb.DynamoItems.s(kind));
                attributes.put("actorId", actorId == null ? null : com.lookahead.domain.persistence.dynamodb.DynamoItems.s(actorId.toString()));
                attributes.put("fromStatus", fromStatus == null ? null : com.lookahead.domain.persistence.dynamodb.DynamoItems.s(fromStatus));
                attributes.put("toStatus", com.lookahead.domain.persistence.dynamodb.DynamoItems.s(toStatus));
                attributes.put("noteSet", com.lookahead.domain.persistence.dynamodb.DynamoItems.bool(noteSet));
                attributes.put("actor", com.lookahead.domain.persistence.dynamodb.DynamoItems.s(actorId == null ? "system" : actorId.toString()));
                attributes.put("action", com.lookahead.domain.persistence.dynamodb.DynamoItems.s(ACTION));
                attributes.put("occurredAt", com.lookahead.domain.persistence.dynamodb.DynamoItems.s(at.toString()));
                com.lookahead.domain.persistence.dynamodb.DynamoWrites.transaction(client).insertOnly(tables.administratorAuditEventsTableName(),
                        com.lookahead.domain.persistence.dynamodb.DynamoItems.item(partition(kind, recordId),
                                com.lookahead.domain.persistence.dynamodb.DynamoItems.timeKey("STATUS", at, UUID.randomUUID()), 1, attributes)).commit();
            } catch (RuntimeException failure) {
                com.lookahead.domain.Events.failed(com.lookahead.domain.Events.EVENT, "platform-review.history", "HISTORY_NOT_WRITTEN")
                        .addKeyValue("target.id", recordId).addKeyValue("error.type", com.lookahead.domain.Events.types(failure))
                        .log("Status history for {} was not written ({}); the change itself is saved", recordId, com.lookahead.domain.Events.types(failure));
            }
        }
    }

    /** For tests: the rows in order. */
    final class InMemory implements ReviewStatusHistory {
        public record Row(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {}
        public final java.util.List<Row> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {
            rows.add(new Row(recordId, kind, actorId, fromStatus, toStatus, noteSet));
        }
    }
}
