package com.lookahead.domain.service.signin;

import static com.lookahead.domain.util.SignInRules.*;

import com.lookahead.domain.db.store.signin.SignIn;
import com.lookahead.domain.db.store.signin.SignIn.AttemptAccount;
import com.lookahead.domain.db.store.signin.SignIn.FirstContact;
import com.lookahead.domain.db.store.signin.SignIn.Work;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.model.SignInModels.*;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.util.RandomToken;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.util.SignInIdentityRules;
import com.lookahead.domain.validator.AccountProfileLinkValidator;
import com.lookahead.domain.validator.SignInValueValidator;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * DLV-969: DEV/PROD sign-in: first contact, admission of a sign-in into one of two slots,
 * challenges, revocation, profile, preferences and links. DLV-1016: every sign-in decision is made
 * here: the two-sign-in limit, idle (30 min) and absolute (7 days) expiry, challenge expiry (5 min)
 * and the 5-per-15-minutes limit (all checked in code at the exact boundary), the sign-in
 * generation, linking a new identity by its verified email, admission at first contact, the
 * refusals for a disabled, deleted, waiting or half-changed account, and the log lines. The work of
 * each call runs inside a unit of {@link SignIn} ({@link
 * com.lookahead.domain.db.store.signin.SignInStore}: DynamoDB items, every write one transaction
 * fenced on the account's control revision), so each decision is still made between the unit's read
 * and its fenced write and is made again when the unit loses a race and runs again.
 */
public class SignInService {
  /**
   * Linking's outcome: the account linked to, or none; {@code admitNew}: several accounts share the
   * email and all are admitted, so the sign-in becomes its own admitted account.
   */
  private record Linking(UUID linked, long emailRevision, boolean admitNew) {}

  private final SignIn store;
  private final StaffAccessService access;

  /**
   * DLV-971: prepared access by email ({@code access}) admits a first contact at once; null leaves
   * every first contact waiting.
   */
  public SignInService(SignIn store, StaffAccessService access) {
    this.store = store;
    this.access = access;
  }

  /**
   * DLV-969: a sign-in without an account records a waiting account (first contact) and still
   * answers 403.
   */
  public Admission admit(SignInIdentity identity, String proof) {
    String binding = binding(proof), family = family(identity);
    Admission admission =
        store.firstContact(
            identity,
            contact -> {
              firstContact(contact, identity);
              return merge(contact, identity, admitted(contact, identity, binding, family));
            });
    if (admission.signInId() != null)
      EventLog.success(EventLog.EVENT, "sign-in.admitted")
          .addKeyValue("account.id", admission.accountId())
          .addKeyValue("target.id", admission.signInId())
          .addKeyValue("merge.available", admission.mergeAvailable())
          .log(
              admission.mergeAvailable()
                  ? "Sign-in {} admitted for account {}; other accounts share its verified email,"
                      + " so the merge is offered"
                  : "Sign-in {} admitted for account {}",
              admission.signInId(),
              admission.accountId());
    else
      EventLog.success(EventLog.EVENT, "sign-in.challenge")
          .addKeyValue("account.id", admission.accountId())
          .log(
              "Account {} already has 2 active sign-ins: a challenge to replace one was issued",
              admission.accountId());
    return admission;
  }

  /**
   * The sign-in itself, for an admitted account: the same sign-in again, a free slot, or a
   * challenge to replace one.
   */
  private static Admission admitted(
      Work work, SignInIdentity identity, String binding, String family) {
    Account a = work.account();
    Instant now = work.now();
    requireFresh(identity.authenticatedAt(), now);
    var known = work.familyBinding(family);
    if (known != null) {
      Session existing = known.accountId().equals(a.id()) ? work.session(known.signInId()) : null;
      if (existing == null
          || !active(existing, a.authGeneration(), now)
          || !existing.binding().equals(binding)) throw denied();
      return new Admission(a.id(), existing.id(), null, null);
    }
    var sessions = activeSessions(work, now);
    var old = sessions.stream().filter(s -> s.binding().equals(binding)).findFirst();
    if (old.isPresent()) {
      // The original family binding stays as a tombstone: revoked credentials must never be
      // admitted again.
      UUID id =
          work.reissueSession(old.get().id(), family, binding, identity.authenticatedAt(), now);
      return new Admission(a.id(), id, null, null);
    }
    if (sessions.size() < SIGN_IN_LIMIT) {
      UUID id = work.addSession(family, binding, identity.authenticatedAt(), now);
      return new Admission(a.id(), id, null, null);
    }
    var challenges = work.challenges();
    // Do not issue a second challenge for the same token family; the caller retains its secret.
    if (challenges.stream()
        .anyMatch(c ->
            c.family().equals(family)
                && c.binding().equals(binding)
                && !c.cancelled()
                && c.expires().isAfter(now))) throw failure(409, "SIGN_IN_CHALLENGE_PENDING");
    Instant windowStart = now.minus(WINDOW);
    // A challenge whose expiry is older than the window is deleted with the new one's transaction.
    var stale =
        challenges.stream()
            .filter(c -> c.expires().isBefore(windowStart))
            .map(Challenge::digest)
            .toList();
    if (challenges.stream().filter(c -> c.created().isAfter(windowStart)).count()
        >= CHALLENGES_PER_WINDOW) {
      EventLog.limit(
              "sign-in.challenge",
              "SIGN_IN_RATE_LIMITED",
              "sign-in-challenges",
              CHALLENGES_PER_WINDOW,
              "15m")
          .addKeyValue("account.id", a.id())
          .log(
              "Sign-in limit hit for account {}: at most {} sign-in challenges in 15 minutes;"
                  + " refused with 429",
              a.id(),
              CHALLENGES_PER_WINDOW);
      throw failure(429, "SIGN_IN_RATE_LIMITED");
    }
    String token = RandomToken.next();
    Instant expires = now.plus(CHALLENGE);
    work.issueChallenge(
        stale, Sha256.hex(token), family, binding, identity.authenticatedAt(), now, expires);
    return new Admission(a.id(), null, token, expires);
  }

  private static Admission merge(
      AttemptAccount work, SignInIdentity identity, Admission admission) {
    return new Admission(
        admission.accountId(),
        admission.signInId(),
        admission.challengeToken(),
        admission.expiresAt(),
        mergeable(work, admission.accountId(), email(identity)));
  }

  /**
   * DLV-969 S36: the merge can be offered when this and at least one other account carry the
   * sign-in's verified email and none of them is disabled.
   */
  private static boolean mergeable(AttemptAccount work, UUID account, String email) {
    if (email == null || email.isBlank()) return false;
    var carriers = work.emailAccounts(email);
    boolean own = carriers.stream().anyMatch(c -> c.id().equals(account));
    boolean blocked = carriers.stream().anyMatch(c -> !c.enabled() || !c.active());
    return own && carriers.size() >= 2 && !blocked;
  }

  public AccountPrincipal authenticate(SignInIdentity identity, String proof) {
    return known(
        identity,
        work -> {
          current(work, identity, proof, true);
          Account a = work.account();
          return new AccountPrincipal(
              a.id(), a.username(), a.displayName(), true, a.author(), identity.verifiedEmail());
        });
  }

  public Inventory inventory(SignInIdentity identity, String proof) {
    return known(
        identity,
        work -> {
          Session current = current(work, identity, proof, true);
          return new Inventory(SIGN_IN_LIMIT, entries(work, current.id()));
        });
  }

  public ChallengeView challenge(SignInIdentity identity, String proof, String token) {
    return known(
        identity,
        work -> {
          Challenge c = challenge(work, identity, proof, token);
          return new ChallengeView(SIGN_IN_LIMIT, entries(work, null), c.expires());
        });
  }

  public Admission replace(SignInIdentity identity, String proof, String token, UUID selected) {
    return known(
        identity,
        work -> {
          Account a = work.account();
          Challenge c = challenge(work, identity, proof, token);
          Instant now = work.now();
          if (c.admitted() != null) {
            if (!Objects.equals(c.selected(), selected)) throw invalid();
            current(work, identity, proof, false);
            return merge(work, identity, new Admission(a.id(), c.admitted(), null, null));
          }
          Session chosen = selected == null ? null : work.session(selected);
          if (chosen == null) throw invalid();
          var remaining =
              activeSessions(work, now).stream().filter(s -> !s.id().equals(selected)).toList();
          if (remaining.size() >= SIGN_IN_LIMIT) throw invalid();
          // Duplicate challenges cannot create multiple active families or bindings.
          if (work.familyBinding(c.family()) != null
              || remaining.stream().anyMatch(s -> s.binding().equals(c.binding()))) throw invalid();
          UUID id = work.replaceSession(selected, chosen.revoked() == null, c, now);
          EventLog.success(EventLog.EVENT, "sign-in.replaced")
              .addKeyValue("account.id", a.id())
              .addKeyValue("target.id", selected)
              .log("Account {} replaced sign-in {} with new sign-in {}", a.id(), selected, id);
          return merge(work, identity, new Admission(a.id(), id, null, null));
        });
  }

  public void cancel(SignInIdentity identity, String proof, String token) {
    known(
        identity,
        work -> {
          Challenge c = challenge(work, identity, proof, token);
          if (c.admitted() == null) work.cancelChallenge(c.digest());
          return null;
        });
  }

  public boolean revoke(SignInIdentity identity, String proof, UUID target) {
    return known(
        identity,
        work -> {
          Account a = work.account();
          Session current = current(work, identity, proof, false);
          Instant now = work.now();
          requireFresh(current.recent(), now);
          Session ended = work.session(target);
          if (ended == null) throw invalid();
          // Already ended: nothing to write, the answer is the same.
          if (ended.revoked() == null) work.endSessions(List.of(target), now);
          EventLog.success(EventLog.EVENT, "sign-in.revoked")
              .addKeyValue("account.id", a.id())
              .addKeyValue("target.id", target)
              .log("Account {} ended sign-in {}", a.id(), target);
          return current.id().equals(target);
        });
  }

  public void revokeOthers(SignInIdentity identity, String proof) {
    known(
        identity,
        work -> {
          Account a = work.account();
          Session current = current(work, identity, proof, false);
          Instant now = work.now();
          requireFresh(current.recent(), now);
          var others =
              activeSessions(work, now).stream().filter(s -> !s.id().equals(current.id())).toList();
          work.endOtherSessions(current.id());
          EventLog.success(EventLog.EVENT, "sign-in.revoked-others")
              .addKeyValue("account.id", a.id())
              .addKeyValue("count", others.size())
              .log("Account {} ended its other sign-ins ({} row(s))", a.id(), others.size());
          return null;
        });
  }

  public Entry rename(SignInIdentity identity, String proof, String label) {
    SignInValueValidator.label(label);
    return known(
        identity,
        work -> {
          Session current = current(work, identity, proof, false);
          work.labelSession(current.id(), label.strip());
          return entries(work, current.id()).stream()
              .filter(Entry::current)
              .findFirst()
              .orElseThrow();
        });
  }

  public void terminate(SignInIdentity identity, String proof) {
    known(
        identity,
        work -> {
          Account a = work.account();
          String binding = binding(proof), family = family(identity);
          Instant now = work.now();
          var mine =
              work.sessions().stream()
                  .filter(s ->
                      s.revoked() == null
                          && s.family().equals(family)
                          && s.binding().equals(binding))
                  .toList();
          if (!mine.isEmpty()) work.endSessions(mine.stream().map(Session::id).toList(), now);
          EventLog.success(EventLog.EVENT, "sign-in.signed-out")
              .addKeyValue("account.id", a.id())
              .log("Account {} signed out", a.id());
          return null;
        });
  }

  public Map<String, Object> profile(SignInIdentity identity, String proof, String displayName) {
    return known(
        identity,
        work -> {
          current(work, identity, proof, true);
          String name = work.account().displayName();
          if (displayName != null) {
            // Checked after the sign-in itself, on purpose: a caller without a sign-in is told
            // that, not what is wrong with the name.
            name = SignInValueValidator.displayName(displayName);
            work.saveDisplayName(name);
          }
          return profileView(work, name);
        });
  }

  public Map<String, Object> preferences(SignInIdentity identity, String proof, String theme) {
    SignInValueValidator.theme(theme);
    return known(
        identity,
        work -> {
          current(work, identity, proof, true);
          work.saveTheme(theme);
          return profileView(work, work.account().displayName());
        });
  }

  public Map<String, Object> links(
      SignInIdentity identity, String proof, Map<String, String> links) {
    var values = SignInValueValidator.links(links);
    return known(
        identity,
        work -> {
          current(work, identity, proof, true);
          work.saveLinks(values);
          return profileView(work, work.account().displayName());
        });
  }

  /**
   * The profile answer: every link kind (null for none), "system" when no theme was saved, the
   * account's email or else its first sign-in method's.
   */
  private static Map<String, Object> profileView(Work work, String name) {
    Account account = work.account();
    StoredProfile profile = work.profile();
    var links = new LinkedHashMap<String, String>();
    for (String kind : AccountProfileLinkValidator.KINDS) links.put(kind, null);
    profile
        .links()
        .forEach((kind, url) -> {
          if (links.containsKey(kind)) links.put(kind, url);
        });
    var methods = profile.signInMethods();
    var view =
        new LinkedHashMap<String, Object>(
            Map.of(
                "accountId",
                account.id(),
                "username",
                account.username(),
                "displayName",
                name,
                "signInMethod",
                account.provider(),
                "signInMethods",
                methods,
                "themePreference",
                profile.theme() == null ? "system" : profile.theme(),
                "links",
                links));
    String shown =
        account.verifiedEmail() != null
            ? account.verifiedEmail()
            : methods.stream()
                .map(m -> (String) m.get("email"))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    if (shown != null) view.put("email", shown);
    return view;
  }

  public void changeCredentials(SignInIdentity identity, String proof, Runnable providerChange) {
    // Commit a fail-closed intent before crossing the provider/store boundary: a crash leaves all
    // account access blocked.
    UUID account = beginCredentialChange(identity, proof);
    try {
      providerChange.run();
    } catch (AccountFailure rejected) {
      boolean knownRejection =
          Set.of("INVALID_CURRENT_PASSWORD", "INVALID_PASSWORD").contains(rejected.code());
      finishCredentialChange(identity, !knownRejection);
      (knownRejection
              ? EventLog.refused(EventLog.EVENT, "password.change", rejected.code())
              : EventLog.failed(EventLog.EVENT, "password.change", rejected.code()))
          .addKeyValue("account.id", account)
          .log(
              knownRejection
                  ? "Password change for account {} refused by the sign-in service: {}"
                  : "Password change for account {} not confirmed by the sign-in service: {}; every"
                      + " sign-in of the account was ended",
              account,
              rejected.code());
      throw rejected;
    } catch (RuntimeException uncertain) {
      finishCredentialChange(identity, true);
      EventLog.failed(EventLog.EVENT, "password.change", "UNEXPECTED")
          .addKeyValue("account.id", account)
          .log(
              "Password change for account {} failed: {}; every sign-in of the account was ended",
              account,
              EventLog.types(uncertain));
      throw uncertain;
    }
    finishCredentialChange(identity, true);
    EventLog.success(EventLog.EVENT, "password.change")
        .addKeyValue("account.id", account)
        .log("Password changed for account {}; every sign-in of the account was ended", account);
  }

  /**
   * Commits the fail-closed mark before the sign-in provider is asked to change the password;
   * answers the account.
   */
  private UUID beginCredentialChange(SignInIdentity identity, String proof) {
    return known(
        identity,
        work -> {
          current(work, identity, proof, false);
          work.markCredentialChange();
          return work.account().id();
        });
  }

  /**
   * Clears the mark; with {@code revoke} every sign-in of the account ends and its open challenges
   * are cancelled.
   */
  private void finishCredentialChange(SignInIdentity identity, boolean revoke) {
    known(
        identity,
        true,
        work -> {
          var open =
              revoke
                  ? work.challenges().stream()
                      .filter(c -> !c.cancelled())
                      .map(Challenge::digest)
                      .toList()
                  : List.<String>of();
          work.settleCredentialChange(revoke, open);
          return null;
        });
  }

  // ---- sign-ins
  // -----------------------------------------------------------------------------------------------

  private static Session current(Work work, SignInIdentity identity, String proof, boolean touch) {
    Instant now = work.now();
    String family = family(identity), binding = binding(proof);
    var rows =
        activeSessions(work, now).stream()
            .filter(s -> s.family().equals(family) && s.binding().equals(binding))
            .toList();
    if (rows.size() != 1) throw denied();
    Session result = rows.getFirst();
    // Only once a minute, so a page's parallel calls do not contend on one item.
    if (touch && !result.active().plus(TOUCH).isAfter(now)) work.touch(result.id(), now);
    return result;
  }

  private static Challenge challenge(
      Work work, SignInIdentity identity, String proof, String token) {
    long generation = work.account().authGeneration();
    String family = family(identity), binding = binding(proof);
    // Checked after the account and before the read, as before: a caller without an admitted
    // account is told that first.
    SignInValueValidator.challengeToken(token);
    Challenge c = work.challenge(Sha256.hex(token));
    if (c == null) throw invalid();
    // A challenge issued under an earlier sign-in generation (Disable, Delete, a merge, "end other
    // sign-ins") is dead with it.
    if (c.cancelled()
        || c.generation() != generation
        || !c.expires().isAfter(work.now())
        || !c.family().equals(family)
        || !c.binding().equals(binding)) throw invalid();
    return c;
  }

  private static List<Session> activeSessions(Work work, Instant now) {
    long generation = work.account().authGeneration();
    return work.sessions().stream()
        .filter(s -> active(s, generation, now))
        .sorted(Comparator.comparing(Session::created).thenComparing(s -> s.id().toString()))
        .toList();
  }

  private static List<Entry> entries(Work work, UUID current) {
    return activeSessions(work, work.now()).stream()
        .map(s ->
            new Entry(
                s.id(),
                Objects.equals(current, s.id()),
                s.label(),
                s.client(),
                s.created(),
                s.active()))
        .toList();
  }

  // ---- the account behind an identity
  // ---------------------------------------------------------------------------

  /**
   * Every call after admission: the identity must already be bound to an admitted, enabled account.
   */
  private <T> T known(SignInIdentity identity, Function<Work, T> action) {
    return known(identity, false, action);
  }

  private <T> T known(
      SignInIdentity identity, boolean allowCredentialChange, Function<Work, T> action) {
    return store.unit(
        identity,
        work -> {
          if (!work.known()) throw admissionRequired();
          Account row = work.account();
          if (row == null) throw denied();
          requireEnabled(row);
          if (!row.admitted()) throw denied();
          requireCredentialsSettled(row, allowCredentialChange);
          return action.apply(work);
        });
  }

  /**
   * Sign-in admission only: an unknown identity is linked to the one account with its verified
   * email, or recorded as a new waiting account, and an unadmitted account is refreshed from the
   * provider claims; all commit before the 403 is thrown. On return the attempt's account is
   * admitted, enabled and settled.
   */
  private void firstContact(FirstContact contact, SignInIdentity identity) {
    Instant now = contact.now();
    boolean linked = false;
    if (!contact.known()) {
      var linking = link(contact, identity, now);
      if (linking.linked == null) {
        UUID created = request(contact, identity, now, linking.emailRevision, linking.admitNew);
        Account row = contact.follow(created);
        if (row == null) throw denied();
        if (!row.admitted()) throw waiting(created, "created");
        contact.rebind();
      } else {
        linked = true;
        if (contact.follow(linking.linked) == null) throw denied();
      }
    } else if (contact.account() == null) throw denied();
    Account row = contact.account();
    requireEnabled(row);
    // DLV-969 S36: a sign-in just linked to a waiting account is "linked"; the owner still has to
    // admit it.
    // DLV-970: unless the sign-in is the protected owner identity whose binding was written after
    // its first contact
    // (the DEV cutover order): the reviewed decision admits the waiting owner now, nothing else
    // could.
    if (!row.admitted()) {
      if (contact.ownerIdentity()) {
        contact.admitWaitingOwner(now);
        EventLog.success(EventLog.EVENT, "admission.admitted-owner")
            .addKeyValue("account.id", row.id())
            .log(
                "Waiting account {} admitted: its sign-in is the protected owner identity, bound"
                    + " after the first contact",
                row.id());
        row = contact.reload();
      } else {
        if (!linked) contact.refreshClaims(email(identity));
        throw waiting(row.id(), linked ? "linked" : "refreshed");
      }
    }
    if (!linked) {
      contact.refreshEmail(email(identity));
      row = contact.account();
    }
    // Owner identity (Protected Owner Identity, issuer/subject) decides author access; it is
    // recorded on the control item at sign-in, never from a profile field.
    if (!row.author() && contact.ownerIdentity()) {
      contact.makeAuthor();
      row = contact.account();
    }
    requireCredentialsSettled(row, false);
  }

  /**
   * DLV-969: an account the owner disabled or deleted has no sign-in, even before the pool is told.
   */
  private static void requireEnabled(Account row) {
    if (row.enabled() && row.active()) return;
    String reason = row.removed() ? "ACCOUNT_REMOVED" : "ACCOUNT_DISABLED";
    EventLog.refused(EventLog.EVENT, "sign-in.check", reason)
        .addKeyValue("account.id", row.id())
        .log(
            "Sign-in refused for account {}: {}",
            row.id(),
            reason.equals("ACCOUNT_REMOVED") ? "the owner deleted it" : "the owner disabled it");
    throw denied();
  }

  private static void requireCredentialsSettled(Account row, boolean allowCredentialChange) {
    if (allowCredentialChange || !row.credentialChangePending()) return;
    EventLog.refused(EventLog.EVENT, "sign-in.check", "CREDENTIAL_CHANGE_PENDING")
        .addKeyValue("account.id", row.id())
        .log(
            "Request refused for account {}: a password change is not confirmed yet (503)",
            row.id());
    throw failure(503, "CREDENTIAL_CHANGE_PENDING");
  }

  private static AccountFailure waiting(UUID id, String action) {
    // Committed: the owner now sees the waiting account on People and access.
    EventLog.refused(EventLog.EVENT, "admission.waiting-" + action, "ACCOUNT_ADMISSION_REQUIRED")
        .addKeyValue("account.id", id)
        .log(
            "Sign-in refused for account {}: not admitted yet; waiting row {} (removed after {} h"
                + " unless the owner admits it)",
            id,
            action,
            WAITING_EXPIRY.toHours());
    return admissionRequired();
  }

  /**
   * DLV-969 S36 (owner decision 2026-11-04): an unknown identity with a provider-verified email
   * joins the one eligible account that already has that email (enabled, never disabled or deleted,
   * not a test account). Zero or several candidates, no verified email, or a candidate disabled or
   * deleted meanwhile: nothing linked, and the caller records a new waiting account. The per-email
   * sentinel, read before the candidates and advanced in the linking transaction, serialises a
   * Google and a password first contact.
   */
  private static Linking link(FirstContact contact, SignInIdentity identity, Instant now) {
    String email = email(identity);
    if (email == null) return new Linking(null, 0, false);
    long emailRevision = contact.emailRevision(email);
    var candidates =
        contact.emailAccounts(email).stream()
            .filter(c -> c.enabled() && c.active() && !c.managedTest())
            .toList();
    String provider = SignInIdentityRules.provider(identity.issuer(), identity.username());
    if (candidates.size() > 1) {
      int several = candidates.size();
      boolean allAdmitted = candidates.stream().allMatch(EmailAccount::admitted);
      EventLog.success(EventLog.EVENT, "identity.link-skipped")
          .addKeyValue("reason", "SEVERAL_SAME_EMAIL_ACCOUNTS")
          .addKeyValue("provider", provider)
          .addKeyValue("count", several)
          .addKeyValue("allAdmitted", allAdmitted)
          .log(
              "New {} sign-in not linked: {} eligible accounts already share its verified email{}",
              provider,
              several,
              allAdmitted
                  ? "; all are admitted, so the sign-in is admitted as its own account"
                  : "; recorded as a new waiting account");
      return new Linking(null, emailRevision, allAdmitted);
    }
    if (candidates.size() != 1) return new Linking(null, emailRevision, false);
    EmailAccount row = candidates.getFirst();
    contact.link(row.id(), email, emailRevision, now);
    EventLog.success(EventLog.EVENT, "identity.linked")
        .addKeyValue("account.id", row.id())
        .addKeyValue("provider", provider)
        .addKeyValue("admitted", row.admitted())
        .log(
            "New {} sign-in linked to account {}: its verified email matched exactly one eligible"
                + " account{}",
            provider,
            row.id(),
            row.admitted() ? "" : " (still waiting for the owner to admit it)");
    return new Linking(row.id(), emailRevision, false);
  }

  /** Records the new account of an unknown identity; who is admitted at once is decided here. */
  private UUID request(
      FirstContact contact,
      SignInIdentity identity,
      Instant now,
      long emailRevision,
      boolean sameEmailAdmitted) {
    String email = email(identity);
    // DLV-970: the owner identity (Protected Owner Identity) is admitted at its own first contact.
    // Nothing else could
    // admit it, and the protected table is the reviewed admission decision.
    boolean author = contact.ownerIdentity();
    // DLV-969 (owner, 2026-11-22): a new sign-in whose verified email already belongs to admitted
    // accounts is admitted at once.
    boolean admitted = author || sameEmailAdmitted;
    UUID id = contact.create(email, emailRevision, admitted, author, now);
    if (author) {
      EventLog.success(EventLog.EVENT, "admission.admitted-owner")
          .addKeyValue("account.id", id)
          .log(
              "Account {} admitted at first contact: its sign-in is the protected owner identity",
              id);
      return id;
    }
    if (sameEmailAdmitted) {
      EventLog.success(EventLog.EVENT, "admission.admitted-same-email")
          .addKeyValue("account.id", id)
          .log(
              "Account {} admitted at first contact: its verified email belongs only to admitted"
                  + " accounts",
              id);
      return id;
    }
    // DLV-971 (owner, 2026-11-07): prepared access by email is a pass through the DEV gate: the
    // invitation is claimed
    // for the new account and the account admitted at once, so the owner never sees it waiting.
    // This runs inside the
    // attempt, so a lost race here (in the claim or in the admission) runs the attempt again.
    var prepared = access == null ? null : access.claimPrepared(id, email);
    if (prepared != null) {
      var roles = new TreeSet<>(prepared.roles());
      contact.admitCreated(List.copyOf(roles), now);
      EventLog.success(EventLog.EVENT, "admission.admitted-by-invitation")
          .addKeyValue("account.id", id)
          .addKeyValue("target.id", prepared.id())
          .addKeyValue("roles", roles)
          .log(
              "Account {} admitted at first contact by prepared access {}: roles {}",
              id,
              prepared.id(),
              roles.isEmpty() ? "none (learner)" : roles);
    }
    return id;
  }
}
