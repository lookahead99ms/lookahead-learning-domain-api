package com.lookahead.domain.db.store.membership;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** DLV-970: what {@code MembershipAccessSummaryService} reads: purchased plan snapshots and granted topics at one instant. */
public interface MembershipAccessSummary {
    /** Plan snapshots of purchases granting access at {@code now}, by plan id then id. */
    List<JsonNode> activeSnapshots(UUID account, Instant now);
    /** Valid account grants plus staff topic grants. */
    Set<String> grantedTopics(UUID account, Instant now);
}
