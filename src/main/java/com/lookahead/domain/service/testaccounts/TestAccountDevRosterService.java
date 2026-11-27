package com.lookahead.domain.service.testaccounts;

import com.lookahead.domain.model.TestAccountRoster;
import com.lookahead.domain.model.TestAccountScenario;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistryRows;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * DLV-969 S35 (owner, 2026-11-03): Local's roster in DEV, for the admin and staff videos: the 27 learner scenarios as
 * {@code <name>@lookahead.dev}, database-only and admitted by the owner. DLV-971 (owner, 2026-11-06): learner test
 * accounts only; the co-author and admin test accounts the roster used to carry are retired by V21 and never created.
 *
 * <p>One call creates at most {@link #BATCH} accounts so it ends well inside the Gateway's read timeout; the page
 * repeats the call until every account exists. An account that exists, or was deleted, is never created again.
 */
@Service
public class TestAccountDevRosterService {
    static final int BATCH = 5;
    /**
     * {@code withoutPlan}: ready learners whose scenario has a study plan they don't have yet (DLV-969 S37).
     * {@code deleted}: roster accounts deleted before "roster accounts are never deleted", which is also how many the
     * owner's Restore brings back ({@link #restore}).
     * {@code changed}: active roster accounts that differ from their scenario start (DLV-969, owner 2026-11-04), so the
     * page greys out "Reset all" at 0; the store's roster status row says whether an account changed since its scenario start. Every account in {@code withoutPlan} counts.
     */
    public record Status(int total, int ready, int deleted, int withoutPlan, int changed) {
        public Status(int total, int ready, int deleted) { this(total, ready, deleted, 0, 0); }
        public int remaining() { return total - ready - deleted; }
    }
    /** The owner types {@code RESTORE}. */
    public record Restore(String confirm) {}
    /** How many accounts this call restored: 0 when none was deleted. */
    public record Restored(int restored) {}
    private final TestAccountRegistryRows store;
    private final TestAccountModeService policy;
    private final AuthorPreviewLocalService owner;
    private final TestAccountService service;

    @Autowired
    public TestAccountDevRosterService(TestAccountRegistryRows store, TestAccountModeService policy, AuthorPreviewLocalService owner, TestAccountService service) {
        this.store = store; this.policy = policy; this.owner = owner; this.service = service;
    }
    /** The roster's progress for the owner in DEV; null for everyone and everywhere else, so the page offers nothing. */
    public Status statusFor(AccountPrincipal actor) {
        return policy.cloud() && actor != null && actor.isEnabled() && owner.allowed(actor) ? status() : null;
    }

    /** Creates the next accounts that do not exist yet, each in its own transaction, and returns the progress. */
    public Status next(AccountPrincipal actor) {
        policy.requireCloud();
        AccountPrincipal.requireSignedIn(actor, "Sign in to continue.");
        if (!owner.allowed(actor)) {
            EventLog.refused(EventLog.EVENT, "test-roster.create", "TEST_ROSTER_OWNER_ONLY").addKeyValue("account.id", actor.accountId())
                    .log("Test roster creation refused for account {}: only the owner creates it", actor.accountId());
            throw new AccountFailure(403, "TEST_ROSTER_OWNER_ONLY", "Only the owner can create the test roster.");
        }
        var existing = store.registeredIds();
        var batch = TestAccountRoster.entries().stream().filter(entry -> !existing.contains(entry.accountId())).limit(BATCH).toList();
        // Each account in its own transaction (the service's admitRosterEntry opens it).
        batch.forEach(entry -> service.admitRosterEntry(actor, entry));
        var progress = status();
        EventLog.success(EventLog.EVENT, "test-roster.create").addKeyValue("account.id", actor.accountId()).addKeyValue("count", batch.size())
                .addKeyValue("ready", progress.ready()).addKeyValue("total", progress.total())
                .log("{} created {} DEV test roster account(s) in this batch; {} of {} ready, {} deleted, {} still to create",
                    EventLog.actor("Owner", actor.accountId()), batch.size(), progress.ready(), progress.total(), progress.deleted(), progress.remaining());
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
            AccountPrincipal.requireSignedIn(actor, "Sign in to continue.");
            service.requireOwner(actor);
            if (request == null || !"RESTORE".equals(request.confirm()))
                throw new AccountFailure(422, "ACCOUNT_CONFIRMATION_REQUIRED", "Type the required confirmation word.");
        } catch (AccountFailure refused) {
            EventLog.refused(EventLog.EVENT, "test-account.restore", refused.code()).addKeyValue("account.id", actor == null ? null : actor.accountId())
                    .log("Restore of deleted roster accounts by {} refused: {}", EventLog.actor("Account", actor == null ? null : actor.accountId()), refused.code());
            throw refused;
        }
        var deleted = store.deletedRosterIds();
        int restored = 0;
        for (var entry : TestAccountRoster.entries()) {
            if (!deleted.contains(entry.accountId())) continue;
            // Each account in its own transaction (the service's restoreRosterEntry opens it).
            if (!service.restoreRosterEntry(actor, entry)) continue;
            restored++;
            EventLog.success(EventLog.EVENT, "test-account.restore").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", entry.accountId())
                    .log("Roster test account {} restored to its scenario by account {}", entry.accountId(), actor.accountId());
        }
        if (restored == 0) EventLog.success(EventLog.EVENT, "test-account.restore").addKeyValue("account.id", actor.accountId()).addKeyValue("count", 0)
                .log("{} ran the restore of deleted roster accounts: nothing to restore", EventLog.actor("Owner", actor.accountId()));
        return new Restored(restored);
    }


    /** The roster's status, read on every allowance read: one store row per owner-created roster account (has a plan, changed since its scenario start). */
    /** DLV-1016: this rule was the store's. A roster account counts as changed when it was never reset, or its registry row, purchases, plans or staff access were written after its last reset. */
    public static boolean changed(TestAccountRegistryRows.RosterFacts row) {
        var since = row.lastResetAt(); var written = row.latestWriteAt();
        return since == null || row.lastChangedAt().isAfter(since) || written != null && written.isAfter(since);
    }
    private Status status() {
        var roster = new HashSet<UUID>();
        for (var entry : TestAccountRoster.entries()) roster.add(entry.accountId());
        int ready = 0, deleted = 0, withoutPlan = 0, changed = 0;
        for (var row : store.rosterFacts()) {
            UUID id = row.accountId();
            if (!roster.contains(id)) continue;
            if ("deleted".equals(row.creationState())) { deleted++; continue; }
            ready++;
            boolean planMissing = needsPlan(id) && !row.hasPlan();
            if (planMissing) withoutPlan++;
            if (planMissing || changed(row)) changed++;
        }
        return new Status(roster.size(), ready, deleted, withoutPlan, changed);
    }

    /** A roster learner whose scenario includes a study plan. */
    static boolean needsPlan(UUID id) {
        return TestAccountRoster.entries().stream().filter(entry -> entry.accountId().equals(id))
                .anyMatch(entry -> TestAccountScenario.of(Integer.parseInt(entry.username().substring(7))).planKind() != TestAccountScenario.PlanKind.NONE);
    }
}
