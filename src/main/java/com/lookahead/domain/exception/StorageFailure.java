package com.lookahead.domain.exception;

/**
 * What a store reports when the persistence layer itself did not do what was asked, said without naming its
 * technology, so a service that must tell it from its own refusals depends on this type only. Two kinds:
 * {@link #conflict()} true is a lost condition (another writer moved first, or the expected state is not there);
 * false is an unknown outcome (the store did not answer). DLV-1016: the stores' own types extend it
 * ({@code DynamoWrites.Conflict}, {@code DynamoWrites.Unavailable}); the web layer still answers those by their own
 * type (409 and 503).
 */
public abstract class StorageFailure extends RuntimeException {
    protected StorageFailure(String message) { super(message); }
    protected StorageFailure(String message, Throwable cause) { super(message, cause); }

    /** True: a condition did not hold or a key already existed. False: the outcome is unknown. */
    public abstract boolean conflict();
}
