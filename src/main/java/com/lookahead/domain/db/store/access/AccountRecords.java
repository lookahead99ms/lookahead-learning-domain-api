package com.lookahead.domain.db.store.access;

import java.util.Collection;
import java.util.UUID;

// DLV-970 cross-lane: provided by lane A
/** Writes on lane A's items that test-account setup, reset and erasure make (each its own committed operation). */
public interface AccountRecords {
    /** The admitted record of a DEV test account on the test issuer, its one identity binding and its admit event. */
    void admitTestAccount(UUID account, String username, String displayName, UUID admittedBy);
    boolean hasAccountRecord(UUID account);
    /** Grants topics ({@code ACCOUNT#id / GRANT#topic}) with no expiry. */
    void grantTopics(UUID account, Collection<String> topics);
    /** Deletes every grant and empties the staff assignment (roles, topic grants, full curriculum), bumping its revision. */
    void clearAccessRecords(UUID account);
}
