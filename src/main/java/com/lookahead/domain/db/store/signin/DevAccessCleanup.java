package com.lookahead.domain.db.store.signin;

import java.time.Instant;
import java.util.UUID;

/**
 * DLV-970: what the 48-hour cleanup of waiting accounts ({@link com.lookahead.domain.util.SignInRules#WAITING_EXPIRY}) needs stored. DLV-1016:
 * the cleanup job lists the waiting rows, decides which account goes and writes the run's log lines; this store reads
 * and makes the fenced removal.
 */
public interface DevAccessCleanup {
    /** One row of the waiting list: the account and when it first asked. */
    interface Waiting {
        Instant requestedAt();
        UUID accountId();
    }
    /** The account behind a waiting row as read now; {@link #expire} is conditioned on exactly this read. */
    interface Candidate {
        /** False when no account is behind the row any more. */
        boolean exists();
        boolean admitted();
        /** False for an account the owner disabled or deleted while it waited. */
        boolean active();
    }
    Instant now();
    /** The waiting list in its stored order: oldest request first. */
    Iterable<Waiting> waiting();
    Candidate read(Waiting row);
    /** Deletes a waiting row alone (one left behind by an admission or removal); when someone else deleted it first, nothing happens. */
    void dropRow(Waiting row);
    /**
     * Removes the waiting account with its sign-in identities and records the {@code expire} event, in one transaction
     * conditioned on the account as {@link #read} saw it and on it still not being admitted. False when that condition
     * no longer held (an admission or another task was first): nothing was removed and nothing is retried.
     */
    boolean expire(Candidate candidate, Instant now);
}
