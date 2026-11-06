package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.CloudIdentities;
import com.lookahead.domain.cloud.CognitoSignInAccounts;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.List;
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
 * DLV-969 S36: the pool is told once per sign-in (Cognito user) linked to the account, oldest first; any failure is one
 * 503 and a retry repeats every call. The identity rows stay with the deleted account, so a sign-in the pool has not yet
 * removed still finds a deleted account (no sign-in, never a new waiting account) and a retry still knows every user.
 */
@Service
public class AccountRemovalService {
    public record Confirmation(String confirmation) {}
    public record Result(UUID accountId, String state) {}
    /** {@code usernames} (DLV-969 S36): every sign-in pool user of the account, oldest first; the pool is told once per user. */
    private record Target(String issuer, String subject, String username, boolean admitted, boolean author, String state, List<String> usernames) {}
    /** {@code changed}: the database row changed now; false when an earlier call already made the change. */
    private record Changed(Target target, boolean changed) {}
    static final String POOL_UNAVAILABLE = "SIGN_IN_PROVIDER_UNAVAILABLE";
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
        try { return disableChecked(actor, id, request); }
        catch (AccountFailure refused) { throw refusal("disable", actor, id, refused); }
    }

    public Result enable(AccountPrincipal actor, UUID id) {
        try { return enableChecked(actor, id); }
        catch (AccountFailure refused) { throw refusal("enable", actor, id, refused); }
    }

    public Result remove(AccountPrincipal actor, UUID id, Confirmation request) {
        try { return removeChecked(actor, id, request); }
        catch (AccountFailure refused) { throw refusal("delete", actor, id, refused); }
    }

    private Result disableChecked(AccountPrincipal actor, UUID id, Confirmation request) {
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
        pool("disable", actor, id, target, () -> { for (String username : target.target().usernames()) signIns.disable(username); });
        done("disable", actor, id, target, "Owner disabled account {}: no sign-in at all until Enable; its sign-ins ended and the sign-in pool confirmed for {} pool user(s)",
            "Account {} was already disabled; the sign-in pool call was repeated and confirmed for {} pool user(s)");
        return new Result(id, "disabled");
    }

    private Result enableChecked(AccountPrincipal actor, UUID id) {
        var target = change(actor, id, "enable", t -> {
            if ("removed".equals(t.state())) throw removed();
            return jdbc.update("DELETE FROM cloud_account_states WHERE account_id=? AND state='disabled'", id) == 1;
        });
        pool("enable", actor, id, target, () -> { for (String username : target.target().usernames()) signIns.enable(username); });
        done("enable", actor, id, target, "Owner enabled account {}: it can sign in again and the sign-in pool confirmed for {} pool user(s)",
            "Account {} was already enabled; the sign-in pool call was repeated and confirmed for {} pool user(s)");
        return new Result(id, "active");
    }

    private Result removeChecked(AccountPrincipal actor, UUID id, Confirmation request) {
        staff.requireOwner(actor);
        confirm(request, "DELETE");
        var target = change(actor, id, "remove", t -> {
            if ("removed".equals(t.state())) return false;
            if (!t.admitted()) {
                // DLV-971: a waiting row has no learning data and no permanent record to keep; it goes the way an expired
                // request goes, so the same person signing in again appears as a new request.
                jdbc.update("DELETE FROM cloud_account_states WHERE account_id=?", id);
                revokeSignIns(id);
                com.lookahead.domain.cloud.CloudAdmissionCleanup.deleteWaiting(jdbc, id);
                return true;
            }
            jdbc.update("""
                INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)
                ON CONFLICT (account_id) DO UPDATE SET state='removed',changed_at=clock_timestamp(),changed_by=EXCLUDED.changed_by
                """, id, actor.accountId());
            revokeSignIns(id);
            erase(actor.accountId(), id);
            return true;
        });
        pool("delete", actor, id, target, () -> { for (String username : target.target().usernames()) signIns.remove(username); });
        done("delete", actor, id, target, "Owner deleted account {}: learning data erased, sign-ins ended and {} sign-in pool user(s) deleted",
            "Account {} was already deleted; the sign-in pool call was repeated and confirmed for {} pool user(s)");
        return new Result(id, "removed");
    }

    /**
     * The database change has committed; a pool failure is logged here and is not a refusal. DLV-969 S36: the account
     * may have several pool users (Google and password with one Gmail); any failure stops the loop, and a retry of the
     * same action repeats the call for every user, so the count in the line says how many a retry must cover.
     */
    private void pool(String action, AccountPrincipal actor, UUID id, Changed target, Runnable call) {
        try { call.run(); }
        catch (AccountFailure unconfirmed) {
            Events.failed(Events.EVENT, "account." + action, unconfirmed.code()).addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("count", target.target().usernames().size())
                .log("Account {}: {} saved in the database, but the sign-in pool call failed for one of its {} pool user(s); a retry of the same action repeats only the pool calls",
                    id, action, target.target().usernames().size());
            throw unconfirmed;
        }
    }

    private static void done(String action, AccountPrincipal actor, UUID id, Changed target, String now, String again) {
        int users = target.target().usernames().size();
        Events.success(Events.EVENT, "account." + action).addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
            .addKeyValue("repeated", !target.changed()).addKeyValue("count", users).log(target.changed() ? now : again, id, users);
    }

    private static AccountFailure refusal(String action, AccountPrincipal actor, UUID id, AccountFailure refused) {
        if (!POOL_UNAVAILABLE.equals(refused.code()))
            Events.refused(Events.EVENT, "account." + action, refused.code()).addKeyValue("account.id", actor == null ? null : actor.accountId())
                .addKeyValue("target.id", id).log("{} of account {} refused: {}", Character.toUpperCase(action.charAt(0)) + action.substring(1), id, refused.code());
        return refused;
    }

    /** Checks, then one transaction under the account's row lock; an event is recorded only when something changed. */
    private Changed change(AccountPrincipal actor, UUID id, String action, java.util.function.Function<Target, Boolean> apply) {
        staff.requireOwner(actor);
        if (id == null || id.equals(actor.accountId())) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
        if (signIns == null) throw notSignIn();
        return transactions.execute(status -> {
            var rows = jdbc.query("""
                SELECT a.issuer,a.subject,a.username,a.admitted,a.author_access,s.state
                FROM cloud_accounts a LEFT JOIN cloud_account_states s USING(account_id) WHERE a.account_id=? FOR UPDATE OF a
                """, (r, i) -> new Target(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4), r.getBoolean(5), r.getString(6), List.of()), id);
            // DLV-971 (owner, 2026-11-06): a waiting row (not admitted) can be disabled or deleted too: Reject on Waiting for access.
            if (rows.isEmpty()) throw notSignIn();
            var row = rows.getFirst();
            // DLV-969 S36: the state again, in a statement of its own under the lock: the locking statement's join keeps
            // its starting snapshot, so a Disable, Delete or merge committed while it waited would go unseen (and its
            // insert would then fail as a duplicate, a 503).
            String state = jdbc.queryForList("SELECT state FROM cloud_account_states WHERE account_id=?", String.class, id).stream().findFirst().orElse(null);
            var target = new Target(row.issuer(), row.subject(), row.username(), row.admitted(), row.author(), state,
                CloudIdentities.usernames(jdbc, id, row.username()));
            if (target.author()) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "An author account cannot be changed here.");
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)", Boolean.class, id)))
                throw new AccountFailure(403, "TEST_ACCOUNT_ROUTE_REQUIRED", "Use the Test accounts page for managed accounts.");
            boolean changed = apply.apply(target);
            if (changed)
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), id, target.issuer(), target.subject(), actor.accountId(), action);
            return new Changed(target, changed);
        });
    }

    /** Ends every sign-in and pending sign-in choice of the account; in the caller's transaction (also the S36 merge). */
    public void revokeSignIns(UUID id) {
        jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=?", id);
        jdbc.update("UPDATE cloud_sign_in_challenges SET cancelled=true WHERE account_id=?", id);
    }

    /**
     * Roles, grants, plans, receipts and profile settings; purchase history stays for the audit. The one deletion of an
     * account's data: Delete here and the DLV-969 S36 merge (the account not kept) both use it, inside the caller's
     * transaction and after the caller has locked the account row.
     */
    public void erase(UUID actor, UUID id) {
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)", rs -> {}, actor.toString());
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
        return new AccountFailure(404, "SIGN_IN_ACCOUNT_NOT_FOUND", "No DEV sign-in account with that id can be disabled or deleted.");
    }
    private static AccountFailure removed() {
        return new AccountFailure(409, "ACCOUNT_REMOVED", "This account was deleted.");
    }
}
