package com.lookahead.domain.membership;

import java.util.Set;
import java.util.UUID;

/** Purchases snapshot catalog scope, but resolve courses dynamically on every access check. */
public interface MembershipGrants {
    Set<String> topics(UUID account);
}
