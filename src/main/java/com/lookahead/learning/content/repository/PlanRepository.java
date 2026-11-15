package com.lookahead.learning.content.repository;

import com.lookahead.learning.content.model.MutationReceipt;
import com.lookahead.learning.content.model.PlanRecord;
import com.lookahead.learning.content.model.PlanVersion;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Account-scoped plan persistence. DLV-970: {@link JdbcPlanRepository} is the PostgreSQL implementation (transactions
 * owned by the calling plan service); {@link DynamoPlanRepository} serves {@code app.persistence.store=dynamodb}, where
 * every call inside one PlanService transaction is collected by {@link DynamoOwnerUnit} and committed as one conditional
 * DynamoDB transaction under the owner's {@code ACCOUNT#id / META} revision.
 */
public interface PlanRepository {
    Set<String> grants(UUID account);
    List<PlanRecord> list(UUID account, int limit, int offset);
    Optional<PlanRecord> findPlan(UUID owner, UUID id, boolean lock);
    Optional<PlanVersion> findVersion(UUID owner, UUID id, UUID version);
    void createPlan(UUID owner, UUID id, UUID version, String goal, JsonNode progress, String name, long planNumber);
    long nextPlanNumber(UUID owner);
    long allocatePlanNumber(UUID owner);
    void rename(UUID owner, UUID id, String name, long revision);
    void updateProgress(UUID owner, UUID id, JsonNode progress, long revision);
    void activateVersion(UUID owner, UUID id, UUID version, long revision, String goal);
    void insertVersion(UUID owner, UUID id, UUID version, UUID parent, JsonNode snapshot, String digest,
                       JsonNode pins, JsonNode recovery, String reason, JsonNode membership);
    void insertActivity(UUID owner, UUID id, UUID version, long revision, String kind, JsonNode payload);
    boolean containsHistoricalContent(UUID account, UUID id, String content);
    void lockMutation(UUID account, UUID key);
    Optional<MutationReceipt> findReceipt(UUID account, UUID key);
    void insertReceipt(UUID account, UUID key, String hash, UUID id, JsonNode result, int status);
    void tombstoneReceipts(UUID account, UUID id);
    void deletePlan(UUID account, UUID id);
}
