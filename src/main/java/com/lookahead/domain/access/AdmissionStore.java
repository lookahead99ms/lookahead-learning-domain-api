package com.lookahead.domain.access;

import com.lookahead.domain.access.AccountAdmissionService.Row;
import com.lookahead.domain.access.AccountAdmissionService.Waiting;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** DLV-970: the waiting list and the admission write of {@link AccountAdmissionService} on one store. */
public interface AdmissionStore {
    /** Runs {@code work} in one store transaction (PostgreSQL); on DynamoDB the admission itself is the transaction. */
    default <T> T transaction(Supplier<T> work) { return work.get(); }
    /** Waiting accounts (not admitted, not disabled or deleted), oldest request first, with duplicate count and sign-in methods. */
    List<Waiting> waiting();
    /** The account read for update, or null. */
    Row lock(UUID accountId);
    /**
     * Admits {@code row}: admitted now with the actor, {@code assignRoles} run for the staff assignment, and the
     * admission event with {@code roles}; returns the admission time.
     */
    Instant admit(UUID actor, Row row, List<String> roles, Runnable assignRoles);
}
