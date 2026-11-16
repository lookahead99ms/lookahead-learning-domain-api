package com.lookahead.learning.content.service;

import com.lookahead.learning.content.util.PlanJson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The PostgreSQL ledgers, SQL unchanged from FreePlanAllowance; runs inside PlanService's owner-locked transaction. */
@Repository
@Profile("accounts")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = com.lookahead.domain.persistence.PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcFreePlanLedgerStore implements FreePlanLedgerStore {
    private final JdbcTemplate jdbc;
    private final PlanJson json;

    public JdbcFreePlanLedgerStore(JdbcTemplate jdbc, PlanJson json) { this.jdbc = jdbc; this.json = json; }

    @Override public void recordCreation(UUID owner, UUID planId) {
        jdbc.update("INSERT INTO free_plan_creations(account_id,plan_id) VALUES (?,?) ON CONFLICT DO NOTHING", owner, planId);
    }

    @Override public int creations(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM free_plan_creations WHERE account_id=?", Integer.class, owner);
    }

    @Override public boolean creationRecorded(UUID owner, UUID planId) {
        Integer recorded = jdbc.queryForObject(
                "SELECT count(*) FROM free_plan_creations WHERE account_id=? AND plan_id=?",
                Integer.class, owner, planId);
        return recorded != null && recorded > 0;
    }

    @Override public int deletionsSince(UUID owner, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM free_plan_deletions WHERE account_id=? AND deleted_at>?",
                Integer.class, owner, java.sql.Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override public Instant oldestDeletionSince(UUID owner, Instant since) {
        java.sql.Timestamp oldest = jdbc.queryForObject(
                "SELECT min(deleted_at) FROM free_plan_deletions WHERE account_id=? AND deleted_at>?",
                java.sql.Timestamp.class, owner, java.sql.Timestamp.from(since));
        return oldest == null ? null : oldest.toInstant();
    }

    @Override public void recordDeletion(UUID owner, UUID planId) {
        jdbc.update("INSERT INTO free_plan_deletions(account_id,plan_id) VALUES (?,?)", owner, planId);
    }

    @Override public int activeFreePlans(UUID owner, UUID excludedPlan, LocalDate today, Predicate<Set<String>> freeContent) {
        return jdbc.query("""
            SELECT p.id, v.membership::text AS membership, l.state, l.start_date, l.day_count
            FROM plans p JOIN plan_versions v ON v.account_id=p.account_id AND v.id=p.current_version
            LEFT JOIN plan_lifecycle l ON l.account_id=p.account_id AND l.plan_id=p.id
            WHERE p.account_id=?
            """, (row, index) -> {
                UUID id = row.getObject("id", UUID.class);
                String state = row.getString("state");
                if (id.equals(excludedPlan) || "archived".equals(state) || "completed".equals(state)) return false;
                if ("active".equals(state) && row.getDate("start_date").toLocalDate()
                        .plusDays(row.getInt("day_count") + 90L).compareTo(today) <= 0) return false;
                Set<String> content = new HashSet<>();
                json.parse(row.getString("membership")).properties()
                        .forEach(entry -> content.add(entry.getValue().asText()));
                return freeContent.test(content);
            }, owner).stream().filter(Boolean::booleanValue).toList().size();
    }
}
