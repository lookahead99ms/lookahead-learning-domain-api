package com.lookahead.domain.cloud;

import static com.lookahead.domain.cloud.CloudAccountItems.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.access.AccountAdmissionService;
import com.lookahead.domain.access.DynamoAccountRemovalStore;
import com.lookahead.domain.access.DynamoAdmissionStore;
import com.lookahead.domain.access.DynamoStaffAccessRepository;
import com.lookahead.domain.access.LearningDataErasure;
import com.lookahead.domain.access.MembershipErasure;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.TestAccountRegistryLookup;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;

/** Real DynamoDB Local: first contact, linking, admission, the two sign-in slots, challenges, revocation, profile, control and cleanup. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoCloudSignInRegistryDatabaseTest {
    static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_Synthetic", OWNER_SUBJECT = "owner-subject";
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoLocalFixture.TABLES;
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    final MutableClock clock = new MutableClock(NOW);
    DynamoCloudSignInRegistry registry;
    DynamoCloudAccounts accounts;
    DynamoAdmissionStore admissions;
    DynamoStaffAccessRepository staff;
    StaffAccessService access;
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true, "owner@example.invalid");

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void fresh() {
        DynamoLocalFixture.purgeLaneA(client);
        DynamoLocalFixture.seedOwner(client, ISSUER, OWNER_SUBJECT);
        registry = new DynamoCloudSignInRegistry(client, TABLES, clock);
        accounts = new DynamoCloudAccounts(client, TABLES, clock);
        admissions = new DynamoAdmissionStore(client, TABLES, clock);
        staff = new DynamoStaffAccessRepository(client, TABLES, new ObjectMapper(), clock);
        var catalog = mock(SnapshotValidator.class); when(catalog.allTopicIds()).thenReturn(Set.of("learn:docker"));
        access = new StaffAccessService(staff, new LocalAuthorAccess(new MockEnvironment().withProperty("app.deployment-environment", "dev")), catalog);
        registry.configureStaffAccess(access);
    }

    CognitoIdentity identity(String subject, String username, String email) { return new CognitoIdentity(ISSUER, subject, UUID.randomUUID().toString(), username, "Person " + subject, clock.instant(), email); }
    CognitoIdentity same(CognitoIdentity identity) { return new CognitoIdentity(identity.issuer(), identity.subject(), identity.family(), identity.username(), identity.displayName(), clock.instant(), identity.verifiedEmail()); }
    static String proof() { return "p".repeat(43); }
    static String proof(char c) { return String.valueOf(c).repeat(43); }
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, f -> { assertThat(f.status()).isEqualTo(status); assertThat(f.code()).isEqualTo(code); });
    }
    /** First contact, then the owner admits: the account id. */
    UUID admitted(CognitoIdentity identity, List<String> roles) {
        failsWith(() -> registry.admit(identity, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
        UUID id = accounts.accountOf(identity.issuer(), identity.subject());
        new AccountAdmissionService(admissions, access, staff).admit(owner, new AccountAdmissionService.Request(id.toString(), roles));
        return id;
    }

    @Test void firstContactRecordsAWaitingAccountWithEveryProjectionAndTheOwnerAdmitsIt() {
        var identity = identity("s-1", "paula@example.invalid", "Paula@Example.invalid");
        try (var log = LogCapture.start()) {
            failsWith(() -> registry.admit(identity, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
            assertThat(log.only("admission.waiting-created").getFormattedMessage()).contains("waiting row created");
            log.containsNone("paula@example.invalid", "Paula");
        }
        UUID id = accounts.accountOf(ISSUER, "s-1");
        var control = accounts.control(id);
        assertThat(control.admitted()).isFalse(); assertThat(control.enabled()).isTrue(); assertThat(control.author()).isFalse();
        assertThat(control.revision()).isEqualTo(1); assertThat(control.requestedAt()).isEqualTo(NOW); assertThat(control.verifiedEmail()).isEqualTo("Paula@Example.invalid");
        assertThat(accounts.identities(id)).singleElement().satisfies(i -> { assertThat(i.provider()).isEqualTo("cognito"); assertThat(i.linkSource()).isEqualTo("first_contact"); });
        assertThat(accounts.emailAccounts("paula@example.invalid")).containsExactly(id);
        assertThat(accounts.emailRevision("paula@example.invalid")).isEqualTo(1);
        assertThat(get(client, TABLES.userAccountDetailsTableName(), WAITING, waitingSk(NOW, id))).isNotEmpty();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), account(id), "META").get("revision").n()).isEqualTo("1");
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(id), "ADMISSION#")).singleElement().satisfies(e -> assertThat(e.get("action").s()).isEqualTo("request"));
        // A second first contact of the same identity refreshes the claims and still waits.
        failsWith(() -> registry.admit(identity("s-1", "paula@example.invalid", "paula@example.invalid"), proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
        assertThat(accounts.control(id).verifiedEmail()).isEqualTo("paula@example.invalid");
        assertThat(accounts.emailAccounts("paula@example.invalid")).containsExactly(id);
        // Not admitted: no API call succeeds.
        failsWith(() -> registry.authenticate(identity, proof()), 401, "AUTHENTICATION_REQUIRED");
        // The owner's waiting list, then the admission (one conditional write: the waiting projection goes with it).
        var waiting = admissions.waiting();
        assertThat(waiting).singleElement().satisfies(w -> { assertThat(w.accountId()).isEqualTo(id); assertThat(w.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48))); assertThat(w.signInMethods()).hasSize(1); });
        var result = new AccountAdmissionService(admissions, access, staff).admit(owner, new AccountAdmissionService.Request(id.toString(), List.of("tester")));
        assertThat(result.roles()).containsExactly("tester");
        assertThat(accounts.control(id).admitted()).isTrue();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), WAITING, waitingSk(NOW, id))).isEmpty();
        assertThat(staff.forAccount(id).roles()).containsExactly("tester");
        assertThat(admissions.waiting()).isEmpty();
        failsWith(() -> new AccountAdmissionService(admissions, access, staff).admit(owner, new AccountAdmissionService.Request(id.toString(), List.of())), 409, "ACCOUNT_ALREADY_ADMITTED");
        // Now the sign-in is admitted into the first slot and authenticates.
        var admission = registry.admit(identity, proof());
        assertThat(admission.signInId()).isNotNull(); assertThat(admission.mergeAvailable()).isFalse();
        var principal = registry.authenticate(identity, proof());
        assertThat(principal.accountId()).isEqualTo(id); assertThat(principal.cloudAuthor()).isFalse(); assertThat(principal.verifiedEmail()).isEqualTo("Paula@Example.invalid"); // the token's own claim, as on PostgreSQL
        // The same family again returns the same sign-in; a different proof for that family is refused.
        assertThat(registry.admit(identity, proof()).signInId()).isEqualTo(admission.signInId());
        failsWith(() -> registry.admit(identity, proof('q')), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test void twoSlotsThenAChallengeReplacesOneAndRevocationsEndSessions() {
        var first = identity("s-2", "two@example.invalid", "two@example.invalid");
        UUID id = admitted(first, List.of());
        var one = registry.admit(first, proof('a'));
        clock.advance(Duration.ofSeconds(1));
        var second = identity("s-2", "two@example.invalid", "two@example.invalid");
        var two = registry.admit(second, proof('b'));
        assertThat(registry.inventory(first, proof('a')).entries()).extracting(CloudSignInRegistry.Entry::signInId).containsExactly(one.signInId(), two.signInId());
        // A third device: a challenge, not a sign-in; the same family asks twice and is told the challenge is pending.
        clock.advance(Duration.ofSeconds(1));
        var third = identity("s-2", "two@example.invalid", "two@example.invalid");
        var challenge = registry.admit(third, proof('c'));
        assertThat(challenge.signInId()).isNull(); assertThat(challenge.challengeToken()).hasSize(43); assertThat(challenge.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        failsWith(() -> registry.admit(third, proof('c')), 409, "SIGN_IN_CHALLENGE_PENDING");
        var view = registry.challenge(third, proof('c'), challenge.challengeToken());
        assertThat(view.entries()).hasSize(2); assertThat(view.expiresAt()).isEqualTo(challenge.expiresAt());
        failsWith(() -> registry.replace(third, proof('c'), challenge.challengeToken(), UUID.randomUUID()), 400, "SIGN_IN_CHALLENGE_INVALID");
        var replaced = registry.replace(third, proof('c'), challenge.challengeToken(), one.signInId());
        assertThat(replaced.signInId()).isNotNull();
        // Replaying the same challenge gives the same answer; the replaced sign-in is dead.
        assertThat(registry.replace(third, proof('c'), challenge.challengeToken(), one.signInId()).signInId()).isEqualTo(replaced.signInId());
        failsWith(() -> registry.authenticate(first, proof('a')), 401, "AUTHENTICATION_REQUIRED");
        assertThat(registry.inventory(third, proof('c')).entries()).extracting(CloudSignInRegistry.Entry::signInId).containsExactly(two.signInId(), replaced.signInId());
        // Revoke the other, then revoke-others from the survivor (the generation advances; the current one is re-issued).
        assertThat(registry.revoke(third, proof('c'), two.signInId())).isFalse();
        failsWith(() -> registry.authenticate(second, proof('b')), 401, "AUTHENTICATION_REQUIRED");
        clock.advance(Duration.ofSeconds(1));
        var fourth = identity("s-2", "two@example.invalid", "two@example.invalid");
        registry.admit(fourth, proof('d'));
        registry.revokeOthers(third, proof('c'));
        assertThat(accounts.control(id).authGeneration()).isEqualTo(1);
        failsWith(() -> registry.authenticate(fourth, proof('d')), 401, "AUTHENTICATION_REQUIRED");
        assertThat(registry.rename(third, proof('c'), " Work laptop ").label()).isEqualTo("Work laptop");
        failsWith(() -> registry.rename(third, proof('c'), "<b>"), 400, "INVALID_SIGN_IN_LABEL");
        assertThat(registry.revoke(third, proof('c'), replaced.signInId())).isTrue();
        failsWith(() -> registry.authenticate(third, proof('c')), 401, "AUTHENTICATION_REQUIRED");
        // A revoked family is never admitted again.
        failsWith(() -> registry.admit(same(third), proof('c')), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test void challengeLimitsExpiryAndCancellationAreCheckedInCode() {
        var a = identity("s-3", "three@example.invalid", null);
        admitted(a, List.of());
        registry.admit(a, proof('a')); registry.admit(identity("s-3", "three@example.invalid", null), proof('b'));
        var tokens = new ArrayList<String>();
        for (int n = 0; n < 4; n++) tokens.add(registry.admit(identity("s-3", "three@example.invalid", null), proof((char) ('c' + n))).challengeToken());
        // Cancel the fifth; cancelled or expired, a challenge is invalid.
        var cancelled = identity("s-3", "three@example.invalid", null);
        var token = registry.admit(cancelled, proof('y')).challengeToken();
        registry.cancel(cancelled, proof('y'), token);
        failsWith(() -> registry.challenge(cancelled, proof('y'), token), 400, "SIGN_IN_CHALLENGE_INVALID");
        var sixth = identity("s-3", "three@example.invalid", null);
        try (var log = LogCapture.start()) {
            failsWith(() -> registry.admit(sixth, proof('z')), 429, "SIGN_IN_RATE_LIMITED");
            assertThat(log.only("sign-in.challenge").getLoggerName()).isEqualTo("lookahead.limit");
        }
        clock.advance(Duration.ofMinutes(5));
        failsWith(() -> registry.challenge(sixth, proof('z'), tokens.getFirst()), 400, "SIGN_IN_CHALLENGE_INVALID");
        // After the 15-minute window the limit opens again and stale challenges are deleted with the new one.
        clock.advance(Duration.ofMinutes(11));
        var fresh = identity("s-3", "three@example.invalid", null);
        assertThat(registry.admit(fresh, proof('x')).challengeToken()).isNotNull();
        UUID id = accounts.accountOf(ISSUER, "s-3");
        assertThat(query(client, TABLES.userAccountDetailsTableName(), account(id), "CHALLENGE#")).hasSizeLessThan(8);
        // A recent authentication is required to admit at all.
        var stale = new CognitoIdentity(ISSUER, "s-3", UUID.randomUUID().toString(), "three@example.invalid", "P", clock.instant().minus(Duration.ofMinutes(6)), null);
        failsWith(() -> registry.admit(stale, proof('w')), 403, "RECENT_AUTHENTICATION_REQUIRED");
    }

    @Test void idleAndAbsoluteExpiryEndSessionsAndTheProfileWritesAreFenced() {
        var a = identity("s-4", "four@example.invalid", "four@example.invalid");
        UUID id = admitted(a, List.of());
        registry.admit(a, proof());
        clock.advance(Duration.ofMinutes(29)); registry.authenticate(a, proof());
        clock.advance(Duration.ofMinutes(30));
        failsWith(() -> registry.authenticate(a, proof()), 401, "AUTHENTICATION_REQUIRED");
        var b = identity("s-4", "four@example.invalid", "four@example.invalid");
        registry.admit(b, proof('b'));
        var profile = registry.profile(b, proof('b'), " Four Person ");
        assertThat(profile).containsEntry("displayName", "Four Person").containsEntry("email", "four@example.invalid").containsEntry("signInMethod", "cognito").containsEntry("themePreference", "system");
        assertThat(accounts.control(id).displayName()).isEqualTo("Four Person");
        failsWith(() -> registry.profile(b, proof('b'), " "), 422, "INVALID_PROFILE");
        assertThat(registry.preferences(b, proof('b'), "dark")).containsEntry("themePreference", "dark");
        assertThat(registry.preferences(b, proof('b'), "light")).containsEntry("themePreference", "light");
        failsWith(() -> registry.preferences(b, proof('b'), "blue"), 422, "INVALID_PREFERENCES");
        var links = new LinkedHashMap<String, String>(); for (String kind : ProfileLinkPolicy.KINDS) links.put(kind, null); links.put("github", "https://github.com/four");
        @SuppressWarnings("unchecked") var saved = (Map<String, String>) registry.links(b, proof('b'), links).get("links");
        assertThat(saved).containsEntry("github", "https://github.com/four");
        links.put("github", "https://github.com/four-again");
        @SuppressWarnings("unchecked") var again = (Map<String, String>) registry.links(b, proof('b'), links).get("links");
        assertThat(again).containsEntry("github", "https://github.com/four-again");
        // Seven days after it was created the session is gone even when active every half hour.
        for (int step = 0; step < 347; step++) { clock.advance(Duration.ofMinutes(29)); registry.authenticate(b, proof('b')); }
        clock.advance(Duration.ofMinutes(29));
        failsWith(() -> registry.authenticate(b, proof('b')), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test void aCredentialChangeBlocksAccessUntilItIsFinishedAndEndsEverySignIn() {
        var a = identity("s-5", "five@example.invalid", null);
        admitted(a, List.of());
        registry.admit(a, proof()); var other = identity("s-5", "five@example.invalid", null); registry.admit(other, proof('o'));
        failsWith(() -> registry.changeCredentials(a, proof(), () -> { throw new AccountFailure(401, "INVALID_CURRENT_PASSWORD", "no"); }), 401, "INVALID_CURRENT_PASSWORD");
        registry.authenticate(a, proof()); // a known rejection keeps the sign-ins
        registry.changeCredentials(a, proof(), () -> {});
        failsWith(() -> registry.authenticate(a, proof()), 401, "AUTHENTICATION_REQUIRED");
        failsWith(() -> registry.authenticate(other, proof('o')), 401, "AUTHENTICATION_REQUIRED");
        var again = identity("s-5", "five@example.invalid", null);
        registry.admit(again, proof('n'));
        assertThatThrownBy(() -> registry.changeCredentials(again, proof('n'), () -> { throw new IllegalStateException("provider down"); })).isInstanceOf(IllegalStateException.class);
        failsWith(() -> registry.authenticate(again, proof('n')), 401, "AUTHENTICATION_REQUIRED");
        registry.terminate(identity("s-5", "five@example.invalid", null), proof('n'));
    }

    @Test void aSecondIdentityWithTheSameVerifiedEmailJoinsTheOneEligibleAccountOrWaitsWhenSeveralShareIt() {
        var paula = identity("s-6", "paula@example.invalid", "paula@example.invalid");
        UUID id = admitted(paula, List.of());
        registry.admit(paula, proof('a'));
        clock.advance(Duration.ofSeconds(1));
        var google = identity("s-6-google", "Google_42", "Paula@example.invalid");
        try (var log = LogCapture.start()) {
            var linked = registry.admit(google, proof('g'));
            assertThat(linked.accountId()).isEqualTo(id); assertThat(linked.signInId()).isNotNull(); assertThat(linked.mergeAvailable()).isFalse();
            assertThat(log.only("identity.linked").getFormattedMessage()).isEqualTo("New google sign-in linked to account " + id + ": its verified email matched exactly one eligible account");
        }
        assertThat(accounts.identities(id)).hasSize(2);
        assertThat(accounts.emailRevision("paula@example.invalid")).isEqualTo(2);
        var profile = registry.profile(google, proof('g'), null);
        assertThat(profile).containsEntry("signInMethod", "google");
        @SuppressWarnings("unchecked") var methods = (List<Map<String, Object>>) profile.get("signInMethods");
        assertThat(methods).extracting(m -> m.get("provider")).containsExactly("cognito", "google");
        assertThat(registry.authenticate(google, proof('g')).accountId()).isEqualTo(id);
        // A second account made with another email signs in again carrying Paula's: two accounts share it, the merge is offered.
        var second = identity("s-7", "other@example.invalid", "other@example.invalid");
        UUID other = admitted(second, List.of());
        var moved = registry.admit(identity("s-7", "other@example.invalid", "paula@example.invalid"), proof('s'));
        assertThat(moved.accountId()).isEqualTo(other); assertThat(moved.mergeAvailable()).isTrue();
        assertThat(accounts.control(other).verifiedEmail()).isEqualTo("paula@example.invalid");
        assertThat(accounts.emailAccounts("other@example.invalid")).isEmpty();
        assertThat(accounts.sameEmailAccounts(id, "paula@example.invalid")).isEqualTo(1);
        var merge = registry.admit(identity("s-6", "paula@example.invalid", "paula@example.invalid"), proof('m'));
        assertThat(merge.mergeAvailable()).isTrue();
        // Several eligible accounts share the email and all are admitted: a third identity is not linked to one of them
        // but admitted as its own account at once, with no waiting row for the owner.
        try (var log = LogCapture.start()) {
            var third = registry.admit(identity("s-8", "third@example.invalid", "paula@example.invalid"), proof('t'));
            assertThat(third.accountId()).isNotIn(id, other);
            assertThat(LogCapture.keys(log.only("identity.link-skipped"))).containsEntry("reason", "SEVERAL_SAME_EMAIL_ACCOUNTS").containsEntry("count", 2).containsEntry("allAdmitted", true);
            assertThat(log.only("admission.admitted-same-email").getFormattedMessage()).contains("admitted at first contact");
        }
        assertThat(accounts.emailAccounts("paula@example.invalid")).hasSize(3);
        assertThat(admissions.waiting()).isEmpty();
        assertThat(accounts.accountOf(ISSUER, "s-8")).isNotIn(id, other);
        assertThat(accounts.mergeable(id, "paula@example.invalid")).isTrue();
        // No verified email: never linked.
        failsWith(() -> registry.admit(identity("s-9", "nine@example.invalid", null), proof('n')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        assertThat(accounts.identities(id)).hasSize(2);
    }

    @Test void ownerBindingWrittenAfterTheFirstContactAdmitsTheWaitingOwnerAtItsNextSignIn() {
        // DLV-970 DEV cutover order: the owner signed in before OWNER / ROOT was written and became an ordinary waiting account.
        var late = identity("late-owner-subject", "late-owner@example.invalid", "late-owner@example.invalid");
        failsWith(() -> registry.admit(late, proof('w')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        UUID id = accounts.accountOf(ISSUER, "late-owner-subject");
        assertThat(accounts.control(id).admitted()).isFalse(); assertThat(accounts.control(id).author()).isFalse();
        assertThat(admissions.waiting()).singleElement().satisfies(w -> assertThat(w.accountId()).isEqualTo(id));
        // The binding is written (by hand, never by the runtime); the next sign-in is the reviewed admission decision.
        DynamoLocalFixture.seedOwner(client, ISSUER, "late-owner-subject");
        try (var log = LogCapture.start()) {
            assertThat(registry.admit(same(late), proof('w')).signInId()).isNotNull();
            assertThat(log.only("admission.admitted-owner").getFormattedMessage()).contains("bound after the first contact");
        }
        var control = accounts.control(id);
        assertThat(control.admitted()).isTrue(); assertThat(control.author()).isTrue(); assertThat(control.admittedAt()).isEqualTo(NOW);
        assertThat(admissions.waiting()).isEmpty();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), WAITING, waitingSk(NOW, id))).isEmpty();
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(id), "ADMISSION#")).extracting(e -> e.get("action").s()).containsExactlyInAnyOrder("request", "admit");
        assertThat(registry.authenticate(same(late), proof('w')).cloudAuthor()).isTrue();
        // Nothing changes for an account that is not the owner's: it still waits.
        var other = identity("s-9", "other@example.invalid", "other@example.invalid");
        failsWith(() -> registry.admit(other, proof('x')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        failsWith(() -> registry.admit(same(other), proof('x')), 403, "ACCOUNT_ADMISSION_REQUIRED");
    }

    @Test void ownerControlDisablesEnablesAndDeletesWithSessionsEndedAndErasureResumable() {
        var ownerIdentity = identity(OWNER_SUBJECT, "owner@example.invalid", "owner@example.invalid");
        // DLV-970: the protected owner identity admits itself at first contact (nothing else could on a fresh store).
        assertThat(registry.admit(ownerIdentity, proof('o')).signInId()).isNotNull();
        UUID ownerId = accounts.accountOf(ISSUER, OWNER_SUBJECT);
        assertThat(accounts.control(ownerId).author()).isTrue(); assertThat(accounts.control(ownerId).admitted()).isTrue();
        assertThat(registry.authenticate(ownerIdentity, proof('o')).cloudAuthor()).isTrue();
        var learner = identity("s-10", "ten@example.invalid", "ten@example.invalid");
        UUID id = admitted(learner, List.of("reviewer"));
        registry.admit(learner, proof()); registry.preferences(learner, proof(), "dark");
        var erased = new ArrayList<UUID>();
        var beans = new StaticListableBeanFactory();
        beans.addBean("plans", (LearningDataErasure) erased::add);
        beans.addBean("memberships", (MembershipErasure) account -> {});
        var removal = new DynamoAccountRemovalStore(client, TABLES, staff, beans.getBeanProvider(TestAccountRegistryLookup.class),
                beans.getBeanProvider(LearningDataErasure.class), beans.getBeanProvider(MembershipErasure.class), clock);
        assertThat(removal.options(id).admitted()).isTrue();
        assertThatThrownBy(() -> removal.disable(ownerId, ownerId)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("OWNER_ACCOUNT_PROTECTED"));
        assertThatThrownBy(() -> removal.disable(ownerId, UUID.randomUUID())).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("SIGN_IN_ACCOUNT_NOT_FOUND"));
        var disabled = removal.disable(ownerId, id);
        assertThat(disabled.changed()).isTrue(); assertThat(disabled.target().usernames()).containsExactly("ten@example.invalid");
        assertThat(removal.disable(ownerId, id).changed()).isFalse();
        assertThat(accounts.control(id).disabled()).isTrue(); assertThat(accounts.control(id).authGeneration()).isEqualTo(1);
        failsWith(() -> registry.authenticate(learner, proof()), 401, "AUTHENTICATION_REQUIRED");
        failsWith(() -> registry.admit(identity("s-10", "ten@example.invalid", "ten@example.invalid"), proof('b')), 401, "AUTHENTICATION_REQUIRED");
        assertThat(removal.options(id).state()).isEqualTo("disabled");
        assertThat(removal.enable(ownerId, id).changed()).isTrue();
        assertThat(removal.enable(ownerId, id).changed()).isFalse();
        failsWith(() -> registry.authenticate(learner, proof()), 401, "AUTHENTICATION_REQUIRED"); // the old generation stays dead
        var back = identity("s-10", "ten@example.invalid", "ten@example.invalid");
        assertThat(registry.admit(back, proof('c')).signInId()).isNotNull();
        var removed = removal.remove(ownerId, id);
        assertThat(removed.changed()).isTrue();
        assertThat(accounts.control(id).removed()).isTrue();
        assertThat(erased).containsExactly(id);
        assertThat(staff.forAccount(id).roles()).isEmpty();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), account(id), "PREFERENCES")).isEmpty();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), removalJobPk(id), "JOB").get("phase").s()).isEqualTo("DONE");
        assertThat(get(client, TABLES.administratorAuditEventsTableName(), account(id), "REMOVED")).isNotEmpty();
        assertThat(removal.remove(ownerId, id).changed()).isFalse();
        assertThat(erased).hasSize(1);
        failsWith(() -> registry.admit(back, proof('c')), 401, "AUTHENTICATION_REQUIRED");
        assertThatThrownBy(() -> removal.enable(ownerId, id)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("ACCOUNT_REMOVED"));
        assertThat(removal.options(id).state()).isEqualTo("removed");
        // A waiting account (DLV-971 Reject): disabled under its own control, then deleted the way the cleanup deletes.
        var waiting = identity("s-11", "eleven@example.invalid", "eleven@example.invalid");
        failsWith(() -> registry.admit(waiting, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
        UUID waitingId = accounts.accountOf(ISSUER, "s-11");
        assertThat(removal.disable(ownerId, waitingId).changed()).isTrue();
        assertThat(admissions.waiting()).isEmpty();
        assertThat(removal.enable(ownerId, waitingId).changed()).isTrue();
        assertThat(admissions.waiting()).hasSize(1);
        assertThat(removal.remove(ownerId, waitingId).changed()).isTrue();
        assertThat(accounts.control(waitingId)).isNull();
        assertThat(accounts.accountOf(ISSUER, "s-11")).isNull();
        assertThat(accounts.emailAccounts("eleven@example.invalid")).isEmpty();
        // An interrupted erasure (the job left in its ERASE phase) is finished by a retry or a sweep, never twice.
        var reopen = accounts.transaction();
        reopen.update(TABLES.userAccountDetailsTableName(), key(removalJobPk(id), "JOB"), Map.of("phase", s("ERASE")), null); reopen.commit();
        assertThat(removal.erasure().pending(id)).isTrue();
        assertThat(removal.erasure().resume(ownerId, List.of(id, UUID.randomUUID()))).isEqualTo(1);
        assertThat(erased).hasSize(2); assertThat(removal.erasure().pending(id)).isFalse();
        assertThat(removal.erasure().resume(ownerId, List.of(id))).isZero();
        reopen = accounts.transaction();
        reopen.update(TABLES.userAccountDetailsTableName(), key(removalJobPk(id), "JOB"), Map.of("phase", s("ERASE")), null); reopen.commit();
        assertThat(removal.remove(ownerId, id).changed()).isFalse();
        assertThat(erased).hasSize(3); assertThat(removal.erasure().pending(id)).isFalse();
        // A failing lane erasure is logged by the sweep and left pending for the next run.
        reopen = accounts.transaction();
        reopen.update(TABLES.userAccountDetailsTableName(), key(removalJobPk(id), "JOB"), Map.of("phase", s("ERASE")), null); reopen.commit();
        var failing = new StaticListableBeanFactory();
        failing.addBean("plans", (LearningDataErasure) account -> { throw new IllegalStateException("plans down"); });
        var broken = new DynamoAccountRemovalStore(client, TABLES, staff, beans.getBeanProvider(TestAccountRegistryLookup.class),
                failing.getBeanProvider(LearningDataErasure.class), beans.getBeanProvider(MembershipErasure.class), clock);
        try (var log = LogCapture.start()) {
            assertThat(broken.erasure().resume(ownerId, List.of(id))).isZero();
            assertThat(log.only("account.erasure").getFormattedMessage()).isEqualTo("Erasure of deleted account " + id + " did not complete (IllegalStateException); the next run tries again");
        }
        assertThat(removal.erasure().pending(id)).isTrue();
    }

    @Test void controlChangesWithoutTheOwnerBindingOrTheOwnerAccountAreRefusedNotGuessed() {
        var learner = identity("s-16", "sixteen@example.invalid", null);
        UUID id = admitted(learner, List.of());
        var beans = new StaticListableBeanFactory();
        var removal = new DynamoAccountRemovalStore(client, TABLES, staff, beans.getBeanProvider(TestAccountRegistryLookup.class),
                beans.getBeanProvider(LearningDataErasure.class), beans.getBeanProvider(MembershipErasure.class), clock);
        // The actor has no account of its own: no owner identity to act as.
        assertThatThrownBy(() -> removal.disable(UUID.randomUUID(), id)).isInstanceOfSatisfying(AccountFailure.class, f -> { assertThat(f.status()).isEqualTo(503); assertThat(f.code()).isEqualTo("OWNER_IDENTITY_UNKNOWN"); });
        // An account whose identity is not the protected owner binding is refused by the control store, and the refusal is logged.
        var impostor = identity("s-17", "seventeen@example.invalid", null);
        UUID impostorId = admitted(impostor, List.of());
        try (var log = LogCapture.start()) {
            assertThatThrownBy(() -> removal.remove(impostorId, id)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("ACCOUNT_CONTROL_REFUSED"));
            assertThat(LogCapture.keys(log.only("account.remove"))).containsEntry("reason", "ACCOUNT_CONTROL_REFUSED");
        }
        assertThat(accounts.control(id).active()).isTrue();
        assertThat(accounts.control(id).admitted()).isTrue();
        // A managed test account (lane C's registry) takes the test-account route.
        var registry = new StaticListableBeanFactory();
        registry.addBean("tests", (TestAccountRegistryLookup) account -> account.equals(id));
        var guarded = new DynamoAccountRemovalStore(client, TABLES, staff, registry.getBeanProvider(TestAccountRegistryLookup.class),
                beans.getBeanProvider(LearningDataErasure.class), beans.getBeanProvider(MembershipErasure.class), clock);
        assertThatThrownBy(() -> guarded.enable(impostorId, id)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("TEST_ACCOUNT_ROUTE_REQUIRED"));
        // revokeSignIns on its own: the generation advances, an unknown account is a no-op.
        registryRevoke(removal, id);
        removal.revokeSignIns(UUID.randomUUID());
    }
    private void registryRevoke(DynamoAccountRemovalStore removal, UUID id) {
        long before = accounts.control(id).authGeneration();
        removal.revokeSignIns(id);
        assertThat(accounts.control(id).authGeneration()).isEqualTo(before + 1);
    }

    @Test void theCleanupExpiresOnlyUntouchedWaitingAccountsOlderThanFortyEightHours() {
        var old = identity("s-12", "twelve@example.invalid", "twelve@example.invalid");
        failsWith(() -> registry.admit(old, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
        UUID oldId = accounts.accountOf(ISSUER, "s-12");
        clock.advance(Duration.ofHours(48));
        var young = identity("s-13", "thirteen@example.invalid", null);
        failsWith(() -> registry.admit(young, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
        var kept = identity("s-14", "fourteen@example.invalid", null);
        UUID keptId = admitted(kept, List.of());
        var cleanup = new CloudAdmissionCleanup(new DynamoAdmissionCleanupStore(client, TABLES, clock));
        assertThat(cleanup.run()).isZero(); // exactly 48 h is not yet expired
        clock.advance(Duration.ofSeconds(1));
        try (var log = LogCapture.start()) {
            assertThat(cleanup.run()).isEqualTo(1);
            assertThat(log.only("admission.expired").getFormattedMessage()).isEqualTo("Waiting account " + oldId + " expired: not admitted within 48 h, removed with its sign-in identities");
            assertThat(LogCapture.keys(log.only("admission.cleanup"))).containsEntry("count", 1);
        }
        assertThat(accounts.control(oldId)).isNull(); assertThat(accounts.accountOf(ISSUER, "s-12")).isNull();
        assertThat(accounts.emailAccounts("twelve@example.invalid")).isEmpty();
        assertThat(accounts.control(keptId).admitted()).isTrue(); assertThat(accounts.accountOf(ISSUER, "s-13")).isNotNull();
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(oldId), "ADMISSION#")).extracting(e -> e.get("action").s()).containsExactly("request", "expire");
        assertThat(cleanup.run()).isZero();
    }

    @Test void preparedAccessByEmailAdmitsAFirstContactAtOnce() {
        access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), null, "Invited@Example.invalid", Set.of("admin"), Set.of(), false, 0));
        var invited = identity("s-15", "invited@example.invalid", "invited@example.invalid");
        try (var log = LogCapture.start()) {
            var admission = registry.admit(invited, proof());
            assertThat(admission.signInId()).isNotNull();
            assertThat(log.only("admission.admitted-by-invitation").getFormattedMessage()).contains("roles [admin]");
        }
        UUID id = admission(invited);
        assertThat(accounts.control(id).admitted()).isTrue();
        assertThat(staff.forAccount(id).roles()).containsExactly("admin");
        assertThat(staff.pending("invited@example.invalid")).isNull();
        assertThat(admissions.waiting()).isEmpty();
    }
    private UUID admission(CognitoIdentity identity) { return accounts.accountOf(identity.issuer(), identity.subject()); }
}
