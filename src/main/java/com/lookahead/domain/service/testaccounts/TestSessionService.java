package com.lookahead.domain.service.testaccounts;

import com.lookahead.domain.db.store.testaccounts.TestAccountIdentities;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.model.TestAccountRoster;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.util.StaffAccessRoleRules;
import com.lookahead.domain.util.TestAccountTemplates;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-969 S35: staff use DEV test accounts through a test session inside the DEV gate. The Gateway
 * relays, from its own session only, who opened the gate (their Cognito {@code sub}) and the test
 * account; the Domain decides on every call. This service decides on plain values; reading them
 * from a request is the filters' part ({@code security.filter.TestSessionFilter}).
 *
 * <ul>
 *   <li>Only with the Gateway secret (constant-time compare), and only in DEV with test accounts
 *       on.
 *   <li>The gate holder must be an admitted, enabled DEV account that is not disabled or deleted.
 *       Its role (owner, co-author, admin, or learner for an account without a staff role or with
 *       only reviewer or tester) goes through {@link
 *       com.lookahead.domain.util.StaffAccessRoleRules#allows}, the one hierarchy (owner,
 *       2026-11-04): a learner uses test learners only. The session then acts as the test account,
 *       so its course access is the test account's own.
 *   <li>The target must be an active DEV test account (its cloud_accounts row is on the test
 *       issuer).
 * </ul>
 */
public class TestSessionService {
  /**
   * One test account the gate holder may use. {@code upgradedBy} (DLV-969, owner 2026-11-04): who
   * upgraded it since its latest Reset, {@code {self, label, name?, plan, approvedAt}} ({@code
   * name} for the owner only), or null.
   */
  public record Usable(
      UUID accountId,
      String username,
      String displayName,
      List<String> roles,
      String scenario,
      ObjectNode upgradedBy) {
    public Usable(
        UUID accountId, String username, String displayName, List<String> roles, String scenario) {
      this(accountId, username, displayName, roles, scenario, null);
    }
  }

  /** An admitted, enabled gate holder: their account and their place in the one hierarchy. */
  private record Holder(UUID accountId, String role) {}

  private static final Holder NOBODY = new Holder(null, "");

  private record Target(UUID accountId, String username, String displayName) {}

  private final TestAccountIdentities store;
  private final TestAccountModeService policy;
  private final String issuer;
  private final byte[] secret;

  /** With the upgrade markers of the picker (DLV-969, owner 2026-11-04). */
  public TestSessionService(
      TestAccountIdentities store,
      TestAccountModeService policy,
      String issuer,
      String bffSecret,
      TestAccountUpgradeMarkerService markers) {
    this(store, policy, issuer, bffSecret);
    this.markers = markers;
  }

  public TestSessionService(
      TestAccountIdentities store, TestAccountModeService policy, String issuer, String bffSecret) {
    this.store = store;
    this.policy = policy;
    this.issuer = issuer;
    this.secret = Objects.requireNonNull(bffSecret).getBytes(StandardCharsets.UTF_8);
  }

  private TestAccountUpgradeMarkerService markers;

  /**
   * DLV-969 (owner, 2026-11-04): the "upgraded by" marker on the picker; without it every account
   * shows none.
   */
  public void configureMarkers(TestAccountUpgradeMarkerService markers) {
    this.markers = markers;
  }

  /**
   * Whether {@code supplied} is the Gateway BFF's secret, compared in constant time. An absent
   * value is compared as the empty text, so the comparison runs for every request and nothing about
   * the caller decides whether it runs.
   */
  public boolean secretMatches(String supplied) {
    byte[] bytes = Objects.toString(supplied, "").getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(bytes, secret);
  }

  /** The test accounts this gate holder may use; empty for anyone who is not admitted. */
  public List<Usable> usable(String holderSubject) {
    policy.requireCloud();
    var holder = holderOf(holderSubject);
    String role = holder.role();
    // Only the owner sees names; everyone sees their own requests as "self".
    boolean named = "owner".equals(role);
    var templates = new HashMap<UUID, String>();
    for (var entry : TestAccountRoster.entries())
      TestAccountTemplates.all().stream()
          .filter(t -> t.id().equals(entry.template()))
          .findFirst()
          .ifPresent(t -> templates.put(entry.accountId(), t.label()));
    return targets(null).stream()
        .map(target -> {
          var roles = roles(target.accountId());
          if (!StaffAccessRoleRules.allows(role, roles)) return null;
          var upgradedBy =
              markers == null
                  ? null
                  : markers.compact(markers.find(target.accountId()), holder.accountId(), named);
          return new Usable(
              target.accountId(),
              target.username(),
              target.displayName(),
              roles.stream().sorted().toList(),
              templates.get(target.accountId()),
              upgradedBy);
        })
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * DLV-969 S35: the Gateway starts a test session; one line says who acts as which test account,
   * or why not.
   */
  public AccountPrincipal start(String holderSubject, UUID accountId) {
    try {
      var principal = actAs(holderSubject, accountId);
      UUID holder = holderAccountId(holderSubject);
      EventLog.success(EventLog.EVENT, "test-session.start")
          .addKeyValue("account.id", holder)
          .addKeyValue("target.id", principal.accountId())
          .log(
              "Test session started: account {} acts as test account {}",
              holder,
              principal.accountId());
      return principal;
    } catch (AccountFailure refused) {
      EventLog.refused(EventLog.EVENT, "test-session.start", refused.code())
          .addKeyValue("account.id", holderAccountId(holderSubject))
          .addKeyValue("target.id", accountId)
          .log("Test session refused for test account {}: {}", accountId, refused.code());
      throw refused;
    }
  }

  /**
   * The gate holder's account id, for the log line and the request's record of who is behind the
   * session, or null when unknown; looking it up never fails the session.
   */
  public UUID holderAccountId(String subject) {
    if (subject == null || subject.isBlank() || subject.length() > 256) return null;
    try {
      // Any linked sign-in (S36) names the same account, as for the holder's role.
      return store.accountOf(issuer, subject);
    } catch (RuntimeException unknown) {
      return null;
    }
  }

  /**
   * The test account a gate holder acts as for this request; refused unless every rule above holds.
   */
  public AccountPrincipal actAs(String holderSubject, UUID accountId) {
    policy.requireCloud();
    if (accountId == null)
      throw new AccountFailure(422, "TEST_ACCOUNT_REQUIRED", "Choose a test account.");
    String role = holderRole(holderSubject);
    var target =
        targets(accountId).stream()
            .findFirst()
            .orElseThrow(() ->
                new AccountFailure(
                    404, "TEST_ACCOUNT_NOT_FOUND", "This test account is not available."));
    if (!StaffAccessRoleRules.allows(role, roles(target.accountId())))
      throw new AccountFailure(
          403, "TEST_SESSION_FORBIDDEN", "Your role cannot use this test account.");
    return new AccountPrincipal(
        target.accountId(), target.username(), target.displayName(), true, false, null);
  }

  /**
   * {@link com.lookahead.domain.util.StaffAccessRoleRules#role}: "owner", "co-author", "admin" (a
   * dual-role person gets the lower) or "learner" for an admitted, enabled, not disabled or deleted
   * account; "" for anyone else. Any linked sign-in (S36) counts.
   */
  private String holderRole(String subject) {
    return holderOf(subject).role();
  }

  private Holder holderOf(String subject) {
    // The subject is normalised, not branched on: an empty or oversized one is simply asked of
    // nobody, and the store's
    // answer (null for an unknown subject) decides, so no caller-controlled condition guards the
    // role read.
    String key = subject == null ? "" : subject.strip();
    var holder = key.isEmpty() || key.length() > 256 ? null : store.holder(issuer, key);
    if (holder == null) return NOBODY;
    boolean owner = holder.authorAccess();
    UUID account = holder.accountId();
    return new Holder(account, StaffAccessRoleRules.role(owner, owner ? Set.of() : roles(account)));
  }

  private Set<String> roles(UUID account) {
    return new HashSet<>(store.staffRoles(account));
  }

  private List<Target> targets(UUID only) {
    // DLV-1016: this rule was the store's. A person may sign in as a test account only when it is
    // one of ours (the test issuer), admitted and enabled.
    return store.signInCandidates(only).stream()
        .filter(c -> SignInIdentity.TEST_ISSUER.equals(c.issuer()) && c.admitted() && c.enabled())
        .map(c -> new Target(c.accountId(), c.username(), c.displayName()))
        .toList();
  }
}
