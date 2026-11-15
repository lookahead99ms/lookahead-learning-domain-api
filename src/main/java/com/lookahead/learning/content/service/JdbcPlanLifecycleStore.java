package com.lookahead.learning.content.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** The PostgreSQL lifecycle rows, SQL unchanged from PlanLifecycle; called inside PlanService's owner-locked transaction. */
@Repository
@Profile("accounts")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = com.lookahead.domain.persistence.PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcPlanLifecycleStore implements PlanLifecycleStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcPlanLifecycleStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public List<PlanLifecycle.Entry> entries(UUID owner){
        return jdbc.query("SELECT * FROM plan_lifecycle WHERE account_id=? ORDER BY plan_id",(r,n)->{
            var subjects=new TreeSet<String>();json.readTree(r.getString("subject_ids")).forEach(x->subjects.add(x.asText()));
            return new PlanLifecycle.Entry(r.getObject("plan_id",UUID.class),r.getString("state"),r.getDate("start_date").toLocalDate(),r.getInt("day_count"),r.getInt("daily_minutes"),Set.copyOf(subjects));
        },owner);
    }
    @Override public void setState(UUID owner,UUID id,String state){
        jdbc.update("UPDATE plan_lifecycle SET state=? WHERE account_id=? AND plan_id=?",state,owner,id);
    }
    @Override public void reserve(UUID owner,UUID id,LocalDate start,int days,int minutes,Set<String> subjects){
        jdbc.update("""
            INSERT INTO plan_lifecycle(account_id,plan_id,state,start_date,day_count,daily_minutes,subject_ids)
            VALUES (?,?,'active',?,?,?,?::jsonb)
            ON CONFLICT(plan_id) DO UPDATE SET state='active',start_date=excluded.start_date,
              day_count=excluded.day_count,daily_minutes=excluded.daily_minutes,subject_ids=excluded.subject_ids
            WHERE plan_lifecycle.account_id=excluded.account_id
            """,owner,id,java.sql.Date.valueOf(start),days,minutes,json.writeValueAsString(subjects));
    }
}
