package com.lookahead.domain.service.access;

import com.lookahead.domain.client.SignInPoolClient;
import com.lookahead.domain.db.store.access.AccountRemoval;
import com.lookahead.domain.dto.AccountRemovalConfirmation;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.AccountRemovalModels.*;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * DLV-969 (owner decision 2026-11-03): the owner disables, enables and deletes DEV sign-in accounts
 * from People and access. Disable is the strongest: no sign-in at all until Enable. Delete erases
 * the account's learning data and its sign-in; the same person signing in again is a new waiting
 * account. Never the owner, an author account or a test account. The database change commits first,
 * then the sign-in pool is told; a retry repeats only the pool call. DLV-969 S36: the pool is told
 * once per sign-in (Cognito user) linked to the account, oldest first; any failure is one 503 and a
 * retry repeats every call. The identity rows stay with the deleted account, so a sign-in the pool
 * has not yet removed still finds a deleted account (no sign-in, never a new waiting account) and a
 * retry still knows every user.
 */
@Service
public class AccountRemovalService {
  public record Result(UUID accountId, String state) {}

  static final String POOL_UNAVAILABLE = "SIGN_IN_PROVIDER_UNAVAILABLE";
  private final AccountRemoval store;
  private final StaffAccessService staff;

  /**
   * The sign-in pool; null in Local, which has none: the three actions refuse there before anything
   * is stored.
   */
  private final SignInPoolClient signIns;

  /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
  @Autowired
  public AccountRemovalService(
      AccountRemoval store, StaffAccessService staff, ObjectProvider<SignInPoolClient> signIns) {
    this.store = store;
    this.staff = staff;
    this.signIns = signIns.getIfAvailable();
  }

  /** Directory flags: which of the three actions apply to this account for the owner. */
  public record Options(String signIn, boolean canDisable, boolean canEnable, boolean canRemove) {
    static final Options NONE = new Options(null, false, false, false);
  }

  /**
   * Package-private: the directory service of this package asks, after its own check of the reader.
   */
  Options options(UUID self, UUID id, boolean test) {
    if (signIns == null || test || id.equals(self)) return Options.NONE;
    var row = store.options(id);
    if (row == null || row.author() || !row.admitted()) return Options.NONE;
    String state = row.state();
    if ("removed".equals(state)) return Options.NONE;
    boolean disabled = "disabled".equals(state);
    return new Options(disabled ? "disabled" : "active", !disabled, disabled, true);
  }

  public Result disable(AccountPrincipal actor, UUID id, AccountRemovalConfirmation request) {
    try {
      return disableChecked(actor, id, request);
    } catch (AccountFailure refused) {
      throw refusal("disable", actor, id, refused);
    }
  }

  public Result enable(AccountPrincipal actor, UUID id) {
    try {
      return enableChecked(actor, id);
    } catch (AccountFailure refused) {
      throw refusal("enable", actor, id, refused);
    }
  }

  public Result remove(AccountPrincipal actor, UUID id, AccountRemovalConfirmation request) {
    try {
      return removeChecked(actor, id, request);
    } catch (AccountFailure refused) {
      throw refusal("delete", actor, id, refused);
    }
  }

  // DLV-1016: the next four were inside the store. The store answers the account as it read it and
  // makes the write on that
  // reading; who may not be changed here, "already done" and "deleted is final" are decided here,
  // in the store's former order.
  private AccountRemoval.Subject subject(UUID id) {
    var subject = store.subject(id);
    if (subject == null) throw notSignIn();
    if (subject.author())
      throw new AccountFailure(
          403, "OWNER_ACCOUNT_PROTECTED", "An author account cannot be changed here.");
    if (subject.managedTest())
      throw new AccountFailure(
          403, "TEST_ACCOUNT_ROUTE_REQUIRED", "Use the Test accounts page for managed accounts.");
    return subject;
  }

  /**
   * The stored step of Disable, without the owner's checks and the pool calls of {@link #disable}:
   * what changed, for those. Package-private: only the three actions call the stored steps.
   */
  Changed disableAccount(UUID actor, UUID id) {
    var subject = subject(id);
    var target = subject.target();
    if (subject.removed()) throw removed();
    if (subject.disabled()) return new Changed(target, false);
    stored(() -> store.disable(actor, subject));
    return new Changed(target, true);
  }

  /** The stored step of Enable. */
  Changed enableAccount(UUID actor, UUID id) {
    var subject = subject(id);
    var target = subject.target();
    if (subject.removed()) throw removed();
    if (subject.active()) return new Changed(target, false);
    stored(() -> store.enable(actor, subject));
    return new Changed(target, true);
  }

  /**
   * The stored step of Delete; a retry on a deleted account only finishes an interrupted erasure.
   */
  Changed removeAccount(UUID actor, UUID id) {
    var subject = subject(id);
    var target = subject.target();
    if (subject.removed()) {
      store.finishErasure(actor, id);
      return new Changed(target, false);
    }
    stored(() -> store.remove(actor, subject));
    return new Changed(target, true);
  }

  /**
   * One stored step of the three actions. DLV-1016: when the store did not make an admitted
   * account's state change ({@link AccountRemoval.NotChanged}), the line for a refusal by the
   * control store and the 503 are written here; they were the store's, with the same words, at the
   * same point (right after the store's calls, before anything else).
   */
  private static void stored(Runnable step) {
    try {
      step.run();
    } catch (AccountRemoval.NotChanged refused) {
      if (refused.reason() == AccountRemoval.NotChanged.Reason.ACCOUNT_CONTROL_REFUSED)
        EventLog.failed(EventLog.EVENT, "account." + refused.action(), "ACCOUNT_CONTROL_REFUSED")
            .addKeyValue("target.id", refused.target())
            .addKeyValue("error.type", EventLog.types(refused.getCause()))
            .log(
                "Account {} {} refused by the control store ({})",
                refused.target(),
                refused.action(),
                refused.getCause().getMessage());
      throw new AccountFailure(
          503,
          refused.reason().name(),
          "The account state could not be changed. Retry the same action.");
    }
  }

  private Result disableChecked(
      AccountPrincipal actor, UUID id, AccountRemovalConfirmation request) {
    staff.requireOwner(actor);
    confirm(request, "DISABLE");
    if (id == null || id.equals(actor.accountId()))
      throw new AccountFailure(
          403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
    if (signIns == null) throw notSignIn();
    var target = disableAccount(actor.accountId(), id);
    pool(
        "disable",
        actor,
        id,
        target,
        () -> {
          for (String username : target.target().usernames()) signIns.disable(username);
        });
    done(
        "disable",
        actor,
        id,
        target,
        "{} disabled account {}: no sign-in at all until Enable; its sign-ins ended and the sign-in"
            + " pool confirmed for {} pool user(s)",
        "Account {} was already disabled; {} repeated the sign-in pool call, confirmed for {} pool"
            + " user(s)");
    return new Result(id, "disabled");
  }

  private Result enableChecked(AccountPrincipal actor, UUID id) {
    staff.requireOwner(actor);
    if (id == null || id.equals(actor.accountId()))
      throw new AccountFailure(
          403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
    if (signIns == null) throw notSignIn();
    var target = enableAccount(actor.accountId(), id);
    pool(
        "enable",
        actor,
        id,
        target,
        () -> {
          for (String username : target.target().usernames()) signIns.enable(username);
        });
    done(
        "enable",
        actor,
        id,
        target,
        "{} enabled account {}: it can sign in again and the sign-in pool confirmed for {} pool"
            + " user(s)",
        "Account {} was already enabled; {} repeated the sign-in pool call, confirmed for {} pool"
            + " user(s)");
    return new Result(id, "active");
  }

  private Result removeChecked(
      AccountPrincipal actor, UUID id, AccountRemovalConfirmation request) {
    staff.requireOwner(actor);
    confirm(request, "DELETE");
    if (id == null || id.equals(actor.accountId()))
      throw new AccountFailure(
          403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
    if (signIns == null) throw notSignIn();
    var target = removeAccount(actor.accountId(), id);
    pool(
        "delete",
        actor,
        id,
        target,
        () -> {
          for (String username : target.target().usernames()) signIns.remove(username);
        });
    done(
        "delete",
        actor,
        id,
        target,
        "{} deleted account {}: learning data erased, sign-ins ended and {} sign-in pool user(s)"
            + " deleted",
        "Account {} was already deleted; {} repeated the sign-in pool call, confirmed for {} pool"
            + " user(s)");
    return new Result(id, "removed");
  }

  /**
   * The database change has committed; a pool failure is logged here and is not a refusal. DLV-969
   * S36: the account may have several pool users (Google and password with one Gmail); any failure
   * stops the loop, and a retry of the same action repeats the call for every user, so the count in
   * the line says how many a retry must cover.
   */
  private void pool(String action, AccountPrincipal actor, UUID id, Changed target, Runnable call) {
    try {
      call.run();
    } catch (AccountFailure unconfirmed) {
      EventLog.failed(EventLog.EVENT, "account." + action, unconfirmed.code())
          .addKeyValue("account.id", actor.accountId())
          .addKeyValue("target.id", id)
          .addKeyValue("count", target.target().usernames().size())
          .log(
              "Account {}: {} by {} saved in the database, but the sign-in pool call failed for one"
                  + " of its {} pool user(s); a retry of the same action repeats only the pool"
                  + " calls",
              id,
              action,
              EventLog.actor("Owner", actor.accountId()),
              target.target().usernames().size());
      throw unconfirmed;
    }
  }

  /**
   * {@code now} is "{actor} {did} account {id}: … {n} …"; {@code again} is "Account {id} was
   * already …; {actor} … {n} …" (plain logs: the actor in the sentence).
   */
  private static void done(
      String action, AccountPrincipal actor, UUID id, Changed target, String now, String again) {
    int users = target.target().usernames().size();
    String owner = EventLog.actor("Owner", actor.accountId());
    var line =
        EventLog.success(EventLog.EVENT, "account." + action)
            .addKeyValue("account.id", actor.accountId())
            .addKeyValue("target.id", id)
            .addKeyValue("repeated", !target.changed())
            .addKeyValue("count", users);
    if (target.changed()) line.log(now, owner, id, users);
    else line.log(again, id, owner, users);
  }

  private static AccountFailure refusal(
      String action, AccountPrincipal actor, UUID id, AccountFailure refused) {
    if (!POOL_UNAVAILABLE.equals(refused.code()))
      EventLog.refused(EventLog.EVENT, "account." + action, refused.code())
          .addKeyValue("account.id", actor == null ? null : actor.accountId())
          .addKeyValue("target.id", id)
          .log(
              "{} of account {} refused for {}: {}",
              Character.toUpperCase(action.charAt(0)) + action.substring(1),
              id,
              EventLog.actor("Account", actor == null ? null : actor.accountId()),
              refused.code());
    return refused;
  }

  private static void confirm(AccountRemovalConfirmation request, String word) {
    if (request == null || !word.equals(request.confirmation()))
      throw new AccountFailure(
          422, "ACCOUNT_CONFIRMATION_REQUIRED", "Type the required confirmation word.");
  }

  // The two refusals of a removal (DLV-1016: they were static methods of the removal model class;
  // only this service builds them).
  private static AccountFailure notSignIn() {
    return new AccountFailure(
        404,
        "SIGN_IN_ACCOUNT_NOT_FOUND",
        "No DEV sign-in account with that id can be disabled or deleted.");
  }

  private static AccountFailure removed() {
    return new AccountFailure(409, "ACCOUNT_REMOVED", "This account was deleted.");
  }
}
