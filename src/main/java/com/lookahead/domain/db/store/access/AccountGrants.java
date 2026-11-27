package com.lookahead.domain.db.store.access;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

// DLV-970 cross-lane: provided by lane A
/** The topics an account holds outside memberships: valid {@code ACCOUNT#id / GRANT#topic} items plus staff topic grants. */
public interface AccountGrants {
    Set<String> grantedTopics(UUID account, Instant now);
}
