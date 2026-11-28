package com.lookahead.domain.db.store.signin;

import static com.lookahead.domain.db.store.signin.AccountItems.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.access.AccountErasureStore;
import com.lookahead.domain.db.store.access.AccountRemoval;
import com.lookahead.domain.db.store.access.AccountRemovalStore;
import com.lookahead.domain.db.store.access.DevAccessStore;
import com.lookahead.domain.db.store.access.StaffAccessStore;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipErasure;
import com.lookahead.domain.db.store.plan.StudyPlanAccountErasure;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import com.lookahead.domain.dto.DevAccessAdmission;
import com.lookahead.domain.dto.StaffAccessChange;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.job.AccountErasureJob;
import com.lookahead.domain.job.DevAccessCleanupJob;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.model.SignInModels;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountRemovalSteps;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.access.DevAccessService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.validator.AccountProfileLinkValidator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Real DynamoDB Local: first contact, linking, admission, the two sign-in slots, challenges,
 * revocation, profile, control and cleanup.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class SignInStoreDatabaseTest {
  /**
   * DLV-1016: the removal decisions are the service's; these tests run its stored steps over the
   * real store.
   */
  private static AccountRemovalSteps steps(AccountRemovalStore store) {
    return new AccountRemovalSteps(store);
  }

  static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_Synthetic",
      OWNER_SUBJECT = "owner-subject";
  private static DynamoDbClient client;
  private static final DynamoTableNames TABLES = SignInStoreLocalFixture.TABLES;
  private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
  final MutableClock clock = new MutableClock(NOW);
  SignInService signInService;
  AccountAggregateStore accounts;
  DevAccessStore devAccess;
  StaffAccessStore staff;
  StaffAccessService access;
  final AccountPrincipal owner =
      new AccountPrincipal(
          UUID.randomUUID(), "owner", "Owner", true, true, "owner@example.invalid");

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @BeforeAll
  static void connect() {
    client = SignInStoreLocalFixture.connect();
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
  }

  @BeforeEach
  void fresh() {
    SignInStoreLocalFixture.purgeLaneA(client);
    SignInStoreLocalFixture.seedOwner(client, ISSUER, OWNER_SUBJECT);
    accounts = new AccountAggregateStore(client, TABLES, clock);
    devAccess = new DevAccessStore(client, TABLES, clock);
    staff = new StaffAccessStore(client, TABLES, new ObjectMapper(), clock);
    var catalog = mock(AccountCatalogService.class);
    when(catalog.allTopicIds()).thenReturn(Set.of("learn:docker"));
    access =
        new StaffAccessService(
            staff,
            new AuthorPreviewLocalService(
                new MockEnvironment().withProperty("app.deployment-environment", "dev")),
            catalog);
    signInService = new SignInService(new SignInStore(client, TABLES, clock), access);
  }

  SignInIdentity identity(String subject, String username, String email) {
    return new SignInIdentity(
        ISSUER,
        subject,
        UUID.randomUUID().toString(),
        username,
        "Person " + subject,
        clock.instant(),
        email);
  }

  SignInIdentity same(SignInIdentity identity) {
    return new SignInIdentity(
        identity.issuer(),
        identity.subject(),
        identity.family(),
        identity.username(),
        identity.displayName(),
        clock.instant(),
        identity.verifiedEmail());
  }

  static String proof() {
    return "p".repeat(43);
  }

  static String proof(char c) {
    return String.valueOf(c).repeat(43);
  }

  static void failsWith(ThrowableAssert.ThrowingCallable call, int status, String code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            AccountFailure.class,
            f -> {
              assertThat(f.status()).isEqualTo(status);
              assertThat(f.code()).isEqualTo(code);
            });
  }

  /** First contact, then the owner admits: the account id. */
  UUID admitted(SignInIdentity identity, List<String> roles) {
    failsWith(() -> signInService.admit(identity, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
    UUID id = accounts.accountOf(identity.issuer(), identity.subject());
    new DevAccessService(devAccess, access, staff)
        .admit(owner, new DevAccessAdmission(id.toString(), roles));
    return id;
  }

  @Test
  void firstContactRecordsAWaitingAccountWithEveryProjectionAndTheOwnerAdmitsIt() {
    var identity = identity("s-1", "paula@example.invalid", "Paula@Example.invalid");
    try (var log = LogCapture.start()) {
      failsWith(() -> signInService.admit(identity, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
      assertThat(log.only("admission.waiting-created").getFormattedMessage())
          .contains("waiting row created");
      log.containsNone("paula@example.invalid", "Paula");
    }
    UUID id = accounts.accountOf(ISSUER, "s-1");
    var control = accounts.control(id);
    assertThat(control.admitted()).isFalse();
    assertThat(control.enabled()).isTrue();
    assertThat(control.author()).isFalse();
    assertThat(control.revision()).isEqualTo(1);
    assertThat(control.requestedAt()).isEqualTo(NOW);
    assertThat(control.verifiedEmail()).isEqualTo("Paula@Example.invalid");
    assertThat(accounts.identities(id))
        .singleElement()
        .satisfies(i -> {
          assertThat(i.provider()).isEqualTo("cognito");
          assertThat(i.linkSource()).isEqualTo("first_contact");
        });
    assertThat(accounts.emailAccounts("paula@example.invalid")).containsExactly(id);
    assertThat(accounts.emailRevision("paula@example.invalid")).isEqualTo(1);
    assertThat(get(client, TABLES.userAccountDetailsTableName(), WAITING, waitingSk(NOW, id)))
        .isNotEmpty();
    assertThat(
            get(client, TABLES.userAccountDetailsTableName(), account(id), "META")
                .get("revision")
                .n())
        .isEqualTo("1");
    assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(id), "ADMISSION#"))
        .singleElement()
        .satisfies(e -> assertThat(e.get("action").s()).isEqualTo("request"));
    // A second first contact of the same identity refreshes the claims and still waits.
    failsWith(
        () ->
            signInService.admit(
                identity("s-1", "paula@example.invalid", "paula@example.invalid"), proof()),
        403,
        "ACCOUNT_ADMISSION_REQUIRED");
    assertThat(accounts.control(id).verifiedEmail()).isEqualTo("paula@example.invalid");
    assertThat(accounts.emailAccounts("paula@example.invalid")).containsExactly(id);
    // Not admitted: no API call succeeds.
    failsWith(() -> signInService.authenticate(identity, proof()), 401, "AUTHENTICATION_REQUIRED");
    // The owner's waiting list, then the admission (one conditional write: the waiting projection
    // goes with it).
    var waiting = devAccess.waiting();
    assertThat(waiting)
        .singleElement()
        .satisfies(w -> {
          assertThat(w.accountId()).isEqualTo(id);
          assertThat(w.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
          assertThat(w.signInMethods()).hasSize(1);
        });
    var result =
        new DevAccessService(devAccess, access, staff)
            .admit(owner, new DevAccessAdmission(id.toString(), List.of("tester")));
    assertThat(result.roles()).containsExactly("tester");
    assertThat(accounts.control(id).admitted()).isTrue();
    assertThat(get(client, TABLES.userAccountDetailsTableName(), WAITING, waitingSk(NOW, id)))
        .isEmpty();
    assertThat(staff.forAccount(id).roles()).containsExactly("tester");
    assertThat(devAccess.waiting()).isEmpty();
    failsWith(
        () ->
            new DevAccessService(devAccess, access, staff)
                .admit(owner, new DevAccessAdmission(id.toString(), List.of())),
        409,
        "ACCOUNT_ALREADY_ADMITTED");
    // Now the sign-in is admitted into the first slot and authenticates.
    var admission = signInService.admit(identity, proof());
    assertThat(admission.signInId()).isNotNull();
    assertThat(admission.mergeAvailable()).isFalse();
    var principal = signInService.authenticate(identity, proof());
    assertThat(principal.accountId()).isEqualTo(id);
    assertThat(principal.cloudAuthor()).isFalse();
    assertThat(principal.verifiedEmail())
        .isEqualTo("Paula@Example.invalid"); // the token's own claim, as on PostgreSQL
    // The same family again returns the same sign-in; a different proof for that family is refused.
    assertThat(signInService.admit(identity, proof()).signInId()).isEqualTo(admission.signInId());
    failsWith(() -> signInService.admit(identity, proof('q')), 401, "AUTHENTICATION_REQUIRED");
  }

  @Test
  void twoSlotsThenAChallengeReplacesOneAndRevocationsEndSessions() {
    var first = identity("s-2", "two@example.invalid", "two@example.invalid");
    UUID id = admitted(first, List.of());
    var one = signInService.admit(first, proof('a'));
    clock.advance(Duration.ofSeconds(1));
    var second = identity("s-2", "two@example.invalid", "two@example.invalid");
    var two = signInService.admit(second, proof('b'));
    assertThat(signInService.inventory(first, proof('a')).entries())
        .extracting(SignInModels.Entry::signInId)
        .containsExactly(one.signInId(), two.signInId());
    // A third device: a challenge, not a sign-in; the same family asks twice and is told the
    // challenge is pending.
    clock.advance(Duration.ofSeconds(1));
    var third = identity("s-2", "two@example.invalid", "two@example.invalid");
    var challenge = signInService.admit(third, proof('c'));
    assertThat(challenge.signInId()).isNull();
    assertThat(challenge.challengeToken()).hasSize(43);
    assertThat(challenge.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
    failsWith(() -> signInService.admit(third, proof('c')), 409, "SIGN_IN_CHALLENGE_PENDING");
    var view = signInService.challenge(third, proof('c'), challenge.challengeToken());
    assertThat(view.entries()).hasSize(2);
    assertThat(view.expiresAt()).isEqualTo(challenge.expiresAt());
    failsWith(
        () ->
            signInService.replace(third, proof('c'), challenge.challengeToken(), UUID.randomUUID()),
        400,
        "SIGN_IN_CHALLENGE_INVALID");
    var replaced =
        signInService.replace(third, proof('c'), challenge.challengeToken(), one.signInId());
    assertThat(replaced.signInId()).isNotNull();
    // Replaying the same challenge gives the same answer; the replaced sign-in is dead.
    assertThat(
            signInService
                .replace(third, proof('c'), challenge.challengeToken(), one.signInId())
                .signInId())
        .isEqualTo(replaced.signInId());
    failsWith(() -> signInService.authenticate(first, proof('a')), 401, "AUTHENTICATION_REQUIRED");
    assertThat(signInService.inventory(third, proof('c')).entries())
        .extracting(SignInModels.Entry::signInId)
        .containsExactly(two.signInId(), replaced.signInId());
    // Revoke the other, then revoke-others from the survivor (the generation advances; the current
    // one is re-issued).
    assertThat(signInService.revoke(third, proof('c'), two.signInId())).isFalse();
    failsWith(() -> signInService.authenticate(second, proof('b')), 401, "AUTHENTICATION_REQUIRED");
    clock.advance(Duration.ofSeconds(1));
    var fourth = identity("s-2", "two@example.invalid", "two@example.invalid");
    signInService.admit(fourth, proof('d'));
    signInService.revokeOthers(third, proof('c'));
    assertThat(accounts.control(id).authGeneration()).isEqualTo(1);
    failsWith(() -> signInService.authenticate(fourth, proof('d')), 401, "AUTHENTICATION_REQUIRED");
    assertThat(signInService.rename(third, proof('c'), " Work laptop ").label())
        .isEqualTo("Work laptop");
    failsWith(() -> signInService.rename(third, proof('c'), "<b>"), 400, "INVALID_SIGN_IN_LABEL");
    assertThat(signInService.revoke(third, proof('c'), replaced.signInId())).isTrue();
    failsWith(() -> signInService.authenticate(third, proof('c')), 401, "AUTHENTICATION_REQUIRED");
    // A revoked family is never admitted again.
    failsWith(() -> signInService.admit(same(third), proof('c')), 401, "AUTHENTICATION_REQUIRED");
  }

  @Test
  void challengeLimitsExpiryAndCancellationAreCheckedInCode() {
    var a = identity("s-3", "three@example.invalid", null);
    admitted(a, List.of());
    signInService.admit(a, proof('a'));
    signInService.admit(identity("s-3", "three@example.invalid", null), proof('b'));
    var tokens = new ArrayList<String>();
    for (int n = 0; n < 4; n++)
      tokens.add(
          signInService
              .admit(identity("s-3", "three@example.invalid", null), proof((char) ('c' + n)))
              .challengeToken());
    // Cancel the fifth; cancelled or expired, a challenge is invalid.
    var cancelled = identity("s-3", "three@example.invalid", null);
    var token = signInService.admit(cancelled, proof('y')).challengeToken();
    signInService.cancel(cancelled, proof('y'), token);
    failsWith(
        () -> signInService.challenge(cancelled, proof('y'), token),
        400,
        "SIGN_IN_CHALLENGE_INVALID");
    var sixth = identity("s-3", "three@example.invalid", null);
    try (var log = LogCapture.start()) {
      failsWith(() -> signInService.admit(sixth, proof('z')), 429, "SIGN_IN_RATE_LIMITED");
      assertThat(log.only("sign-in.challenge").getLoggerName()).isEqualTo("lookahead.limit");
    }
    clock.advance(Duration.ofMinutes(5));
    failsWith(
        () -> signInService.challenge(sixth, proof('z'), tokens.getFirst()),
        400,
        "SIGN_IN_CHALLENGE_INVALID");
    // After the 15-minute window the limit opens again and stale challenges are deleted with the
    // new one.
    clock.advance(Duration.ofMinutes(11));
    var fresh = identity("s-3", "three@example.invalid", null);
    assertThat(signInService.admit(fresh, proof('x')).challengeToken()).isNotNull();
    UUID id = accounts.accountOf(ISSUER, "s-3");
    assertThat(query(client, TABLES.userAccountDetailsTableName(), account(id), "CHALLENGE#"))
        .hasSizeLessThan(8);
    // A recent authentication is required to admit at all.
    var stale =
        new SignInIdentity(
            ISSUER,
            "s-3",
            UUID.randomUUID().toString(),
            "three@example.invalid",
            "P",
            clock.instant().minus(Duration.ofMinutes(6)),
            null);
    failsWith(() -> signInService.admit(stale, proof('w')), 403, "RECENT_AUTHENTICATION_REQUIRED");
  }

  @Test
  void idleAndAbsoluteExpiryEndSessionsAndTheProfileWritesAreFenced() {
    var a = identity("s-4", "four@example.invalid", "four@example.invalid");
    UUID id = admitted(a, List.of());
    signInService.admit(a, proof());
    clock.advance(Duration.ofMinutes(29));
    signInService.authenticate(a, proof());
    clock.advance(Duration.ofMinutes(30));
    failsWith(() -> signInService.authenticate(a, proof()), 401, "AUTHENTICATION_REQUIRED");
    var b = identity("s-4", "four@example.invalid", "four@example.invalid");
    signInService.admit(b, proof('b'));
    var profile = signInService.profile(b, proof('b'), " Four Person ");
    assertThat(profile)
        .containsEntry("displayName", "Four Person")
        .containsEntry("email", "four@example.invalid")
        .containsEntry("signInMethod", "cognito")
        .containsEntry("themePreference", "system");
    assertThat(accounts.control(id).displayName()).isEqualTo("Four Person");
    failsWith(() -> signInService.profile(b, proof('b'), " "), 422, "INVALID_PROFILE");
    assertThat(signInService.preferences(b, proof('b'), "dark"))
        .containsEntry("themePreference", "dark");
    assertThat(signInService.preferences(b, proof('b'), "light"))
        .containsEntry("themePreference", "light");
    failsWith(() -> signInService.preferences(b, proof('b'), "blue"), 422, "INVALID_PREFERENCES");
    var links = new LinkedHashMap<String, String>();
    for (String kind : AccountProfileLinkValidator.KINDS) links.put(kind, null);
    links.put("github", "https://github.com/four");
    @SuppressWarnings("unchecked")
    var saved = (Map<String, String>) signInService.links(b, proof('b'), links).get("links");
    assertThat(saved).containsEntry("github", "https://github.com/four");
    links.put("github", "https://github.com/four-again");
    @SuppressWarnings("unchecked")
    var again = (Map<String, String>) signInService.links(b, proof('b'), links).get("links");
    assertThat(again).containsEntry("github", "https://github.com/four-again");
    // Seven days after it was created the session is gone even when active every half hour.
    for (int step = 0; step < 347; step++) {
      clock.advance(Duration.ofMinutes(29));
      signInService.authenticate(b, proof('b'));
    }
    clock.advance(Duration.ofMinutes(29));
    failsWith(() -> signInService.authenticate(b, proof('b')), 401, "AUTHENTICATION_REQUIRED");
  }

  @Test
  void aCredentialChangeBlocksAccessUntilItIsFinishedAndEndsEverySignIn() {
    var a = identity("s-5", "five@example.invalid", null);
    admitted(a, List.of());
    signInService.admit(a, proof());
    var other = identity("s-5", "five@example.invalid", null);
    signInService.admit(other, proof('o'));
    failsWith(
        () ->
            signInService.changeCredentials(
                a,
                proof(),
                () -> {
                  throw new AccountFailure(401, "INVALID_CURRENT_PASSWORD", "no");
                }),
        401,
        "INVALID_CURRENT_PASSWORD");
    signInService.authenticate(a, proof()); // a known rejection keeps the sign-ins
    signInService.changeCredentials(a, proof(), () -> {});
    failsWith(() -> signInService.authenticate(a, proof()), 401, "AUTHENTICATION_REQUIRED");
    failsWith(() -> signInService.authenticate(other, proof('o')), 401, "AUTHENTICATION_REQUIRED");
    var again = identity("s-5", "five@example.invalid", null);
    signInService.admit(again, proof('n'));
    assertThatThrownBy(() ->
            signInService.changeCredentials(
                again,
                proof('n'),
                () -> {
                  throw new IllegalStateException("provider down");
                }))
        .isInstanceOf(IllegalStateException.class);
    failsWith(() -> signInService.authenticate(again, proof('n')), 401, "AUTHENTICATION_REQUIRED");
    signInService.terminate(identity("s-5", "five@example.invalid", null), proof('n'));
  }

  @Test
  void
      aSecondIdentityWithTheSameVerifiedEmailJoinsTheOneEligibleAccountOrWaitsWhenSeveralShareIt() {
    var paula = identity("s-6", "paula@example.invalid", "paula@example.invalid");
    UUID id = admitted(paula, List.of());
    signInService.admit(paula, proof('a'));
    clock.advance(Duration.ofSeconds(1));
    var google = identity("s-6-google", "Google_42", "Paula@example.invalid");
    try (var log = LogCapture.start()) {
      var linked = signInService.admit(google, proof('g'));
      assertThat(linked.accountId()).isEqualTo(id);
      assertThat(linked.signInId()).isNotNull();
      assertThat(linked.mergeAvailable()).isFalse();
      assertThat(log.only("identity.linked").getFormattedMessage())
          .isEqualTo(
              "New google sign-in linked to account "
                  + id
                  + ": its verified email matched exactly one eligible account");
    }
    assertThat(accounts.identities(id)).hasSize(2);
    assertThat(accounts.emailRevision("paula@example.invalid")).isEqualTo(2);
    var profile = signInService.profile(google, proof('g'), null);
    assertThat(profile).containsEntry("signInMethod", "google");
    @SuppressWarnings("unchecked")
    var methods = (List<Map<String, Object>>) profile.get("signInMethods");
    assertThat(methods).extracting(m -> m.get("provider")).containsExactly("cognito", "google");
    assertThat(signInService.authenticate(google, proof('g')).accountId()).isEqualTo(id);
    // A second account made with another email signs in again carrying Paula's: two accounts share
    // it, the merge is offered.
    var second = identity("s-7", "other@example.invalid", "other@example.invalid");
    UUID other = admitted(second, List.of());
    var moved =
        signInService.admit(
            identity("s-7", "other@example.invalid", "paula@example.invalid"), proof('s'));
    assertThat(moved.accountId()).isEqualTo(other);
    assertThat(moved.mergeAvailable()).isTrue();
    assertThat(accounts.control(other).verifiedEmail()).isEqualTo("paula@example.invalid");
    assertThat(accounts.emailAccounts("other@example.invalid")).isEmpty();
    assertThat(accounts.sameEmailAccounts(id, "paula@example.invalid")).isEqualTo(1);
    var merge =
        signInService.admit(
            identity("s-6", "paula@example.invalid", "paula@example.invalid"), proof('m'));
    assertThat(merge.mergeAvailable()).isTrue();
    // Several eligible accounts share the email and all are admitted: a third identity is not
    // linked to one of them
    // but admitted as its own account at once, with no waiting row for the owner.
    try (var log = LogCapture.start()) {
      var third =
          signInService.admit(
              identity("s-8", "third@example.invalid", "paula@example.invalid"), proof('t'));
      assertThat(third.accountId()).isNotIn(id, other);
      assertThat(LogCapture.keys(log.only("identity.link-skipped")))
          .containsEntry("reason", "SEVERAL_SAME_EMAIL_ACCOUNTS")
          .containsEntry("count", 2)
          .containsEntry("allAdmitted", true);
      assertThat(log.only("admission.admitted-same-email").getFormattedMessage())
          .contains("admitted at first contact");
    }
    assertThat(accounts.emailAccounts("paula@example.invalid")).hasSize(3);
    assertThat(devAccess.waiting()).isEmpty();
    assertThat(accounts.accountOf(ISSUER, "s-8")).isNotIn(id, other);
    // All three carry the email and none is disabled: signing in to any of them offers the merge
    // (SignInService decides that).
    assertThat(accounts.emailControls("paula@example.invalid"))
        .hasSize(3)
        .allMatch(c -> c.enabled() && c.active())
        .anyMatch(c -> c.id().equals(id));
    // No verified email: never linked.
    failsWith(
        () -> signInService.admit(identity("s-9", "nine@example.invalid", null), proof('n')),
        403,
        "ACCOUNT_ADMISSION_REQUIRED");
    assertThat(accounts.identities(id)).hasSize(2);
  }

  @Test
  void ownerBindingWrittenAfterTheFirstContactAdmitsTheWaitingOwnerAtItsNextSignIn() {
    // DLV-970 DEV cutover order: the owner signed in before OWNER / ROOT was written and became an
    // ordinary waiting account.
    var late =
        identity("late-owner-subject", "late-owner@example.invalid", "late-owner@example.invalid");
    failsWith(() -> signInService.admit(late, proof('w')), 403, "ACCOUNT_ADMISSION_REQUIRED");
    UUID id = accounts.accountOf(ISSUER, "late-owner-subject");
    assertThat(accounts.control(id).admitted()).isFalse();
    assertThat(accounts.control(id).author()).isFalse();
    assertThat(devAccess.waiting())
        .singleElement()
        .satisfies(w -> assertThat(w.accountId()).isEqualTo(id));
    // The binding is written (by hand, never by the runtime); the next sign-in is the reviewed
    // admission decision.
    SignInStoreLocalFixture.seedOwner(client, ISSUER, "late-owner-subject");
    try (var log = LogCapture.start()) {
      assertThat(signInService.admit(same(late), proof('w')).signInId()).isNotNull();
      assertThat(log.only("admission.admitted-owner").getFormattedMessage())
          .contains("bound after the first contact");
    }
    var control = accounts.control(id);
    assertThat(control.admitted()).isTrue();
    assertThat(control.author()).isTrue();
    assertThat(control.admittedAt()).isEqualTo(NOW);
    assertThat(devAccess.waiting()).isEmpty();
    assertThat(get(client, TABLES.userAccountDetailsTableName(), WAITING, waitingSk(NOW, id)))
        .isEmpty();
    assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(id), "ADMISSION#"))
        .extracting(e -> e.get("action").s())
        .containsExactlyInAnyOrder("request", "admit");
    assertThat(signInService.authenticate(same(late), proof('w')).cloudAuthor()).isTrue();
    // Nothing changes for an account that is not the owner's: it still waits.
    var other = identity("s-9", "other@example.invalid", "other@example.invalid");
    failsWith(() -> signInService.admit(other, proof('x')), 403, "ACCOUNT_ADMISSION_REQUIRED");
    failsWith(
        () -> signInService.admit(same(other), proof('x')), 403, "ACCOUNT_ADMISSION_REQUIRED");
  }

  @Test
  void ownerControlDisablesEnablesAndDeletesWithSessionsEndedAndErasureResumable() {
    var ownerIdentity = identity(OWNER_SUBJECT, "owner@example.invalid", "owner@example.invalid");
    // DLV-970: the protected owner identity admits itself at first contact (nothing else could on a
    // fresh store).
    assertThat(signInService.admit(ownerIdentity, proof('o')).signInId()).isNotNull();
    UUID ownerId = accounts.accountOf(ISSUER, OWNER_SUBJECT);
    assertThat(accounts.control(ownerId).author()).isTrue();
    assertThat(accounts.control(ownerId).admitted()).isTrue();
    assertThat(signInService.authenticate(ownerIdentity, proof('o')).cloudAuthor()).isTrue();
    var learner = identity("s-10", "ten@example.invalid", "ten@example.invalid");
    UUID id = admitted(learner, List.of("reviewer"));
    signInService.admit(learner, proof());
    signInService.preferences(learner, proof(), "dark");
    var erased = new ArrayList<UUID>();
    var beans = new StaticListableBeanFactory();
    beans.addBean("plans", (StudyPlanAccountErasure) erased::add);
    beans.addBean("memberships", (MembershipErasure) account -> {});
    var removal =
        new AccountRemovalStore(
            client,
            TABLES,
            staff,
            beans.getBeanProvider(TestAccountRegistry.class),
            beans.getBeanProvider(StudyPlanAccountErasure.class),
            beans.getBeanProvider(MembershipErasure.class),
            clock);
    assertThat(removal.options(id).admitted()).isTrue();
    assertThatThrownBy(() -> steps(removal).disableAccount(ownerId, ownerId))
        .isInstanceOfSatisfying(
            AccountFailure.class, f -> assertThat(f.code()).isEqualTo("OWNER_ACCOUNT_PROTECTED"));
    assertThatThrownBy(() -> steps(removal).disableAccount(ownerId, UUID.randomUUID()))
        .isInstanceOfSatisfying(
            AccountFailure.class, f -> assertThat(f.code()).isEqualTo("SIGN_IN_ACCOUNT_NOT_FOUND"));
    var disabled = steps(removal).disableAccount(ownerId, id);
    assertThat(disabled.changed()).isTrue();
    assertThat(disabled.target().usernames()).containsExactly("ten@example.invalid");
    assertThat(steps(removal).disableAccount(ownerId, id).changed()).isFalse();
    assertThat(accounts.control(id).disabled()).isTrue();
    assertThat(accounts.control(id).authGeneration()).isEqualTo(1);
    failsWith(() -> signInService.authenticate(learner, proof()), 401, "AUTHENTICATION_REQUIRED");
    failsWith(
        () ->
            signInService.admit(
                identity("s-10", "ten@example.invalid", "ten@example.invalid"), proof('b')),
        401,
        "AUTHENTICATION_REQUIRED");
    assertThat(removal.options(id).state()).isEqualTo("disabled");
    assertThat(steps(removal).enableAccount(ownerId, id).changed()).isTrue();
    assertThat(steps(removal).enableAccount(ownerId, id).changed()).isFalse();
    failsWith(
        () -> signInService.authenticate(learner, proof()),
        401,
        "AUTHENTICATION_REQUIRED"); // the old generation stays dead
    var back = identity("s-10", "ten@example.invalid", "ten@example.invalid");
    assertThat(signInService.admit(back, proof('c')).signInId()).isNotNull();
    var removed = steps(removal).removeAccount(ownerId, id);
    assertThat(removed.changed()).isTrue();
    assertThat(accounts.control(id).removed()).isTrue();
    assertThat(erased).containsExactly(id);
    assertThat(staff.forAccount(id).roles()).isEmpty();
    assertThat(get(client, TABLES.userAccountDetailsTableName(), account(id), "PREFERENCES"))
        .isEmpty();
    assertThat(
            get(client, TABLES.userAccountDetailsTableName(), removalJobPk(id), "JOB")
                .get("phase")
                .s())
        .isEqualTo("DONE");
    assertThat(get(client, TABLES.administratorAuditEventsTableName(), account(id), "REMOVED"))
        .isNotEmpty();
    assertThat(steps(removal).removeAccount(ownerId, id).changed()).isFalse();
    assertThat(erased).hasSize(1);
    failsWith(() -> signInService.admit(back, proof('c')), 401, "AUTHENTICATION_REQUIRED");
    assertThatThrownBy(() -> steps(removal).enableAccount(ownerId, id))
        .isInstanceOfSatisfying(
            AccountFailure.class, f -> assertThat(f.code()).isEqualTo("ACCOUNT_REMOVED"));
    assertThat(removal.options(id).state()).isEqualTo("removed");
    // A waiting account (DLV-971 Reject): disabled under its own control, then deleted the way the
    // cleanup deletes.
    var waiting = identity("s-11", "eleven@example.invalid", "eleven@example.invalid");
    failsWith(() -> signInService.admit(waiting, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
    UUID waitingId = accounts.accountOf(ISSUER, "s-11");
    assertThat(steps(removal).disableAccount(ownerId, waitingId).changed()).isTrue();
    assertThat(devAccess.waiting()).isEmpty();
    assertThat(steps(removal).enableAccount(ownerId, waitingId).changed()).isTrue();
    assertThat(devAccess.waiting()).hasSize(1);
    assertThat(steps(removal).removeAccount(ownerId, waitingId).changed()).isTrue();
    assertThat(accounts.control(waitingId)).isNull();
    assertThat(accounts.accountOf(ISSUER, "s-11")).isNull();
    assertThat(accounts.emailAccounts("eleven@example.invalid")).isEmpty();
    // An interrupted erasure (the job left in its ERASE phase) is finished by a retry or a sweep,
    // never twice.
    var reopen = accounts.transaction();
    reopen.update(
        TABLES.userAccountDetailsTableName(),
        key(removalJobPk(id), "JOB"),
        Map.of("phase", s("ERASE")),
        null);
    reopen.commit();
    assertThat(removal.erasure().pending(id)).isTrue();
    assertThat(removal.erasure().pending(UUID.randomUUID())).isFalse();
    removal.erasure().erase(ownerId, id);
    assertThat(erased).hasSize(2);
    assertThat(removal.erasure().pending(id)).isFalse();
    reopen = accounts.transaction();
    reopen.update(
        TABLES.userAccountDetailsTableName(),
        key(removalJobPk(id), "JOB"),
        Map.of("phase", s("ERASE")),
        null);
    reopen.commit();
    assertThat(steps(removal).removeAccount(ownerId, id).changed()).isFalse();
    assertThat(erased).hasSize(3);
    assertThat(removal.erasure().pending(id)).isFalse();
    // A failing lane erasure is logged by the sweep and left pending for the next run.
    reopen = accounts.transaction();
    reopen.update(
        TABLES.userAccountDetailsTableName(),
        key(removalJobPk(id), "JOB"),
        Map.of("phase", s("ERASE")),
        null);
    reopen.commit();
    var failing = new StaticListableBeanFactory();
    failing.addBean(
        "plans",
        (StudyPlanAccountErasure)
            account -> {
              throw new IllegalStateException("plans down");
            });
    var broken =
        new AccountRemovalStore(
            client,
            TABLES,
            staff,
            beans.getBeanProvider(TestAccountRegistry.class),
            failing.getBeanProvider(StudyPlanAccountErasure.class),
            beans.getBeanProvider(MembershipErasure.class),
            clock);
    // DLV-1016: the sweep job catches and logs; through the real queue it reads this entry, fails
    // on it and leaves it queued and pending.
    broken.erasure().queue(ownerId, id);
    try (var log = LogCapture.start()) {
      var page = new AccountErasureJob(broken.erasure()).run(false);
      assertThat(page.failed()).isEqualTo(1);
      assertThat(log.only("account.erasure").getFormattedMessage())
          .isEqualTo(
              "Erasure of deleted account "
                  + id
                  + " did not complete (IllegalStateException); the next run tries again");
    }
    assertThat(removal.erasure().pending(id)).isTrue();
    assertThat(removal.erasure().queued(AccountErasureStore.MAX_QUEUED))
        .extracting(q -> q.id())
        .contains(id);
    removal.erasure().drop(id);
  }

  @Test
  void controlChangesWithoutTheOwnerBindingOrTheOwnerAccountAreRefusedNotGuessed() {
    var learner = identity("s-16", "sixteen@example.invalid", null);
    UUID id = admitted(learner, List.of());
    var beans = new StaticListableBeanFactory();
    var removal =
        new AccountRemovalStore(
            client,
            TABLES,
            staff,
            beans.getBeanProvider(TestAccountRegistry.class),
            beans.getBeanProvider(StudyPlanAccountErasure.class),
            beans.getBeanProvider(MembershipErasure.class),
            clock);
    // The actor has no account of its own: no owner identity to act as.
    assertThatThrownBy(() -> steps(removal).disableAccount(UUID.randomUUID(), id))
        .isInstanceOfSatisfying(
            AccountFailure.class,
            f -> {
              assertThat(f.status()).isEqualTo(503);
              assertThat(f.code()).isEqualTo("OWNER_IDENTITY_UNKNOWN");
              assertThat(f.getMessage())
                  .isEqualTo("The account state could not be changed. Retry the same action.");
            });
    // DLV-1016: the store itself reports the change as not made (no HTTP answer, no line); the
    // service above answers it.
    try (var log = LogCapture.start()) {
      assertThatThrownBy(() -> removal.disable(UUID.randomUUID(), removal.subject(id)))
          .isInstanceOfSatisfying(
              AccountRemoval.NotChanged.class,
              n -> {
                assertThat(n.reason())
                    .isEqualTo(AccountRemoval.NotChanged.Reason.OWNER_IDENTITY_UNKNOWN);
                assertThat(n.target()).isEqualTo(id);
                assertThat(n.action()).isEqualTo("disable");
                assertThat(n.getCause()).isNull();
              });
      assertThat(log.lines()).isEmpty();
    }
    // An account whose identity is not the protected owner binding is refused by the control store,
    // and the refusal is logged.
    var impostor = identity("s-17", "seventeen@example.invalid", null);
    UUID impostorId = admitted(impostor, List.of());
    try (var log = LogCapture.start()) {
      assertThatThrownBy(() -> steps(removal).removeAccount(impostorId, id))
          .isInstanceOfSatisfying(
              AccountFailure.class,
              f -> {
                assertThat(f.code()).isEqualTo("ACCOUNT_CONTROL_REFUSED");
                assertThat(f.status()).isEqualTo(503);
                assertThat(f.getMessage())
                    .isEqualTo("The account state could not be changed. Retry the same action.");
              });
      var line = log.only("account.remove");
      assertThat(LogCapture.keys(line))
          .containsEntry("reason", "ACCOUNT_CONTROL_REFUSED")
          .containsEntry("event.outcome", "failure")
          .containsEntry("target.id", id)
          .containsEntry("error.type", "IllegalStateException");
      assertThat(line.getFormattedMessage())
          .isEqualTo(
              "Account " + id + " remove refused by the control store (Owner identity required)");
      assertThat(line.getLevel()).isEqualTo(Level.WARN);
    }
    try (var log = LogCapture.start()) {
      assertThatThrownBy(() -> removal.disable(impostorId, removal.subject(id)))
          .isInstanceOfSatisfying(
              AccountRemoval.NotChanged.class,
              n -> {
                assertThat(n.reason())
                    .isEqualTo(AccountRemoval.NotChanged.Reason.ACCOUNT_CONTROL_REFUSED);
                assertThat(n.action()).isEqualTo("disable");
                assertThat(n.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Owner identity required");
              });
      assertThat(log.lines()).isEmpty();
    }
    assertThat(accounts.control(id).active()).isTrue();
    assertThat(accounts.control(id).admitted()).isTrue();
    // A managed test account (lane C's registry) takes the test-account route.
    var registry = new StaticListableBeanFactory();
    registry.addBean("tests", (TestAccountRegistry) account -> account.equals(id));
    var guarded =
        new AccountRemovalStore(
            client,
            TABLES,
            staff,
            registry.getBeanProvider(TestAccountRegistry.class),
            beans.getBeanProvider(StudyPlanAccountErasure.class),
            beans.getBeanProvider(MembershipErasure.class),
            clock);
    assertThatThrownBy(() -> steps(guarded).enableAccount(impostorId, id))
        .isInstanceOfSatisfying(
            AccountFailure.class,
            f -> assertThat(f.code()).isEqualTo("TEST_ACCOUNT_ROUTE_REQUIRED"));
  }

  @Test
  void theCleanupExpiresOnlyUntouchedWaitingAccountsOlderThanFortyEightHours() {
    var old = identity("s-12", "twelve@example.invalid", "twelve@example.invalid");
    failsWith(() -> signInService.admit(old, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
    UUID oldId = accounts.accountOf(ISSUER, "s-12");
    clock.advance(Duration.ofHours(48));
    var young = identity("s-13", "thirteen@example.invalid", null);
    failsWith(() -> signInService.admit(young, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
    var kept = identity("s-14", "fourteen@example.invalid", null);
    UUID keptId = admitted(kept, List.of());
    var cleanup = new DevAccessCleanupJob(new DevAccessCleanupStore(client, TABLES, clock));
    assertThat(cleanup.run()).isZero(); // exactly 48 h is not yet expired
    clock.advance(Duration.ofSeconds(1));
    try (var log = LogCapture.start()) {
      assertThat(cleanup.run()).isEqualTo(1);
      assertThat(log.only("admission.expired").getFormattedMessage())
          .isEqualTo(
              "Waiting account "
                  + oldId
                  + " expired: not admitted within 48 h, removed with its sign-in identities");
      assertThat(LogCapture.keys(log.only("admission.cleanup"))).containsEntry("count", 1);
    }
    assertThat(accounts.control(oldId)).isNull();
    assertThat(accounts.accountOf(ISSUER, "s-12")).isNull();
    assertThat(accounts.emailAccounts("twelve@example.invalid")).isEmpty();
    assertThat(accounts.control(keptId).admitted()).isTrue();
    assertThat(accounts.accountOf(ISSUER, "s-13")).isNotNull();
    assertThat(
            query(client, TABLES.administratorAuditEventsTableName(), account(oldId), "ADMISSION#"))
        .extracting(e -> e.get("action").s())
        .containsExactly("request", "expire");
    assertThat(cleanup.run()).isZero();
  }

  /**
   * DLV-1016: the cleanup's other cases against DynamoDB, through the job (the same calls work on
   * the code before the job/store split).
   */
  @Test
  void theCleanupDropsRowsLeftBehindKeepsADisabledWaitingAccountAndSkipsALostRace() {
    String table = TABLES.userAccountDetailsTableName();
    // Two rows left behind: one whose account is gone, one whose account was admitted.
    UUID ghost = UUID.randomUUID();
    client.putItem(put ->
        put.tableName(table)
            .item(
                item(
                    WAITING,
                    waitingSk(NOW, ghost),
                    attrs("accountId", s(ghost.toString()), "requestedAt", time(NOW)))));
    UUID admittedId = admitted(identity("s-20", "twenty@example.invalid", null), List.of());
    client.putItem(put ->
        put.tableName(table)
            .item(
                item(
                    WAITING,
                    waitingSk(NOW, admittedId),
                    attrs("accountId", s(admittedId.toString()), "requestedAt", time(NOW)))));
    // A waiting account the owner disabled, one that changes under the cleanup, and one that simply
    // expires.
    failsWith(
        () -> signInService.admit(identity("s-21", "twentyone@example.invalid", null), proof()),
        403,
        "ACCOUNT_ADMISSION_REQUIRED");
    UUID heldId = accounts.accountOf(ISSUER, "s-21");
    DynamoWrites.transaction(client)
        .update(table, key(account(heldId), "CONTROL"), Map.of("accountState", s("DISABLED")), null)
        .commit();
    failsWith(
        () -> signInService.admit(identity("s-22", "twentytwo@example.invalid", null), proof()),
        403,
        "ACCOUNT_ADMISSION_REQUIRED");
    UUID racedId = accounts.accountOf(ISSUER, "s-22");
    failsWith(
        () ->
            signInService.admit(
                identity("s-23", "twentythree@example.invalid", "twentythree@example.invalid"),
                proof()),
        403,
        "ACCOUNT_ADMISSION_REQUIRED");
    UUID plainId = accounts.accountOf(ISSUER, "s-23");
    clock.advance(Duration.ofHours(48).plusSeconds(1));
    // Another writer moves the raced account's revision between the cleanup's read and its
    // transaction.
    boolean[] moved = {false};
    var racing =
        (DynamoDbClient)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                  if (!moved[0]
                      && args != null
                      && args.length == 1
                      && args[0] instanceof ExecuteTransactionRequest tx
                      && tx.transactStatements().stream()
                          .anyMatch(st ->
                              st.statement().startsWith("DELETE")
                                  && st.parameters().stream()
                                      .anyMatch(v -> account(racedId).equals(v.s()))
                                  && st.parameters().stream()
                                      .anyMatch(v -> "CONTROL".equals(v.s())))) {
                    moved[0] = true;
                    DynamoWrites.transaction(client)
                        .update(
                            table, key(account(racedId), "CONTROL"), Map.of("revision", n(2)), null)
                        .commit();
                  }
                  try {
                    return method.invoke(client, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    try (var log = LogCapture.start()) {
      assertThat(new DevAccessCleanupJob(new DevAccessCleanupStore(racing, TABLES, clock)).run())
          .isEqualTo(1);
      assertThat(log.only("admission.expired").getFormattedMessage())
          .isEqualTo(
              "Waiting account "
                  + plainId
                  + " expired: not admitted within 48 h, removed with its sign-in identities");
      assertThat(LogCapture.keys(log.only("admission.cleanup"))).containsEntry("count", 1);
    }
    assertThat(moved[0]).isTrue();
    assertThat(get(client, table, WAITING, waitingSk(NOW, ghost))).isEmpty();
    assertThat(get(client, table, WAITING, waitingSk(NOW, admittedId))).isEmpty();
    assertThat(accounts.control(admittedId).admitted()).isTrue();
    assertThat(accounts.control(heldId).disabled()).isTrue();
    assertThat(get(client, table, WAITING, waitingSk(NOW, heldId))).isNotEmpty();
    assertThat(accounts.control(racedId).revision()).isEqualTo(2);
    assertThat(get(client, table, WAITING, waitingSk(NOW, racedId))).isNotEmpty();
    assertThat(accounts.control(plainId)).isNull();
    assertThat(
            query(
                client, TABLES.administratorAuditEventsTableName(), account(racedId), "ADMISSION#"))
        .extracting(e -> e.get("action").s())
        .containsExactly("request");
    // Nothing was retried in that run; the next run removes the account that had moved.
    assertThat(new DevAccessCleanupJob(new DevAccessCleanupStore(client, TABLES, clock)).run())
        .isEqualTo(1);
    assertThat(accounts.control(racedId)).isNull();
  }

  @Test
  void preparedAccessByEmailAdmitsAFirstContactAtOnce() {
    access.save(
        owner,
        new StaffAccessChange(
            UUID.randomUUID(),
            null,
            "Invited@Example.invalid",
            Set.of("admin"),
            Set.of(),
            false,
            0));
    var invited = identity("s-15", "invited@example.invalid", "invited@example.invalid");
    try (var log = LogCapture.start()) {
      var admission = signInService.admit(invited, proof());
      assertThat(admission.signInId()).isNotNull();
      assertThat(log.only("admission.admitted-by-invitation").getFormattedMessage())
          .contains("roles [admin]");
    }
    UUID id = admission(invited);
    assertThat(accounts.control(id).admitted()).isTrue();
    assertThat(staff.forAccount(id).roles()).containsExactly("admin");
    assertThat(
            StaffAccessService.liveInvitation(
                staff.pending("invited@example.invalid"), "invited@example.invalid"))
        .isFalse();
    assertThat(devAccess.waiting()).isEmpty();
  }

  private UUID admission(SignInIdentity identity) {
    return accounts.accountOf(identity.issuer(), identity.subject());
  }

  /** DLV-1016: the two sign-in writes no other database test reached. */
  @Test
  void aNewFamilyWithAnActiveSignInsProofTakesItsPlaceAndAnAdmittedOwnerBecomesAnAuthor() {
    var person = identity("s-40", "forty@example.invalid", "forty@example.invalid");
    UUID id = admitted(person, List.of());
    UUID first = signInService.admit(person, proof('a')).signInId();
    clock.advance(Duration.ofSeconds(1));
    // A new token family with the proof of the active sign-in: that sign-in ends and the new one
    // takes its place.
    var again = identity("s-40", "forty@example.invalid", "forty@example.invalid");
    var reissued = signInService.admit(again, proof('a'));
    assertThat(reissued.signInId()).isNotNull().isNotEqualTo(first);
    assertThat(signInService.inventory(again, proof('a')).entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.signInId()).isEqualTo(reissued.signInId()));
    // The first family keeps its binding: an ended family is never admitted again.
    failsWith(() -> signInService.admit(same(person), proof('a')), 401, "AUTHENTICATION_REQUIRED");
    assertThat(accounts.control(id).author()).isFalse();
    // The owner binding is written for this identity (by hand, never by the runtime): its next
    // sign-in records author access.
    SignInStoreLocalFixture.seedOwner(client, ISSUER, "s-40");
    clock.advance(Duration.ofSeconds(1));
    var owned = identity("s-40", "forty@example.invalid", "forty@example.invalid");
    assertThat(signInService.admit(owned, proof('b')).signInId()).isNotNull();
    assertThat(accounts.control(id).author()).isTrue();
    assertThat(signInService.authenticate(owned, proof('b')).cloudAuthor()).isTrue();
  }

  /**
   * DLV-1016: a client that lets {@code other} write just before a transaction {@code when}
   * recognises.
   */
  DynamoDbClient racingClient(
      Predicate<ExecuteTransactionRequest> when, Consumer<ExecuteTransactionRequest> other) {
    return (DynamoDbClient)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {DynamoDbClient.class},
            (proxy, method, args) -> {
              if (args != null
                  && args.length == 1
                  && args[0] instanceof ExecuteTransactionRequest tx
                  && when.test(tx)) other.accept(tx);
              try {
                return method.invoke(client, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  /** Another writer of the account: its control revision moves on. */
  void moveRevision(UUID id) {
    long revision = accounts.control(id).revision();
    DynamoWrites.transaction(client)
        .update(
            TABLES.userAccountDetailsTableName(),
            key(account(id), "CONTROL"),
            Map.of("revision", n(revision + 1)),
            null)
        .commit();
  }

  /**
   * DLV-1016: every unit of a known account is fenced on the control revision it read; a lost race
   * runs it again from its reads, three attempts in all.
   */
  @Test
  void aUnitThatLosesTheRevisionRaceRunsAgainFromItsReadsAndGivesUpAfterThreeAttempts() {
    var person = identity("s-30", "thirty@example.invalid", "thirty@example.invalid");
    UUID id = admitted(person, List.of());
    UUID first = signInService.admit(person, proof('a')).signInId();
    int[] seen = {0}, lose = {1};
    var raced =
        new SignInService(
            new SignInStore(
                racingClient(
                    tx ->
                        tx.transactStatements().stream()
                            .anyMatch(st ->
                                st.parameters().stream().anyMatch(v -> account(id).equals(v.s()))
                                    && st.parameters().stream()
                                        .anyMatch(v -> "CONTROL".equals(v.s()))),
                    tx -> {
                      seen[0]++;
                      if (seen[0] <= lose[0]) moveRevision(id);
                    }),
                TABLES,
                clock),
            null);
    // One lost race each: a label (the control only checked), a display name (the control written),
    // a second sign-in, a challenge, its replacement.
    assertThat(raced.rename(person, proof('a'), "Laptop").label()).isEqualTo("Laptop");
    assertThat(seen[0]).isEqualTo(2);
    seen[0] = 0;
    assertThat(raced.profile(person, proof('a'), " Thirty "))
        .containsEntry("displayName", "Thirty");
    assertThat(seen[0]).isEqualTo(2);
    seen[0] = 0;
    clock.advance(Duration.ofSeconds(1));
    var second = identity("s-30", "thirty@example.invalid", "thirty@example.invalid");
    assertThat(raced.admit(second, proof('b')).signInId()).isNotNull();
    assertThat(seen[0]).isEqualTo(2);
    seen[0] = 0;
    var third = identity("s-30", "thirty@example.invalid", "thirty@example.invalid");
    var challenge = raced.admit(third, proof('c'));
    assertThat(challenge.challengeToken()).isNotNull();
    assertThat(seen[0]).isEqualTo(2);
    seen[0] = 0;
    var replaced = raced.replace(third, proof('c'), challenge.challengeToken(), first);
    assertThat(replaced.signInId()).isNotNull();
    assertThat(seen[0]).isEqualTo(2);
    seen[0] = 0;
    assertThat(signInService.inventory(third, proof('c')).entries())
        .extracting(SignInModels.Entry::signInId)
        .containsExactlyInAnyOrder(
            replaced.signInId(),
            signInService.inventory(second, proof('b')).entries().stream()
                .filter(SignInModels.Entry::current)
                .findFirst()
                .orElseThrow()
                .signInId());
    failsWith(() -> signInService.authenticate(person, proof('a')), 401, "AUTHENTICATION_REQUIRED");
    // Every attempt lost: the conflict surfaces after three attempts and nothing is written.
    lose[0] = 3;
    long before = accounts.control(id).revision();
    assertThatThrownBy(() -> raced.rename(second, proof('b'), "Phone"))
        .isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(seen[0]).isEqualTo(3);
    seen[0] = 0;
    assertThatThrownBy(() -> raced.revokeOthers(second, proof('b')))
        .isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(seen[0]).isEqualTo(3);
    seen[0] = 0;
    assertThatThrownBy(() -> raced.terminate(second, proof('b')))
        .isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(seen[0]).isEqualTo(3);
    assertThat(accounts.control(id).revision()).isEqualTo(before + 9);
    assertThat(accounts.control(id).authGeneration()).isZero();
    assertThat(signInService.inventory(second, proof('b')).entries())
        .hasSize(2)
        .noneMatch(entry -> "Phone".equals(entry.label()));
  }

  /**
   * DLV-1016: a first contact is serialised by its email's sentinel; a lost race reads again, three
   * attempts in all.
   */
  @Test
  void aFirstContactThatLosesTheEmailRaceReadsAgainAndGivesUpAfterThreeAttempts() {
    String email = "racing@example.invalid", next = "racing-next@example.invalid";
    String[] contested = {email};
    int[] seen = {0}, lose = {1};
    var raced =
        new SignInService(
            new SignInStore(
                racingClient(
                    tx ->
                        tx.transactStatements().stream()
                            .anyMatch(st ->
                                st.statement().startsWith("INSERT")
                                    && st.parameters().stream()
                                        .anyMatch(v -> "CONTROL".equals(v.s()))),
                    tx -> {
                      seen[0]++;
                      if (seen[0] <= lose[0]) {
                        var other = accounts.transaction();
                        accounts.emailSentinel(
                            other, contested[0], accounts.emailRevision(contested[0]));
                        other.commit();
                      }
                    }),
                TABLES,
                clock),
            null);
    try (var log = LogCapture.start()) {
      failsWith(
          () -> raced.admit(identity("s-31", "racing@example.invalid", email), proof()),
          403,
          "ACCOUNT_ADMISSION_REQUIRED");
      assertThat(seen[0]).isEqualTo(2);
      assertThat(log.lines("admission.waiting-created")).hasSize(1);
    }
    UUID id = accounts.accountOf(ISSUER, "s-31");
    assertThat(accounts.emailAccounts(email)).containsExactly(id);
    assertThat(accounts.emailRevision(email)).isEqualTo(2);
    assertThat(devAccess.waiting()).hasSize(1);
    // Every attempt lost: the conflict surfaces, and no account, binding or waiting row was made.
    seen[0] = 0;
    lose[0] = 3;
    contested[0] = next;
    assertThatThrownBy(
            () -> raced.admit(identity("s-32", "racing-next@example.invalid", next), proof()))
        .isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(seen[0]).isEqualTo(3);
    assertThat(accounts.accountOf(ISSUER, "s-32")).isNull();
    assertThat(accounts.emailAccounts(next)).isEmpty();
    assertThat(accounts.emailRevision(next)).isEqualTo(3);
    assertThat(devAccess.waiting()).hasSize(1);
  }

  /**
   * DLV-1016: prepared access is claimed inside the first-contact attempt; when the admission that
   * follows loses a race, the attempt runs again and the account waits.
   */
  @Test
  void aPreparedAdmissionThatLosesItsRaceLeavesTheAccountWaitingWithItsAccessClaimed() {
    access.save(
        owner,
        new StaffAccessChange(
            UUID.randomUUID(),
            null,
            "claimed@example.invalid",
            Set.of("tester"),
            Set.of(),
            false,
            0));
    int[] seen = {0};
    var raced =
        new SignInService(
            new SignInStore(
                racingClient(
                    tx ->
                        tx.transactStatements().stream()
                            .anyMatch(st ->
                                st.statement().startsWith("UPDATE")
                                    && st.statement().contains("\"admittedAt\"")
                                    && st.parameters().stream()
                                        .anyMatch(v -> "CONTROL".equals(v.s()))),
                    tx -> {
                      seen[0]++;
                      moveRevision(accounts.accountOf(ISSUER, "s-33"));
                    }),
                TABLES,
                clock),
            access);
    try (var log = LogCapture.start()) {
      failsWith(
          () ->
              raced.admit(
                  identity("s-33", "claimed@example.invalid", "claimed@example.invalid"), proof()),
          403,
          "ACCOUNT_ADMISSION_REQUIRED");
      assertThat(seen[0]).isEqualTo(1);
      assertThat(log.lines("admission.admitted-by-invitation")).isEmpty();
      assertThat(log.lines("admission.waiting-refreshed")).hasSize(1);
      assertThat(log.lines("access.invitation-claimed")).hasSize(1);
    }
    UUID id = accounts.accountOf(ISSUER, "s-33");
    assertThat(accounts.control(id).admitted()).isFalse();
    assertThat(staff.forAccount(id).roles()).containsExactly("tester");
    assertThat(
            StaffAccessService.liveInvitation(
                staff.pending("claimed@example.invalid"), "claimed@example.invalid"))
        .isFalse();
    assertThat(devAccess.waiting()).extracting(w -> w.accountId()).containsExactly(id);
    // The owner admits it as any waiting account; the claimed access is its access.
    new DevAccessService(devAccess, access, staff)
        .admit(owner, new DevAccessAdmission(id.toString(), List.of("tester")));
    assertThat(
            signInService
                .admit(
                    identity("s-33", "claimed@example.invalid", "claimed@example.invalid"), proof())
                .signInId())
        .isNotNull();
  }
}
