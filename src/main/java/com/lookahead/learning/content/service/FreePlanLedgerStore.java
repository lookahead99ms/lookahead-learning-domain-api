package com.lookahead.learning.content.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * DLV-970: the free-plan ledgers behind {@link FreePlanAllowance}, which keeps every rule. {@link DynamoFreePlanLedgerStore}
 * keeps lifetime receipts
 * ({@code ACCOUNT#id / FREECREATE#planId}) with a counter on the owner META item that never decrements, and the
 * rolling-window deletion receipts ({@code ACCOUNT#id / FREEDELETE#time#planId}), all under the owner revision.
 */
public interface FreePlanLedgerStore {
    /** Records a lifetime creation once per plan; a second record for the same plan changes nothing. */
    void recordCreation(UUID owner, UUID planId);
    int creations(UUID owner);
    boolean creationRecorded(UUID owner, UUID planId);
    int deletionsSince(UUID owner, Instant since);
    /** The earliest deletion after {@code since}, or null when there is none. */
    Instant oldestDeletionSince(UUID owner, Instant since);
    void recordDeletion(UUID owner, UUID planId);
    /** Plans of the owner, other than {@code excludedPlan}, that are not archived/completed/expired and whose current content is all free. */
    int activeFreePlans(UUID owner, UUID excludedPlan, LocalDate today, Predicate<Set<String>> freeContent);
}
