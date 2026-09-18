package com.lookahead.learning.content.repository;

import com.lookahead.learning.content.model.MutationReceipt;
import com.lookahead.learning.content.model.PlanRecord;
import com.lookahead.learning.content.model.PlanVersion;
import com.lookahead.learning.content.util.PlanJson;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Account-scoped persistence. Transactions are owned by the calling plan service. */
@Repository
@Profile("accounts")
public class PlanRepository {
    private final JdbcTemplate jdbc;
    private final PlanJson json;
    private com.lookahead.learning.content.service.ProtectedContentPolicy publication;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void publication(com.lookahead.learning.content.service.ProtectedContentPolicy value){publication=value;}

    public PlanRepository(JdbcTemplate jdbc, PlanJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Set<String> grants(UUID account) {
        var result=new HashSet<>(jdbc.queryForList(
                "SELECT topic_id FROM account_grants WHERE account_id=? AND (valid_until IS NULL OR valid_until > now())", String.class, account));
        if(publication!=null && com.lookahead.learning.content.repository.AccountRepository.currentIdentityEnabled(account))
            publication.contentGrants(Set.copyOf(result)).forEach(id->result.add("content:"+id));
        return result;
    }

    public List<PlanRecord> list(UUID account, int limit, int offset) {
        return jdbc.query("SELECT * FROM plans WHERE account_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, row) -> readPlan(rs), account, limit, offset);
    }

    public Optional<PlanRecord> findPlan(UUID owner, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM plans WHERE account_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> readPlan(rs), owner, id).stream().findFirst();
    }

    private PlanRecord readPlan(ResultSet rs) throws SQLException {
        return new PlanRecord(rs.getObject("id", UUID.class), rs.getLong("revision"),
                rs.getObject("current_version", UUID.class), rs.getString("goal"),
                json.parse(rs.getString("progress")), rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString());
    }

    public Optional<PlanVersion> findVersion(UUID owner, UUID id, UUID version) {
        return jdbc.query("SELECT * FROM plan_versions WHERE account_id=? AND plan_id=? AND id=?",
                (rs, row) -> new PlanVersion(rs.getObject("id", UUID.class), json.parse(rs.getString("snapshot")),
                        json.parse(rs.getString("provenance")), json.parse(rs.getString("recovery")),
                        json.parse(rs.getString("membership"))), owner, id, version).stream().findFirst();
    }

    public void createPlan(UUID owner, UUID id, UUID version, String goal, JsonNode progress) {
        jdbc.update("INSERT INTO plans(id,account_id,revision,current_version,goal,progress) VALUES (?,?,1,?,?,?::jsonb)",
                id, owner, version, goal, json.json(progress));
    }

    public void updateProgress(UUID owner, UUID id, JsonNode progress, long revision) {
        jdbc.update("UPDATE plans SET progress=?::jsonb,revision=?,updated_at=now() WHERE account_id=? AND id=?",
                json.json(progress), revision, owner, id);
    }

    public void activateVersion(UUID owner, UUID id, UUID version, long revision, String goal) {
        jdbc.update("UPDATE plans SET current_version=?,revision=?,goal=?,updated_at=now() WHERE account_id=? AND id=?",
                version, revision, goal, owner, id);
    }

    public void insertVersion(UUID owner, UUID id, UUID version, UUID parent, JsonNode snapshot, String digest,
                              JsonNode pins, JsonNode recovery, String reason, JsonNode membership) {
        jdbc.update("INSERT INTO plan_versions(id,account_id,plan_id,parent_version,snapshot,snapshot_digest,provenance,recovery,reason,membership) VALUES (?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,?,?::jsonb)",
                version, owner, id, parent, json.json(snapshot), digest, json.json(pins), json.json(recovery), reason, json.json(membership));
    }

    public void insertActivity(UUID owner, UUID id, UUID version, long revision, String kind, JsonNode payload) {
        jdbc.update("INSERT INTO plan_activity(id,account_id,plan_id,version_id,revision,kind,payload) VALUES (?,?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(), owner, id, version, revision, kind, json.json(payload));
    }

    public boolean containsHistoricalContent(UUID account, UUID id, String content) {
        for (String value : jdbc.queryForList("SELECT membership::text FROM plan_versions WHERE account_id=? AND plan_id=?",
                String.class, account, id)) {
            for (var entry : json.parse(value).properties()) {
                if (entry.getValue().asText().equals(content)) return true;
            }
        }
        return false;
    }

    public void lockMutation(UUID account, UUID key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, account + ":" + key);
    }

    public Optional<MutationReceipt> findReceipt(UUID account, UUID key) {
        return jdbc.query("SELECT request_hash,response,response_status,deleted,plan_id FROM mutation_receipts WHERE account_id=? AND mutation_key=?",
                (rs, row) -> new MutationReceipt(rs.getString(1), rs.getString(2) == null ? null : json.parse(rs.getString(2)),
                        rs.getInt(3), rs.getBoolean(4), rs.getObject(5, UUID.class)), account, key).stream().findFirst();
    }

    public void insertReceipt(UUID account, UUID key, String hash, UUID id, JsonNode result, int status) {
        jdbc.update("INSERT INTO mutation_receipts(account_id,mutation_key,request_hash,plan_id,response,response_status) VALUES (?,?,?,?,?::jsonb,?)",
                account, key, hash, id, json.json(result), status);
    }

    public void tombstoneReceipts(UUID account, UUID id) {
        jdbc.update("UPDATE mutation_receipts SET deleted=true,response=NULL WHERE account_id=? AND plan_id=?", account, id);
    }

    public void deletePlan(UUID account, UUID id) {
        jdbc.update("DELETE FROM plans WHERE account_id=? AND id=?", account, id);
    }
}
