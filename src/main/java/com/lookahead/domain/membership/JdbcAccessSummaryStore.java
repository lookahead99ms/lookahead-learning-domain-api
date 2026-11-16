package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.PersistenceStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The PostgreSQL reads of {@link AccessSummaryService}, unchanged. */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAccessSummaryStore implements AccessSummaryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcAccessSummaryStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public List<JsonNode> activeSnapshots(UUID target,Instant at){
        Timestamp now=Timestamp.from(at);
        return jdbc.query("""
            SELECT plan_snapshot FROM membership_purchases WHERE account_id=?
            AND status IN ('succeeded','scheduled') AND superseded_at IS NULL
            AND starts_at<=? AND ends_at>? ORDER BY plan_id,id
            """,(r,n)->json.readTree(r.getString(1)),target,now,now);
    }
    @Override public Set<String> grantedTopics(UUID target,Instant at){
        Timestamp now=Timestamp.from(at);
        var courses=new TreeSet<>(jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id=? AND (valid_until IS NULL OR valid_until>?)",String.class,target,now));
        for(String grants:jdbc.queryForList("SELECT topic_grants::text FROM staff_access WHERE account_id=?",String.class,target))
            json.readTree(grants).forEach(value->courses.add(value.asText()));
        return courses;
    }
}
