package com.lookahead.domain.access;

import java.util.UUID;

/**
 * DLV-970: the cloud-mode (no Identity service) part of {@link AccountDirectory} on one store: the case-insensitive
 * substring search in 50-row pages ordered by username, the exact username lookup and the by-id read, all limited
 * to admitted, enabled, not deleted accounts.
 */
public interface AccountDirectoryStore {
    /** {@code term} is already lower-cased and stripped; page 0 is the first 50 rows. */
    AccountDirectory.Page list(String term, int page);
    /** Exactly one admitted, enabled account whose username or any identity username equals {@code query} (lower case). */
    AccountDirectory.Account lookup(String query);
    AccountDirectory.Account byId(UUID id);
    void ensureSubject(UUID id);
}
