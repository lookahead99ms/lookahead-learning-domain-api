package com.lookahead.domain.db.store.access;

import com.lookahead.domain.model.AccountDirectoryModels;
import java.util.Optional;
import java.util.UUID;

/**
 * DLV-970: the cloud-mode (no Identity service) part of {@code AccountDirectoryService} on one store: the case-insensitive
 * substring search in 50-row pages ordered by username, the exact username lookup and the by-id read, all limited
 * to admitted, enabled, not deleted accounts.
 */
public interface AccountDirectory {
    /** {@code term} is already lower-cased and stripped; page 0 is the first 50 rows. */
    AccountDirectoryModels.Page list(String term, int page);
    /** The one admitted, enabled account whose username or any identity username equals {@code query} (lower case); empty when none or several match (DLV-1016: the service refuses, not the store). */
    Optional<AccountDirectoryModels.Account> lookup(String query);
    /** The admitted, enabled account; empty otherwise. */
    Optional<AccountDirectoryModels.Account> byId(UUID id);
    void ensureSubject(UUID id);
}
