package com.lookahead.domain.testaccounts;

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
    /** {@code withoutPlan}: ready learners whose scenario has a study plan they don't have yet (DLV-969 S37). */
    public record Status(int total, int ready, int deleted, int withoutPlan) {
        public Status(int total, int ready, int deleted) { this(total, ready, deleted, 0); }
        public int remaining() { return total - ready - deleted; }
    }
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
        if (!owner.allowed(actor)) throw new AccountFailure(403, "TEST_ROSTER_OWNER_ONLY", "Only the owner can create the test roster.");
        var existing = new HashSet<>(jdbc.queryForList("SELECT account_id FROM test_account_registry", UUID.class));
        TestAccountRoster.entries().stream().filter(entry -> !existing.contains(entry.accountId())).limit(BATCH)
                .forEach(entry -> transactions.executeWithoutResult(status -> service.admitRosterEntry(actor, entry)));
        return status();
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
