package com.lookahead.domain.access;

import com.lookahead.domain.cloud.CognitoSignInAccounts;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DLV-969 (owner decision 2026-11-03): the owner disables, enables and deletes DEV sign-in accounts from People and
 * access. Disable is the strongest: no sign-in at all until Enable. Delete erases the account's learning data and its
 * sign-in; the same person signing in again is a new waiting account. Never the owner, an author account or a test
 * account. The database change commits first, then the sign-in pool is told; a retry repeats only the pool call.
 */
@Service
public class AccountRemovalService {
    public record Confirmation(String confirmation) {}
    public record Result(UUID accountId, String state) {}
    private record Target(String issuer, String subject, String username, boolean admitted, boolean author, String state) {}
    private final JdbcTemplate jdbc;
    private final StaffAccessService staff;
    private final CognitoSignInAccounts signIns;
    private final TransactionTemplate transactions;

    public AccountRemovalService(JdbcTemplate jdbc, StaffAccessService staff, ObjectProvider<CognitoSignInAccounts> signIns,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.staff = staff; this.signIns = signIns.getIfAvailable(); this.transactions = new TransactionTemplate(manager);
    }

    /** Directory flags: which of the three actions apply to this account for the owner. */
    public record Options(String signIn, boolean canDisable, boolean canEnable, boolean canRemove) {
        static final Options NONE = new Options(null, false, false, false);
    }
    public Options options(UUID self, UUID id, boolean test) {
        if (signIns == null || test || id.equals(self)) return Options.NONE;
        var rows = jdbc.query("SELECT a.author_access,a.admitted,s.state FROM cloud_accounts a LEFT JOIN cloud_account_states s USING(account_id) WHERE a.account_id=?",
            (r, i) -> new Object[]{r.getBoolean(1), r.getBoolean(2), r.getString(3)}, id);
        if (rows.isEmpty() || (Boolean) rows.getFirst()[0] || !(Boolean) rows.getFirst()[1]) return Options.NONE;
        String state = (String) rows.getFirst()[2];
        if ("removed".equals(state)) return Options.NONE;
        boolean disabled = "disabled".equals(state);
        return new Options(disabled ? "disabled" : "active", !disabled, disabled, true);
    }

    public Result disable(AccountPrincipal actor, UUID id, Confirmation request) {
        staff.requireOwner(actor);
        confirm(request, "DISABLE");
        var target = change(actor, id, "disable", t -> {
            if ("removed".equals(t.state())) throw removed();
            if (t.state() == null) {
                jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)", id, actor.accountId());
                revokeSignIns(id);
                return true;
            }
            return false;
        });
        signIns.disable(target.username());
        return new Result(id, "disabled");
    }

    public Result enable(AccountPrincipal actor, UUID id) {
        var target = change(actor, id, "enable", t -> {
            if ("removed".equals(t.state())) throw removed();
            return jdbc.update("DELETE FROM cloud_account_states WHERE account_id=? AND state='disabled'", id) == 1;
        });
        signIns.enable(target.username());
        return new Result(id, "active");
    }

    public Result remove(AccountPrincipal actor, UUID id, Confirmation request) {
        staff.requireOwner(actor);
        confirm(request, "DELETE");
        var target = change(actor, id, "remove", t -> {
            if ("removed".equals(t.state())) return false;
            jdbc.update("""
                INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)
                ON CONFLICT (account_id) DO UPDATE SET state='removed',changed_at=clock_timestamp(),changed_by=EXCLUDED.changed_by
                """, id, actor.accountId());
            revokeSignIns(id);
            erase(actor, id);
            return true;
        });
        signIns.remove(target.username());
        return new Result(id, "removed");
    }

    /** Checks, then one transaction under the account's row lock; an event is recorded only when something changed. */
    private Target change(AccountPrincipal actor, UUID id, String action, java.util.function.Function<Target, Boolean> apply) {
        staff.requireOwner(actor);
        if (id == null || id.equals(actor.accountId())) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
        if (signIns == null) throw notSignIn();
        return transactions.execute(status -> {
            var rows = jdbc.query("""
                SELECT a.issuer,a.subject,a.username,a.admitted,a.author_access,s.state
                FROM cloud_accounts a LEFT JOIN cloud_account_states s USING(account_id) WHERE a.account_id=? FOR UPDATE OF a
                """, (r, i) -> new Target(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4), r.getBoolean(5), r.getString(6)), id);
            if (rows.isEmpty() || !rows.getFirst().admitted()) throw notSignIn();
            var target = rows.getFirst();
            if (target.author()) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "An author account cannot be changed here.");
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)", Boolean.class, id)))
                throw new AccountFailure(403, "TEST_ACCOUNT_ROUTE_REQUIRED", "Use the Test accounts page for managed accounts.");
            if (apply.apply(target))
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), id, target.issuer(), target.subject(), actor.accountId(), action);
            return target;
        });
    }

    private void revokeSignIns(UUID id) {
        jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=?", id);
        jdbc.update("UPDATE cloud_sign_in_challenges SET cancelled=true WHERE account_id=?", id);
    }

    /** Roles, grants, plans, receipts and profile settings; purchase history stays for the audit. */
    private void erase(AccountPrincipal actor, UUID id) {
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)", rs -> {}, actor.accountId().toString());
        jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=? AND (roles<>'[]'::jsonb OR topic_grants<>'[]'::jsonb OR full_curriculum)", id);
        jdbc.update("UPDATE membership_purchases SET superseded_at=COALESCE(superseded_at,now()),updated_at=now() WHERE account_id=? AND superseded_at IS NULL", id);
        // The free-plan allowance rows stay: the runtime may not delete them, and a returning person has a new account id.
        for (String table : new String[]{"account_grants", "mutation_receipts", "plans", "support_receipts",
                "cloud_account_preferences", "cloud_account_profile_links"})
            jdbc.update("DELETE FROM " + table + " WHERE account_id=?", id);
    }

    private static void confirm(Confirmation request, String word) {
        if (request == null || !word.equals(request.confirmation()))
            throw new AccountFailure(422, "ACCOUNT_CONFIRMATION_REQUIRED", "Type the required confirmation word.");
    }
    private static AccountFailure notSignIn() {
        return new AccountFailure(404, "SIGN_IN_ACCOUNT_NOT_FOUND", "Only admitted DEV sign-in accounts can be disabled or deleted.");
    }
    private static AccountFailure removed() {
        return new AccountFailure(409, "ACCOUNT_REMOVED", "This account was deleted.");
    }
}
