package com.lookahead.domain.db.store.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DLV-970: the free-plan ledgers behind {@code StudyPlanFreeAllowanceService}, which keeps every rule. {@link StudyPlanFreeLedgerStore}
 * keeps lifetime receipts
 * ({@code ACCOUNT#id / FREECREATE#planId}) with a counter on the owner META item that never decrements, and the
 * rolling-window deletion receipts ({@code ACCOUNT#id / FREEDELETE#time#planId}), all under the owner revision.
 */
public interface StudyPlanFreeLedger {
    /** Records a lifetime creation once per plan; a second record for the same plan changes nothing. */
    void recordCreation(UUID owner, UUID planId);
    int creations(UUID owner);
    boolean creationRecorded(UUID owner, UUID planId);
    int deletionsSince(UUID owner, Instant since);
    /** The earliest deletion after {@code since}, or null when there is none. */
    Instant oldestDeletionSince(UUID owner, Instant since);
    void recordDeletion(UUID owner, UUID planId);
    /** One plan that is not deleted, with its lifecycle row. Each value is read from the stored item when it is asked for. */
    interface PlanRow {
        UUID id();
        /** The lifecycle state as stored, or null when the plan has no lifecycle row. */
        String state();
        LocalDate startDate();
        long dayCount();
        UUID currentVersion();
    }
    /** The owner's plans that are not deleted, in stored order. Which of them count against the free allowance is the caller's rule. */
    List<PlanRow> plans(UUID owner);
    /** The content ids of one plan version; fails when the plan has no such version. */
    Set<String> contentIds(UUID owner, UUID plan, UUID version);
}
