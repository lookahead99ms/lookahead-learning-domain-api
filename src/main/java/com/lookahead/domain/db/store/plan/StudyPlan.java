package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.model.StudyPlanRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Account-scoped plan persistence. DLV-970: {@link StudyPlanStore} is the one implementation
 * (PostgreSQL retired 2026-11-17), where every call inside one StudyPlanService transaction is
 * collected by {@link com.lookahead.domain.db.store.dynamodb.OwnerTransactionUnit} and committed as
 * one conditional DynamoDB transaction under the owner's {@code ACCOUNT#id / META} revision.
 */
public interface StudyPlan {
  List<StudyPlanRecord> list(UUID account, int limit, int offset);

  Optional<StudyPlanRecord> findPlan(UUID owner, UUID id, boolean lock);

  void createPlan(
      UUID owner,
      UUID id,
      UUID version,
      String goal,
      JsonNode progress,
      String name,
      long planNumber);

  long nextPlanNumber(UUID owner);

  long allocatePlanNumber(UUID owner);

  void rename(UUID owner, UUID id, String name, long revision);

  void updateProgress(UUID owner, UUID id, JsonNode progress, long revision);

  void deletePlan(UUID account, UUID id);
}
