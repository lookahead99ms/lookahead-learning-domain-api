package com.lookahead.domain.service.testaccounts;

import com.lookahead.domain.client.TestAccountSignInClient;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.testaccounts.TestAccount;
import com.lookahead.domain.dto.TestAccountCreate;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.TestAccountRoster;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.util.TestAccountDeletionPolicy;
import com.lookahead.domain.util.TestAccountTemplates;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * A pending reservation precedes Identity; only a completed Domain record may be activated. Cloud
 * mode (DLV-969 S35, DEV): no identity provider and no password. The completing transaction writes
 * the admitted cloud_accounts row on the test issuer and its 'admit' event, then the chosen
 * scenario; staff use the account through a test session. Delete keeps the admitted row (V17); the
 * mutation key and the lifetime allowance behave as in Local.
 */
@Service
public class TestAccountCreationService {
  /**
   * A reservation that never finished holds one place of the creator's allowance for this long
   * (DLV-1016: the number was in the store).
   */
  public static final Duration HOLD = Duration.ofMinutes(15);

  public record Created(UUID accountId, String username, boolean active) {}

  /**
   * {@code roster}: the DEV roster's progress, for the owner only; {@code scenarios}: what this
   * creator may choose.
   */
  public record Allowance(
      int limit,
      int used,
      int remaining,
      TestAccountDevRosterService.Status roster,
      List<TestAccountTemplates.Template> scenarios) {
    public Allowance(int limit, int used, int remaining) {
      this(limit, used, remaining, null, null);
    }

    public Allowance with(TestAccountDevRosterService.Status status) {
      return new Allowance(limit, used, remaining, status, scenarios);
    }
  }

  private record Reservation(UUID accountId, String username, String state) {}

  private final TestAccount store;
  private final TestAccountModeService policy;
  private final StaffAccess staff;
  private final AuthorPreviewLocalService owner;

  /**
   * DLV-1016: the one client of this environment (the Local Identity operator, or none to call in
   * DEV); it was chosen here by {@code policy.cloud()}.
   */
  private final TestAccountSignInClient identity;

  private TestAccountService accounts;

  @Autowired
  public TestAccountCreationService(
      TestAccount store,
      TestAccountModeService policy,
      StaffAccess staff,
      AuthorPreviewLocalService owner,
      TestAccountSignInClient identity) {
    this.store = store;
    this.policy = policy;
    this.staff = staff;
    this.owner = owner;
    this.identity = identity;
  }

  /** Writes the scenarios and the DEV admitted row (DLV-969 S35). */
  @Autowired
  public void configureAccounts(TestAccountService accounts) {
    this.accounts = accounts;
  }

  private int limit(AccountPrincipal actor) {
    policy.requireEnabled();
    AccountPrincipal.requireSignedIn(actor, "Sign in to continue.");
    if (owner.allowed(actor)) return Integer.MAX_VALUE;
    var assignment = staff.forAccount(actor.accountId());
    Set<String> roles = assignment == null ? Set.of() : assignment.roles();
    // A dual-role actor gets the lower limit by design.
    if (roles.contains("admin")) return 10;
    if (roles.contains("co-author")) return 20;
    throw new AccountFailure(
        403,
        "TEST_ACCOUNT_CREATION_FORBIDDEN",
        "Only owner, co-author or admin may create learner test accounts.");
  }

  public Allowance allowance(AccountPrincipal actor) {
    int cap = limit(actor);
    int used = store.lifetimeSpend(actor.accountId());
    // DLV-969 (owner, 2026-11-04): learner scenarios only, for every role. DLV-971 (owner,
    // 2026-11-06): there are no
    // other scenarios; staff test accounts no longer exist anywhere.
    return new Allowance(
        cap,
        used,
        cap == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, cap - used),
        null,
        TestAccountTemplates.creatable());
  }

  public Created create(AccountPrincipal actor, TestAccountCreate request) {
    try {
      var created = createChecked(actor, request);
      EventLog.success(EventLog.EVENT, "test-account.create")
          .addKeyValue("account.id", actor.accountId())
          .addKeyValue("target.id", created.accountId())
          .addKeyValue("scenario", request.scenario())
          .log(
              "Test account {} created by account {} (scenario {})",
              created.accountId(),
              actor.accountId(),
              request.scenario() == null ? "none" : request.scenario());
      return created;
    } catch (AccountFailure refused) {
      if (!"TEST_ACCOUNT_LIMIT".equals(refused.code()))
        EventLog.refused(EventLog.EVENT, "test-account.create", refused.code())
            .addKeyValue("account.id", actor == null ? null : actor.accountId())
            .log(
                "Test account creation by {} refused: {}",
                EventLog.actor("Account", actor == null ? null : actor.accountId()),
                refused.code());
      throw refused;
    }
  }

  private Created createChecked(AccountPrincipal actor, TestAccountCreate request) {
    boolean cloud = policy.cloud();
    if (request == null
        || request.key() == null
        || !cloud
            && (request.password() == null
                || request.password().codePointCount(0, request.password().length()) < 15
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72))
      throw new AccountFailure(
          422,
          "INVALID_TEST_ACCOUNT",
          cloud
              ? "Provide a request key."
              : "Provide a request key and a password of 15 to 72 UTF-8 bytes.");
    String scenario = request.scenario();
    // DLV-971 (owner, 2026-11-06): every known scenario is a learner scenario; anything else (the
    // former co-author
    // and admin scenarios included) is simply unknown.
    if (scenario != null && !TestAccountTemplates.known(scenario))
      throw new AccountFailure(422, "UNKNOWN_SCENARIO", "Choose one of the listed scenarios.");
    // Two creates by one creator at once fence on the creator's revision: the loser reads again
    // instead of answering 409.
    var reservation = store.transactRetrying(work -> reserve(work, actor, request.key()));
    if (reservation == null) throw new IllegalStateException("Reservation did not complete");
    if (!"active".equals(reservation.state())) {
      identity.provision(
          reservation.accountId(), reservation.username(), cloud ? null : request.password());
      store.transact(work -> {
        complete(work, actor, reservation, scenario);
        return null;
      });
    }
    identity.activate(reservation.accountId(), reservation.username());
    return new Created(reservation.accountId(), reservation.username(), true);
  }

  private Reservation reserve(TestAccount.Work work, AccountPrincipal actor, UUID key) {
    int cap = limit(actor);
    work.lockSubject(actor.accountId());
    var prior = work.reservation(actor.accountId(), key);
    if (prior != null) {
      if ("deleted".equals(prior.state()))
        throw new AccountFailure(
            409,
            "TEST_ACCOUNT_DELETED",
            "This request already created an account that was deleted.");
      return new Reservation(prior.accountId(), prior.username(), prior.state());
    }
    // A timed-out reservation may hold capacity briefly, but never becomes a lifetime spend.
    int used = work.holdsAndSpend(actor.accountId(), HOLD);
    if (used >= cap) throw limitReached(actor, cap);
    UUID id =
        UUID.nameUUIDFromBytes(
            ("lookahead-created-test:" + actor.accountId() + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    String hex = id.toString().replace("-", "");
    // DEV (DLV-969 S35): an address on the owner's domain, like the roster; it never signs in. 29
    // characters, within the
    // 32 that usernames have always been limited to.
    String username =
        policy.cloud()
            ? "test-" + hex.substring(0, 10) + "@" + TestAccountRoster.CLOUD_DOMAIN
            : "test-" + hex.substring(0, 24);
    work.reserve(actor.accountId(), key, id, username);
    return new Reservation(id, username, "pending");
  }

  private void complete(
      TestAccount.Work work, AccountPrincipal actor, Reservation reserved, String scenario) {
    limit(actor);
    work.lockSubject(actor.accountId());
    String state = work.creationState(actor.accountId(), reserved.accountId());
    if (state == null || "deleted".equals(state))
      throw new AccountFailure(409, "TEST_ACCOUNT_DELETED", "The account is no longer available.");
    if ("active".equals(state)) return;
    int cap = limit(actor);
    int spent = work.lifetimeSpend(actor.accountId());
    if (spent >= cap) throw limitReached(actor, cap);
    work.createSubject(reserved.accountId());
    work.registerCreated(reserved.accountId(), reserved.username(), actor.accountId());
    // DEV: admitted by its creator on the test issuer, so every account feature finds its row
    // (DLV-969 S35).
    if (policy.cloud())
      accounts.admitTestAccount(
          reserved.accountId(), reserved.username(), reserved.username(), actor.accountId());
    if (scenario != null) accounts.applyCreated(reserved.accountId(), scenario, actor.accountId());
    work.activateCreation(actor.accountId(), reserved.accountId());
  }

  /**
   * A lifetime creation limit hit: one {@code lookahead.limit} line (admin 10, co-author 20; the
   * owner has none).
   */
  private static AccountFailure limitReached(AccountPrincipal actor, int cap) {
    EventLog.limit(
            "test-account.create", "TEST_ACCOUNT_LIMIT", "test-account-creations", cap, "lifetime")
        .addKeyValue("account.id", actor.accountId())
        .log(
            "Test account limit hit for account {}: {} creations in its lifetime are allowed and"
                + " all are used",
            actor.accountId(),
            cap);
    return new AccountFailure(
        409, "TEST_ACCOUNT_LIMIT", "Lifetime test-account creation limit reached.");
  }

  public void delete(AccountPrincipal actor, UUID id) {
    try {
      var target = store.transactRetrying(work -> authorizedTarget(work, actor, id));
      if (target == null)
        throw new AccountFailure(404, "TEST_ACCOUNT_NOT_FOUND", "Managed test account not found.");
      identity.disable(id, target.username());
      // Erasure is idempotent: a checkout that slips in between its fence and the commit makes it
      // run again and delete that too.
      store.transactRetrying(work -> {
        erase(work, actor, id);
        return null;
      });
      EventLog.success(EventLog.EVENT, "test-account.delete")
          .addKeyValue("account.id", actor.accountId())
          .addKeyValue("target.id", id)
          .addKeyValue("repeated", "deleted".equals(target.state()))
          .log(
              "deleted".equals(target.state())
                  ? "Test account {} was already deleted; nothing more to do"
                  : "Test account {} deleted by account {}",
              id,
              actor.accountId());
    } catch (AccountFailure refused) {
      EventLog.refused(EventLog.EVENT, "test-account.delete", refused.code())
          .addKeyValue("account.id", actor == null ? null : actor.accountId())
          .addKeyValue("target.id", id)
          .log(
              "Deleting test account {} by {} refused: {}",
              id,
              EventLog.actor("Account", actor == null ? null : actor.accountId()),
              refused.code());
      throw refused;
    }
  }

  private Reservation authorizedTarget(
      TestAccount.RegistryState work, AccountPrincipal actor, UUID id) {
    policy.requireEnabled();
    if (id == null)
      throw new AccountFailure(404, "TEST_ACCOUNT_NOT_FOUND", "Managed test account not found.");
    AccountPrincipal.requireSignedIn(actor, "Sign in to continue.");
    boolean isOwner = owner.allowed(actor);
    var assignment = staff.forAccount(actor.accountId());
    Set<String> roles = assignment == null ? Set.of() : assignment.roles();
    boolean coauthor = roles.contains("co-author");
    boolean admin = roles.contains("admin");
    if (!isOwner && !coauthor && !admin)
      throw new AccountFailure(403, "DELETE_FORBIDDEN", "Staff access required.");
    var registry = work.registryLocked(id);
    if (registry == null)
      throw new AccountFailure(404, "TEST_ACCOUNT_NOT_FOUND", "Managed test account not found.");
    String username = registry.username();
    if (AuthorPreviewLocalService.ACCOUNT_ID.equals(id))
      throw new AccountFailure(
          403, "PROTECTED_TEST_ACCOUNT", "The Local author account cannot be deleted.");
    if (policy.cloud()) {
      if (TestAccountRoster.isRoster(id))
        throw new AccountFailure(
            403,
            "ROSTER_ACCOUNT_PERMANENT",
            "Roster accounts are never deleted. Reset puts one back to its scenario.");
      if (!TestAccountDeletionPolicy.allowsInDev(
          isOwner, actor.accountId(), id, registry.creatorId()))
        throw new AccountFailure(
            403,
            "DELETE_FORBIDDEN",
            "Only the owner or the person who created this test account can delete it.");
      return new Reservation(id, username, registry.creationState());
    }
    var targetAssignment = staff.forAccount(id);
    Set<String> targetRoles = targetAssignment == null ? Set.of() : targetAssignment.roles();
    String actorRole = isOwner ? "owner" : coauthor ? "co-author" : "admin";
    if (!TestAccountDeletionPolicy.allows(actorRole, actor.accountId(), id, targetRoles))
      throw new AccountFailure(
          403, "DELETE_FORBIDDEN", "Your role cannot delete this test account.");
    return new Reservation(id, username, registry.creationState());
  }

  private void erase(TestAccount.Work work, AccountPrincipal actor, UUID id) {
    var target = authorizedTarget(work, actor, id);
    if ("deleted".equals(target.state())) return;
    work.erase(id);
    // Cloud: a deleted test account leaves every test-session list and check at once. The admitted
    // row stays (V17).
    work.deleteCreation(id);
    work.deleteRegistry(id, actor.accountId());
  }
}
