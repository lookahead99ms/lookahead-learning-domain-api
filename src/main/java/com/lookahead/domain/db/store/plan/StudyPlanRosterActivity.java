package com.lookahead.domain.db.store.plan;

import java.time.Instant;
import java.util.UUID;

// DLV-970 cross-lane: provided by lane B
/** What the DEV roster status reads about an account's plans. */
public interface StudyPlanRosterActivity {
    boolean hasPlan(UUID account);
    /** The latest plan, version or activity write of the account, or null. */
    Instant latestPlanWriteAt(UUID account);
}
