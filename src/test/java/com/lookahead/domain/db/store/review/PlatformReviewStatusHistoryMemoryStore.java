package com.lookahead.domain.db.store.review;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** For tests: the rows in order. */
public final class PlatformReviewStatusHistoryMemoryStore implements PlatformReviewStatusHistory {
    public record Row(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {}
    public final List<Row> rows = new CopyOnWriteArrayList<>();
    @Override public void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet) {
        rows.add(new Row(recordId, kind, actorId, fromStatus, toStatus, noteSet));
    }
}
