package com.lookahead.domain.access;

import java.util.UUID;

/** DLV-970: the owner's downgrade and Local learner deletion ({@link RealAccountMaintenance}) on one store. */
public interface AccountMaintenanceStore {
    /** Runs {@code work}; on DynamoDB each write below is its own transaction, so the default runs it as it is. */
    default void transaction(Runnable work) { work.run(); }
    void ensureSubject(UUID id);
    boolean subjectExists(UUID id);
    /** The subject read for update; false when there is none. */
    boolean lockSubject(UUID id);
    /** A completed learner deletion is already recorded for the account. */
    boolean alreadyDeleted(UUID id);
    boolean testAccount(UUID id);
    boolean author(UUID id);
    /** A staff assignment with roles, topic grants or the full curriculum. */
    boolean privileged(UUID id);
    boolean grants(UUID id);
    boolean openPurchases(UUID id);
    /** Roles, course grants and open memberships removed, with the maintenance record. */
    void downgrade(UUID actor, UUID id);
    /** Plans, free ledgers, receipts and grants erased, with the maintenance record. */
    void deleteLearner(UUID actor, UUID id);
}
