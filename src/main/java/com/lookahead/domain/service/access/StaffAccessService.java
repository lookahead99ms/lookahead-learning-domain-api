package com.lookahead.domain.service.access;

import static com.lookahead.domain.db.store.access.StaffAccess.Assignment;

import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.dto.StaffAccessChange;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.exception.StorageFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.util.StaffAccessRoleRules;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StaffAccessService {
  private final StaffAccess assignments;
  private final AuthorPreviewLocalService owner;
  private final AccountCatalogService catalog;

  public StaffAccessService(
      StaffAccess assignments, AuthorPreviewLocalService owner, AccountCatalogService catalog) {
    this.assignments = assignments;
    this.owner = owner;
    this.catalog = catalog;
  }

  public StaffPermissions permissions(AccountPrincipal actor) {
    requireIdentity(actor);
    var assignment = assignments.forAccount(actor.accountId());
    return StaffPermissions.forRoles(
        owner.allowed(actor), assignment == null ? Set.of() : assignment.roles());
  }

  public void requireOwner(AccountPrincipal actor) {
    requireIdentity(actor);
    if (!owner.allowed(actor)) {
      EventLog.refused(EventLog.EVENT, "access.owner-check", "OWNER_REQUIRED")
          .addKeyValue("account.id", actor.accountId())
          .log("Owner-only action refused for account {}: not the owner", actor.accountId());
      throw new AccountFailure(403, "OWNER_REQUIRED", "Only the owner can manage access.");
    }
  }

  public void requireAccessReader(AccountPrincipal actor) {
    var current = permissions(actor);
    if (!current.manageAccess() && !"co-author".equals(current.feedbackRole()))
      throw new AccountFailure(403, "ACCESS_READER_REQUIRED", "Owner or co-author required.");
  }

  /**
   * DLV-969 (owner, 2026-11-04): the People rows the reader may see, by the one hierarchy ({@link
   * StaffAccessRoleRules#allows}): the owner sees every row, a co-author never the owner's, an
   * admin only admin and learner rows. Who may open the list is unchanged.
   */
  public List<Assignment> list(AccountPrincipal actor) {
    requireAccessReader(actor);
    var rows = assignments.list();
    String role = StaffAccessRoleRules.role(permissions(actor));
    if ("owner".equals(role)) return rows;
    var owners = assignments.owners();
    return rows.stream()
        .filter(row ->
            StaffAccessRoleRules.allows(
                role,
                row.accountId() != null
                        && (owners.contains(row.accountId())
                            || AuthorPreviewLocalService.ACCOUNT_ID.equals(row.accountId()))
                    ? Set.of("owner")
                    : row.roles()))
        .toList();
  }

  public Set<String> catalog(AccountPrincipal actor) {
    requireAccessReader(actor);
    return catalog.allTopicIds();
  }

  public Assignment save(AccountPrincipal actor, StaffAccessChange change) {
    requireOwner(actor);
    try {
      return saveChecked(actor, change);
    } catch (AccountFailure refused) {
      EventLog.refused(EventLog.EVENT, "access.save", refused.code())
          .addKeyValue("account.id", actor.accountId())
          .addKeyValue("target.id", change == null ? null : change.accountId())
          .log(
              "Access change by {} refused for {}: {}",
              EventLog.actor("Account", actor.accountId()),
              change == null || change.accountId() == null
                  ? "a pending invitation"
                  : "account " + change.accountId(),
              refused.code());
      throw refused;
    }
  }

  private Assignment saveChecked(AccountPrincipal actor, StaffAccessChange change) {
    if (change == null
        || change.id() == null
        || change.roles() == null
        || !StaffPermissions.ROLES.containsAll(change.roles())
        || change.topicGrants() == null
        || !catalog.allTopicIds().containsAll(change.topicGrants())
        || change.revision() < 0) throw invalid();
    String email = change.pendingEmail() == null ? null : normalizeEmail(change.pendingEmail());
    if ((change.accountId() == null) == (email == null)) throw invalid();
    if (actor.accountId().equals(change.accountId())
        || AuthorPreviewLocalService.ACCOUNT_ID.equals(change.accountId()))
      throw new AccountFailure(
          422, "OWNER_ACCESS_FIXED", "Owner access cannot be changed on this page.");
    var old = assignments.lock(change.id());
    if ((old == null && change.revision() != 0)
        || (old != null && old.revision() != change.revision())) throw conflict();
    // A saved assignment cannot be moved to a different person.
    if (old != null
        && (!Objects.equals(old.accountId(), change.accountId())
            || !Objects.equals(old.pendingEmail(), email))) throw invalid();
    var next =
        new Assignment(
            change.id(),
            change.accountId(),
            email,
            Set.copyOf(change.roles()),
            Set.copyOf(change.topicGrants()),
            change.fullCurriculum(),
            change.revision() + 1);
    try {
      assignments.save(actor.accountId(), "assign", old, next);
    }
    // The store lost its revision condition (another save came first): the same 409 as the revision
    // check above. An unknown outcome goes on as it is.
    catch (StorageFailure lost) {
      if (!lost.conflict()) throw lost;
      throw conflict();
    }
    Set<String> before = old == null ? Set.of() : old.roles();
    EventLog.afterCommit(() ->
        EventLog.success(
                EventLog.EVENT,
                next.accountId() == null ? "access.invitation-saved" : "access.saved")
            .addKeyValue("account.id", actor.accountId())
            .addKeyValue("target.id", next.accountId() == null ? next.id() : next.accountId())
            .addKeyValue("roles", new TreeSet<>(next.roles()))
            .addKeyValue("rolesBefore", new TreeSet<>(before))
            .log(
                "{} saved access for {}: roles {} -> {}, {} topic grant(s), full curriculum {}",
                EventLog.actor("Owner", actor.accountId()),
                next.accountId() == null
                    ? "pending invitation " + next.id()
                    : "account " + next.accountId(),
                new TreeSet<>(before),
                new TreeSet<>(next.roles()),
                next.topicGrants().size(),
                next.fullCurriculum() ? "on" : "off"));
    return next;
  }

  /** Only an authoritative provider-verified address may claim an owner-created invitation. */
  public void claimVerified(AccountPrincipal actor, String verifiedEmail) {
    requireIdentity(actor);
    if (verifiedEmail == null || owner.allowed(actor)) return;
    claim(actor.accountId(), normalizeEmail(verifiedEmail), true);
  }

  /**
   * DLV-971 (owner, 2026-11-07): prepared access by email is a pass through the DEV gate. The
   * sign-in registry calls this inside its first-contact transaction for the account it has just
   * recorded; the claimed assignment, or null when nothing is prepared for that address, decides
   * whether the account is admitted at once. Never a conflict: a brand-new account holds nothing,
   * and an older one that does is simply left alone.
   */
  public Assignment claimPrepared(UUID accountId, String verifiedEmail) {
    if (accountId == null || verifiedEmail == null) return null;
    return claim(accountId, normalizeEmail(verifiedEmail), false);
  }

  /**
   * An invitation is good for this long after it was last changed (DLV-1016: the number and the
   * rule were in the store).
   */
  public static final Duration PENDING_LIFETIME = Duration.ofDays(14);

  /**
   * True while an invitation can still be claimed: not yet bound to an account, written for this
   * email, younger than {@link #PENDING_LIFETIME}, and granting something.
   */
  public static boolean liveInvitation(StaffAccess.Invitation invitation, String email) {
    if (invitation == null) return false;
    var a = invitation.assignment();
    return a.accountId() == null
        && email.equals(a.pendingEmail())
        && invitation.age().compareTo(PENDING_LIFETIME) < 0
        && (!a.roles().isEmpty() || !a.topicGrants().isEmpty() || a.fullCurriculum());
  }

  private Assignment claim(UUID accountId, String email, boolean conflictWhenAssigned) {
    var invitation = assignments.pending(email);
    if (!liveInvitation(invitation, email)) return null;
    var pending = invitation.assignment();
    if (assignments.forAccount(accountId) != null) {
      if (conflictWhenAssigned) throw conflict();
      return null;
    }
    var next =
        new Assignment(
            pending.id(),
            accountId,
            null,
            pending.roles(),
            pending.topicGrants(),
            pending.fullCurriculum(),
            pending.revision() + 1);
    assignments.save(accountId, "claim", pending, next);
    EventLog.afterCommit(() ->
        EventLog.success(EventLog.EVENT, "access.invitation-claimed")
            .addKeyValue("account.id", accountId)
            .addKeyValue("target.id", pending.id())
            .addKeyValue("roles", new TreeSet<>(next.roles()))
            .log(
                "Account {} claimed pending access invitation {}: roles {}",
                accountId,
                pending.id(),
                new TreeSet<>(next.roles())));
    return next;
  }

  public static String normalizeEmail(String email) {
    String clean = email.strip().toLowerCase(Locale.ROOT);
    if (clean.length() > 254
        || !clean.matches(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]"
                + "*[A-Za-z0-9])?\\.[A-Za-z]{2,63}")
        || clean.contains("..")
        || clean.startsWith(".")
        || clean.contains(".@")) throw invalid();
    return clean;
  }

  private static void requireIdentity(AccountPrincipal actor) {
    AccountPrincipal.requireSignedIn(actor, "Sign in to continue.");
  }

  private static AccountFailure invalid() {
    return new AccountFailure(
        422, "INVALID_ACCESS_ASSIGNMENT", "Check the recipient, roles and content selection.");
  }

  private static AccountFailure conflict() {
    return new AccountFailure(
        409, "ACCESS_ASSIGNMENT_CONFLICT", "Reload the current access assignment before saving.");
  }
}
