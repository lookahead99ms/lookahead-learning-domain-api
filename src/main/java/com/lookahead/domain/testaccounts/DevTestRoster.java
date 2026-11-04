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
     */
    public record Status(int total, int ready, int deleted, int withoutPlan) {
        public Status(int total, int ready, int deleted) { this(total, ready, deleted, 0); }
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

    private Status status() {
        var roster = new HashSet<UUID>();
        for (var entry : TestAccountRoster.entries()) roster.add(entry.accountId());
        int ready = 0, deleted = 0, withoutPlan = 0;
        var withPlans = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT p.account_id FROM plans p JOIN test_account_registry r ON r.account_id=p.account_id"
                + " WHERE r.creator_id IS NOT NULL AND r.initialization<>'created'", UUID.class));
        for (var row : jdbc.queryForList("SELECT account_id,creation_state FROM test_account_registry WHERE creator_id IS NOT NULL AND initialization<>'created'")) {
            UUID id = (UUID) row.get("account_id");
            if (!roster.contains(id)) continue;
            if ("deleted".equals(row.get("creation_state"))) { deleted++; continue; }
            ready++;
            if (needsPlan(id) && !withPlans.contains(id)) withoutPlan++;
        }
        return new Status(roster.size(), ready, deleted, withoutPlan);
    }

    /** A roster learner whose scenario includes a study plan. */
    private static boolean needsPlan(UUID id) {
        return TestAccountRoster.entries().stream().filter(entry -> entry.accountId().equals(id) && !entry.staff())
                .anyMatch(entry -> LearnerScenario.of(Integer.parseInt(entry.username().substring(7))).planKind() != LearnerScenario.PlanKind.NONE);
    }
}
