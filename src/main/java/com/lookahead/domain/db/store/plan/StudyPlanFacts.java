package com.lookahead.domain.db.store.plan;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

// DLV-970 cross-lane: provided by lane B. What the merge page shows of an account's study plans on the selected store.
public interface StudyPlanFacts {
    /** One live plan: its current version's snapshot (null when unreadable) and its progress, as the plan card reads them. */
    record Card(UUID id, String name, JsonNode snapshot, JsonNode progress, String createdAt) {}
    /** The account's live plans, oldest first. */
    List<Card> cards(UUID account);
}
