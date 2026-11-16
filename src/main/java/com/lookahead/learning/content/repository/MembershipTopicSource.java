package com.lookahead.learning.content.repository;

import java.util.Set;
import java.util.UUID;

// DLV-970 cross-lane: provided by lane C. The catalog topics an account's active memberships unlock, on the selected store.
public interface MembershipTopicSource {
    Set<String> topics(UUID account);
}
