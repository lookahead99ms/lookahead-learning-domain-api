package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.CognitoSignInAccounts;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
    public record Target(String issuer, String subject, String username, boolean admitted, boolean author, String state, List<String> usernames) {}
    /** {@code changed}: the database row changed now; false when an earlier call already made the change. */
    public record Changed(Target target, boolean changed) {}
    static final String POOL_UNAVAILABLE = "SIGN_IN_PROVIDER_UNAVAILABLE";
    private final AccountRemovalStore store;
    private final StaffAccessService staff;
    private final CognitoSignInAccounts signIns;

    /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
    @org.springframework.beans.factory.annotation.Autowired
    public AccountRemovalService(AccountRemovalStore store, StaffAccessService staff, ObjectProvider<CognitoSignInAccounts> signIns) {
        this.store = store; this.staff = staff; this.signIns = signIns.getIfAvailable();
    }

    /** Directory flags: which of the three actions apply to this account for the owner. */
    public record Options(String signIn, boolean canDisable, boolean canEnable, boolean canRemove) {
        static final Options NONE = new Options(null, false, false, false);
    }
    public Options options(UUID self, UUID id, boolean test) {
        if (signIns == null || test || id.equals(self)) return Options.NONE;
        var row = store.options(id);
        if (row == null || row.author() || !row.admitted()) return Options.NONE;
        String state = row.state();
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
        if (id == null || id.equals(actor.accountId())) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
        if (signIns == null) throw notSignIn();
        var target = store.disable(actor.accountId(), id);
        pool("disable", actor, id, target, () -> { for (String username : target.target().usernames()) signIns.disable(username); });
        done("disable", actor, id, target, "{} disabled account {}: no sign-in at all until Enable; its sign-ins ended and the sign-in pool confirmed for {} pool user(s)",
            "Account {} was already disabled; {} repeated the sign-in pool call, confirmed for {} pool user(s)");
        return new Result(id, "disabled");
    }

    private Result enableChecked(AccountPrincipal actor, UUID id) {
        staff.requireOwner(actor);
        if (id == null || id.equals(actor.accountId())) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
        if (signIns == null) throw notSignIn();
        var target = store.enable(actor.accountId(), id);
        pool("enable", actor, id, target, () -> { for (String username : target.target().usernames()) signIns.enable(username); });
        done("enable", actor, id, target, "{} enabled account {}: it can sign in again and the sign-in pool confirmed for {} pool user(s)",
            "Account {} was already enabled; {} repeated the sign-in pool call, confirmed for {} pool user(s)");
        return new Result(id, "active");
    }

    private Result removeChecked(AccountPrincipal actor, UUID id, Confirmation request) {
        staff.requireOwner(actor);
        confirm(request, "DELETE");
        if (id == null || id.equals(actor.accountId())) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
        if (signIns == null) throw notSignIn();
        var target = store.remove(actor.accountId(), id);
        pool("delete", actor, id, target, () -> { for (String username : target.target().usernames()) signIns.remove(username); });
        done("delete", actor, id, target, "{} deleted account {}: learning data erased, sign-ins ended and {} sign-in pool user(s) deleted",
            "Account {} was already deleted; {} repeated the sign-in pool call, confirmed for {} pool user(s)");
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
                .log("Account {}: {} by {} saved in the database, but the sign-in pool call failed for one of its {} pool user(s); a retry of the same action repeats only the pool calls",
                    id, action, Events.actor("Owner", actor.accountId()), target.target().usernames().size());
            throw unconfirmed;
        }
    }

    /** {@code now} is "{actor} {did} account {id}: … {n} …"; {@code again} is "Account {id} was already …; {actor} … {n} …" (plain logs: the actor in the sentence). */
    private static void done(String action, AccountPrincipal actor, UUID id, Changed target, String now, String again) {
        int users = target.target().usernames().size();
        String owner = Events.actor("Owner", actor.accountId());
        var line = Events.success(Events.EVENT, "account." + action).addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
            .addKeyValue("repeated", !target.changed()).addKeyValue("count", users);
        if (target.changed()) line.log(now, owner, id, users); else line.log(again, id, owner, users);
    }

    private static AccountFailure refusal(String action, AccountPrincipal actor, UUID id, AccountFailure refused) {
        if (!POOL_UNAVAILABLE.equals(refused.code()))
            Events.refused(Events.EVENT, "account." + action, refused.code()).addKeyValue("account.id", actor == null ? null : actor.accountId())
                .addKeyValue("target.id", id).log("{} of account {} refused for {}: {}", Character.toUpperCase(action.charAt(0)) + action.substring(1), id,
                    Events.actor("Account", actor == null ? null : actor.accountId()), refused.code());
        return refused;
    }

    /** Ends every sign-in and pending sign-in choice of the account; in the caller's transaction (also the S36 merge). */
    public void revokeSignIns(UUID id) { store.revokeSignIns(id); }

    /**
     * Roles, grants, plans, receipts and profile settings; purchase history stays for the audit. The one deletion of an
     * account's data: Delete here and the DLV-969 S36 merge (the account not kept) both use it, inside the caller's
     * transaction and after the caller has locked the account row.
     */
    public void erase(UUID actor, UUID id) { store.erase(actor, id); }

    private static void confirm(Confirmation request, String word) {
        if (request == null || !word.equals(request.confirmation()))
            throw new AccountFailure(422, "ACCOUNT_CONFIRMATION_REQUIRED", "Type the required confirmation word.");
    }
    static AccountFailure notSignIn() {
        return new AccountFailure(404, "SIGN_IN_ACCOUNT_NOT_FOUND", "No DEV sign-in account with that id can be disabled or deleted.");
    }
    static AccountFailure removed() {
        return new AccountFailure(409, "ACCOUNT_REMOVED", "This account was deleted.");
    }
}
