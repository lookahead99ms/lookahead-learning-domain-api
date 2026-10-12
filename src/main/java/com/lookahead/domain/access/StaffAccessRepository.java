package com.lookahead.domain.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class StaffAccessRepository {
    public record Assignment(UUID id, UUID accountId, String pendingEmail, Set<String> roles,
            Set<String> topicGrants, boolean fullCurriculum, long revision) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public StaffAccessRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    private Assignment row(java.sql.ResultSet r, int index) throws java.sql.SQLException {
        return new Assignment(r.getObject("id",UUID.class),r.getObject("account_id",UUID.class),
                r.getString("pending_email"),strings(r.getString("roles")),strings(r.getString("topic_grants")),
                r.getBoolean("full_curriculum"),r.getLong("revision"));
    }
    private Set<String> strings(String json) { return Set.copyOf(mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class,String.class))); }
    public Assignment forAccount(UUID account) { return jdbc.query("SELECT * FROM staff_access WHERE account_id=?",this::row,account).stream().findFirst().orElse(null); }
    public List<Assignment> list() { return jdbc.query("SELECT * FROM staff_access ORDER BY updated_at DESC LIMIT 500",this::row); }
    public Assignment lock(UUID id) { return jdbc.query("SELECT * FROM staff_access WHERE id=? FOR UPDATE",this::row,id).stream().findFirst().orElse(null); }
    public Assignment pending(String email) { return jdbc.query("SELECT * FROM staff_access WHERE account_id IS NULL AND pending_email=? AND updated_at > clock_timestamp()-interval '14 days' AND (roles <> '[]'::jsonb OR topic_grants <> '[]'::jsonb OR full_curriculum) FOR UPDATE",this::row,email).stream().findFirst().orElse(null); }
    public void insert(Assignment a) {
        jdbc.update("INSERT INTO staff_access(id,account_id,pending_email,roles,topic_grants,full_curriculum,revision) VALUES(?,?,?,?::jsonb,?::jsonb,?,?)",
                a.id(),a.accountId(),a.pendingEmail(),mapper.writeValueAsString(a.roles()),mapper.writeValueAsString(a.topicGrants()),a.fullCurriculum(),a.revision());
    }
    public void update(Assignment a) {
        jdbc.update("UPDATE staff_access SET account_id=?,pending_email=?,roles=?::jsonb,topic_grants=?::jsonb,full_curriculum=?,revision=?,updated_at=clock_timestamp() WHERE id=?",
                a.accountId(),a.pendingEmail(),mapper.writeValueAsString(a.roles()),mapper.writeValueAsString(a.topicGrants()),a.fullCurriculum(),a.revision(),a.id());
    }
    public void event(UUID actor, String action, Assignment old, Assignment next) {
        jdbc.update("INSERT INTO staff_access_events(event_id,assignment_id,actor_id,action,previous_state,next_state) VALUES(?,?,?, ?,?::jsonb,?::jsonb)",
                UUID.randomUUID(),next.id(),actor,action,old==null?null:mapper.writeValueAsString(old),mapper.writeValueAsString(next));
    }
}
