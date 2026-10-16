package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.util.PlanJson;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Free allowances are checked after PlanService takes the owner row lock. */
@Service
@Profile("accounts")
public class FreePlanAllowance {
    static final int MAX_ACTIVE = 2;
    static final int MAX_CREATIONS = 15;
    static final int MAX_DELETIONS = 3;
    static final long DELETION_WINDOW_DAYS = 21;

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final ProtectedContentPolicy publication;
    private final PlanJson json;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FreePlanAllowance(JdbcTemplate jdbc, AccountRepository accounts,
            ProtectedContentPolicy publication, PlanJson json) {
        this(jdbc, accounts, publication, json, Clock.systemUTC());
    }

    FreePlanAllowance(JdbcTemplate jdbc, AccountRepository accounts,
            ProtectedContentPolicy publication, PlanJson json, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.publication = publication;
        this.json = json;
        this.clock = clock;
    }

    public boolean freeAccount(UUID owner) {
        return accounts.findTopicGrants(owner).stream().noneMatch(grant -> !grant.startsWith("content:"));
    }

    public boolean freeContent(Set<String> contentIds) {
        return !contentIds.isEmpty() && publication.freeContentIds().containsAll(contentIds);
    }

    public boolean beforeCreate(UUID owner, Set<String> contentIds) {
        if (!freeAccount(owner)) return false;
        if (!freeContent(contentIds))
            throw new AccountFailure(403, "COURSE_ACCESS_REQUIRED", "Get course access before saving this plan");
        if (activeFreePlans(owner, null) >= MAX_ACTIVE)
            throw new AccountFailure(409, "FREE_ACTIVE_PLAN_LIMIT", "Archive or delete a free plan before saving another");
        if (creations(owner) >= MAX_CREATIONS)
            throw new AccountFailure(409, "FREE_LIFETIME_PLAN_LIMIT", "This account has used its 15 free plan creations");
        return true;
    }

    public void afterCreate(UUID owner, UUID planId, boolean freeCreation) {
        if (freeCreation)
            jdbc.update("INSERT INTO free_plan_creations(account_id,plan_id) VALUES (?,?) ON CONFLICT DO NOTHING", owner, planId);
    }

    public void beforeActivation(UUID owner, UUID planId, Set<String> contentIds) {
        if (freeAccount(owner) && freeContent(contentIds) && activeFreePlans(owner, planId) >= MAX_ACTIVE)
            throw new AccountFailure(409, "FREE_ACTIVE_PLAN_LIMIT", "Archive or delete a free plan before activating another");
    }

    public boolean beforeFreeScopeChange(UUID owner, UUID planId, Set<String> priorContent, Set<String> nextContent,
            boolean active) {
        if (!freeAccount(owner) || freeContent(priorContent) || !freeContent(nextContent)) return false;
        if (active && activeFreePlans(owner, planId) >= MAX_ACTIVE)
            throw new AccountFailure(409, "FREE_ACTIVE_PLAN_LIMIT", "Archive or delete a free plan before changing this plan");
        Integer recorded = jdbc.queryForObject(
                "SELECT count(*) FROM free_plan_creations WHERE account_id=? AND plan_id=?",
                Integer.class, owner, planId);
        if (recorded != null && recorded > 0) return false;
        if (creations(owner) >= MAX_CREATIONS)
            throw new AccountFailure(409, "FREE_LIFETIME_PLAN_LIMIT", "This account has used its 15 free plan creations");
        return true;
    }

    public void beforeDelete(UUID owner) {
        if (!freeAccount(owner)) return;
        var since = Instant.now(clock).minusSeconds(DELETION_WINDOW_DAYS * 86400);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM free_plan_deletions WHERE account_id=? AND deleted_at>?",
                Integer.class, owner, java.sql.Timestamp.from(since));
        if (count != null && count >= MAX_DELETIONS) {
            java.sql.Timestamp oldest = jdbc.queryForObject(
                    "SELECT min(deleted_at) FROM free_plan_deletions WHERE account_id=? AND deleted_at>?",
                    java.sql.Timestamp.class, owner, java.sql.Timestamp.from(since));
            String available = oldest == null ? "after the current window" :
                    oldest.toInstant().plusSeconds(DELETION_WINDOW_DAYS * 86400)
                            .atOffset(ZoneOffset.UTC).toString();
            throw new AccountFailure(429, "FREE_DELETE_LIMIT",
                    "Three free-account deletions are allowed in each rolling 21-day window. You can delete again " + available);
        }
    }

    public void afterDelete(UUID owner, UUID planId, boolean freeAccountAtDelete) {
        if (freeAccountAtDelete)
            jdbc.update("INSERT INTO free_plan_deletions(account_id,plan_id) VALUES (?,?)", owner, planId);
    }

    public int creations(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM free_plan_creations WHERE account_id=?", Integer.class, owner);
    }

    private int activeFreePlans(UUID owner, UUID excludedPlan) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
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
                return freeContent(content);
            }, owner).stream().filter(Boolean::booleanValue).toList().size();
    }
}
