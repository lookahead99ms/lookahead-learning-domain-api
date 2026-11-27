package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.model.StudyPlanMutationReceipt;
import tools.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;

/** The receipt that makes a plan change safe to send twice: one per idempotency key. DLV-1016: split from the plan store interface; one class implements the three. */
public interface StudyPlanReceipts {
    void lockMutation(UUID account, UUID key);
    Optional<StudyPlanMutationReceipt> findReceipt(UUID account, UUID key);
    void insertReceipt(UUID account, UUID key, String hash, UUID id, JsonNode result, int status);
}
