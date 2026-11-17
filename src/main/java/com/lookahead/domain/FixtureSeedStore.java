package com.lookahead.domain;

import java.util.UUID;

/** DLV-970: the local-test seed writes of {@link DomainFixtureSeeder} on one store. */
public interface FixtureSeedStore {
    /** Runs the whole seed; on DynamoDB each write is its own idempotent insert, so the default runs the work as it is. */
    default void transaction(Runnable work) { work.run(); }
    /** The subject row; false when it already existed (a restart must not restore revoked grants). */
    boolean insertSubject(UUID id);
    /** A grant for {@code topic}, left as it is when one exists. */
    void grant(UUID id, String topic);
}
