package com.lookahead.domain.testaccounts;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DLV-969 S35 (owner, 2026-11-03): Local's 35-account roster in DEV, for the admin and staff videos: 27 learner
 * scenarios, 2 co-authors and 6 admins as {@code <name>@lookahead.dev}, database-only and admitted by the owner.
 *
 * <p>One call creates at most {@link #BATCH} accounts so it ends well inside the Gateway's read timeout; the page
 * repeats the call until every account exists. An account that exists, or was deleted, is never created again.
 */
@Service
public class DevTestRoster {
    static final int BATCH = 5;
    /**
     * {@code withoutPlan}: ready learners whose scenario has a study plan they don't have yet (DLV-969 S37).
     * {@code deleted}: roster accounts deleted before "roster accounts are never deleted", which is also how many the
     * owner's Restore brings back ({@link #restore}).
     * {@code changed}: active roster accounts that differ from their scenario start (DLV-969, owner 2026-11-04), so the
     * page greys out "Reset all" at 0; see {@link #STATUS} for what counts. Every account in {@code withoutPlan} counts.
     */
    public record Status(int total, int ready, int deleted, int withoutPlan, int changed) {
        public Status(int total, int ready, int deleted) { this(total, ready, deleted, 0, 0); }
        public int remaining() { return total - ready - deleted; }
    }
    /** The owner types {@code RESTORE}. */
    public record Restore(String confirm) {}
    /** How many accounts this call restored: 0 when none was deleted. */
    public record Restored(int restored) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TestAccountPolicy policy;
    private final LocalAuthorAccess owner;
    private final TestAccountsService service;

    public DevTestRoster(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, TestAccountPolicy policy,
            LocalAuthorAccess owner, TestAccountsService service) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(transactionManager);
        this.policy = policy; this.owner = owner; this.service = service;
    }

    /** The roster's progress for the owner in DEV; null for everyone and everywhere else, so the page offers nothing. */
    public Status statusFor(AccountPrincipal actor) {
        return policy.cloud() && actor != null && actor.isEnabled() && owner.allowed(actor) ? status() : null;
    }

    /** Creates the next accounts that do not exist yet, each in its own transaction, and returns the progress. */
    public Status next(AccountPrincipal actor) {
        policy.requireCloud();
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue.");
        if (!owner.allowed(actor)) {
            Events.refused(Events.EVENT, "test-roster.create", "TEST_ROSTER_OWNER_ONLY").addKeyValue("account.id", actor.accountId())
                    .log("Test roster creation refused for account {}: only the owner creates it", actor.accountId());
            throw new AccountFailure(403, "TEST_ROSTER_OWNER_ONLY", "Only the owner can create the test roster.");
        }
        var existing = new HashSet<>(jdbc.queryForList("SELECT account_id FROM test_account_registry", UUID.class));
        var batch = TestAccountRoster.entries().stream().filter(entry -> !existing.contains(entry.accountId())).limit(BATCH).toList();
        batch.forEach(entry -> transactions.executeWithoutResult(status -> service.admitRosterEntry(actor, entry)));
        var progress = status();
        Events.success(Events.EVENT, "test-roster.create").addKeyValue("account.id", actor.accountId()).addKeyValue("count", batch.size())
                .addKeyValue("ready", progress.ready()).addKeyValue("total", progress.total())
                .log("DEV test roster: {} account(s) created in this batch; {} of {} ready, {} deleted, {} still to create",
                    batch.size(), progress.ready(), progress.total(), progress.deleted(), progress.remaining());
        return progress;
    }

    /**
     * DLV-969 (owner, 2026-11-04): the owner's Restore, DEV only. Every deleted roster account goes back to active and is
     * rebuilt from its scenario like Reset, each in its own transaction; created test accounts are never restored. With
     * nothing to restore it answers 0. Non-owner: 403 OWNER_REQUIRED.
     */
    public Restored restore(AccountPrincipal actor, Restore request) {
        try {
            policy.requireCloud();
            if (actor == null || !actor.isEnabled()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue.");
            service.requireOwner(actor);
            if (request == null || !"RESTORE".equals(request.confirm()))
                throw new AccountFailure(422, "ACCOUNT_CONFIRMATION_REQUIRED", "Type the required confirmation word.");
        } catch (AccountFailure refused) {
            Events.refused(Events.EVENT, "test-account.restore", refused.code()).addKeyValue("account.id", actor == null ? null : actor.accountId())
                    .log("Restore of deleted roster accounts refused: {}", refused.code());
            throw refused;
        }
        var deleted = new HashSet<>(jdbc.queryForList(
                "SELECT account_id FROM test_account_registry WHERE creation_state='deleted' AND creator_id IS NOT NULL AND initialization<>'created'", UUID.class));
        int restored = 0;
        for (var entry : TestAccountRoster.entries()) {
            if (!deleted.contains(entry.accountId())) continue;
            if (!Boolean.TRUE.equals(transactions.execute(status -> service.restoreRosterEntry(actor, entry)))) continue;
            restored++;
            Events.success(Events.EVENT, "test-account.restore").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", entry.accountId())
                    .log("Roster test account {} restored to its scenario by account {}", entry.accountId(), actor.accountId());
        }
        if (restored == 0) Events.success(Events.EVENT, "test-account.restore").addKeyValue("account.id", actor.accountId()).addKeyValue("count", 0)
                .log("Restore of deleted roster accounts: nothing to restore");
        return new Restored(restored);
    }

    /**
     * The roster's status in one query, run on every allowance read: each owner-created roster row, whether it has a
     * study plan, and whether it changed since its scenario start. The start is its latest {@code test_account_resets}
     * row (Reset, Restore, or the creation row {@link TestAccountsService#admitRosterEntry} writes), always the last
     * write of that transaction. Changed after it: the registry row (its triggers stamp {@code last_changed_at} on every
     * insert, update or delete of the account's plans, {@code account_grants}, {@code membership_purchases} and
     * {@code staff_access}, so deletions and the timestamp-less grants count too), or a newer timestamp on its purchases
     * ({@code created_at}, {@code updated_at}), plans ({@code created_at}, {@code updated_at}), {@code plan_versions}
     * ({@code created_at}), {@code plan_activity} ({@code recorded_at}) or {@code staff_access} ({@code updated_at}).
     * {@code plan_lifecycle} has no timestamp; every lifecycle change also updates its plan and writes plan activity.
     * A roster account without any such row (created before the creation row existed) counts as changed until reset.
     */
    static final String STATUS = """
            WITH roster AS (
              SELECT r.account_id,r.creation_state,r.last_changed_at,
                     (SELECT max(x.recorded_at) FROM test_account_resets x WHERE x.account_id=r.account_id) AS since
              FROM test_account_registry r WHERE r.creator_id IS NOT NULL AND r.initialization<>'created'),
            written AS (
              SELECT m.account_id,greatest(m.created_at,m.updated_at) AS at,false AS plan FROM membership_purchases m JOIN roster USING(account_id)
              UNION ALL SELECT p.account_id,greatest(p.created_at,p.updated_at),true FROM plans p JOIN roster USING(account_id)
              UNION ALL SELECT v.account_id,v.created_at,false FROM plan_versions v JOIN roster USING(account_id)
              UNION ALL SELECT a.account_id,a.recorded_at,false FROM plan_activity a JOIN roster USING(account_id)
              UNION ALL SELECT s.account_id,s.updated_at,false FROM staff_access s JOIN roster USING(account_id))
            SELECT r.account_id,r.creation_state,coalesce(bool_or(w.plan),false) AS has_plan,
                   (r.since IS NULL OR r.last_changed_at>r.since OR coalesce(max(w.at)>r.since,false)) AS changed
            FROM roster r LEFT JOIN written w USING(account_id)
            GROUP BY r.account_id,r.creation_state,r.last_changed_at,r.since
            """;

    private Status status() {
        var roster = new HashSet<UUID>();
        for (var entry : TestAccountRoster.entries()) roster.add(entry.accountId());
        int ready = 0, deleted = 0, withoutPlan = 0, changed = 0;
        for (var row : jdbc.queryForList(STATUS)) {
            UUID id = (UUID) row.get("account_id");
            if (!roster.contains(id)) continue;
            if ("deleted".equals(row.get("creation_state"))) { deleted++; continue; }
            ready++;
            boolean planMissing = needsPlan(id) && !Boolean.TRUE.equals(row.get("has_plan"));
            if (planMissing) withoutPlan++;
            if (planMissing || Boolean.TRUE.equals(row.get("changed"))) changed++;
        }
        return new Status(roster.size(), ready, deleted, withoutPlan, changed);
    }

    /** A roster learner whose scenario includes a study plan. */
    private static boolean needsPlan(UUID id) {
        return TestAccountRoster.entries().stream().filter(entry -> entry.accountId().equals(id) && !entry.staff())
                .anyMatch(entry -> LearnerScenario.of(Integer.parseInt(entry.username().substring(7))).planKind() != LearnerScenario.PlanKind.NONE);
    }
}
