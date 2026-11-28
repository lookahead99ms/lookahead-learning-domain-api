package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.model.StudyPlanVersion;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The versions of a study plan and the activity recorded on them. DLV-1016: split from the plan
 * store interface ({@link StudyPlan} is the plan itself, {@link StudyPlanReceipts} the retry
 * receipts); one class implements the three.
 */
public interface StudyPlanVersions {
  Optional<StudyPlanVersion> findVersion(UUID owner, UUID id, UUID version);

  void insertVersion(
      UUID owner,
      UUID id,
      UUID version,
      UUID parent,
      JsonNode snapshot,
      String digest,
      JsonNode pins,
      JsonNode recovery,
      String reason,
      JsonNode membership);

  void activateVersion(UUID owner, UUID id, UUID version, long revision, String goal);

  void insertActivity(
      UUID owner, UUID id, UUID version, long revision, String kind, JsonNode payload);

  boolean containsHistoricalContent(UUID account, UUID id, String content);
}
