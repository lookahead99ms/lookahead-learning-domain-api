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

    /** For tests: the rows in order. */
    final class InMemory implements ReviewStatusHistory {
        public record Row(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {}
        public final java.util.List<Row> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {
            rows.add(new Row(recordId, kind, actorId, fromStatus, toStatus, noteSet));
        }
    }
}
