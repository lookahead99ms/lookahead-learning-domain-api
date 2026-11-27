package com.lookahead.domain.db.store.access;

import java.util.UUID;

/**
 * DLV-970: what {@code AccountMaintenanceService} reads about an account before a downgrade or a learner deletion.
 * DLV-1016: split from the maintenance store interface; the writes are {@link AccountMaintenance}.
 */
public interface AccountMaintenanceFacts {
    boolean subjectExists(UUID id);
    /** A completed learner deletion is already recorded for the account. */
    boolean alreadyDeleted(UUID id);
    boolean testAccount(UUID id);
    boolean author(UUID id);
    /** A staff assignment with roles, topic grants or the full curriculum. */
    boolean privileged(UUID id);
    boolean grants(UUID id);
    boolean openPurchases(UUID id);
}
