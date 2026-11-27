package com.lookahead.domain.db.store.signin;

import com.lookahead.domain.job.AccountErasureJob;
import com.lookahead.domain.job.MaintenanceJobRegistry;
import com.lookahead.domain.job.SignInRecordRetirementJob;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.dto.DevAccessAdmission;
import com.lookahead.domain.model.SignInModels;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.service.signin.SignInService;
import static com.lookahead.domain.db.store.signin.AccountItems.*;
import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.db.store.membership.MembershipErasure;
import com.lookahead.domain.db.store.plan.StudyPlanAccountErasure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.service.access.DevAccessService;
import com.lookahead.domain.db.store.access.AccountErasureStore;
import com.lookahead.domain.db.store.access.AccountDirectoryStore;
import com.lookahead.domain.db.store.access.DevAccessStore;
import com.lookahead.domain.db.store.access.StaffAccessStore;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970, the session rules of the sign-in store on DynamoDB Local: the owner's self-admission, the once-a-minute session touch without a control
 * fence, revocation replays, challenges dying with the sign-in generation, username refreshes, the sign-in record
 * retirement job and the erasure queue's grace period.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class SignInStoreSessionRulesDatabaseTest {
    static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_Synthetic", OWNER_SUBJECT = "owner-subject";
    static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = SignInStoreLocalFixture.TABLES;
    final MutableClock clock = new MutableClock(NOW);
    SignInService signInService; AccountAggregateStore accounts; DevAccessStore devAccess; StaffAccessStore staff; StaffAccessService access;
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true, "owner@example.invalid");

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeAll static void connect() { client = SignInStoreLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void fresh() {
        SignInStoreLocalFixture.purgeLaneA(client);
        SignInStoreLocalFixture.seedOwner(client, ISSUER, OWNER_SUBJECT);
        accounts = new AccountAggregateStore(client, TABLES, clock);
        devAccess = new DevAccessStore(client, TABLES, clock);
        staff = new StaffAccessStore(client, TABLES, new ObjectMapper(), clock);
        var catalog = Mockito.mock(AccountCatalogService.class);
        access = new StaffAccessService(staff, new AuthorPreviewLocalService(new MockEnvironment().withProperty("app.deployment-environment", "dev")), catalog);
        signInService = new SignInService(new SignInStore(client, TABLES, clock), access);
    }

    SignInIdentity identity(String subject, String username, String email) { return new SignInIdentity(ISSUER, subject, UUID.randomUUID().toString(), username, "Person " + subject, clock.instant(), email); }
    static String proof(char c) { return String.valueOf(c).repeat(43); }
    static void failsWith(ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, f -> { assertThat(f.status()).isEqualTo(status); assertThat(f.code()).isEqualTo(code); });
    }
    UUID admitted(SignInIdentity identity) {
        failsWith(() -> signInService.admit(identity, proof('x')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        UUID id = accounts.accountOf(identity.issuer(), identity.subject());
        new DevAccessService(devAccess, access, staff).admit(owner, new DevAccessAdmission(id.toString(), List.of()));
        return id;
    }
    Map<String, AttributeValue> session(UUID account, UUID signIn) { return get(client, TABLES.userAccountDetailsTableName(), account(account), sessionSk(signIn)); }

    @Test void theOwnerIdentityIsAdmittedAtItsOwnFirstContactAndEveryoneElseWaits() {
        var ownerIdentity = identity(OWNER_SUBJECT, "owner@example.invalid", "owner@example.invalid");
        try (var log = LogCapture.start()) {
            var admission = signInService.admit(ownerIdentity, proof('o'));
            assertThat(admission.signInId()).isNotNull();
            assertThat(log.only("admission.admitted-owner").getFormattedMessage()).endsWith("admitted at first contact: its sign-in is the protected owner identity");
            log.containsNone("owner@example.invalid");
        }
        UUID id = accounts.accountOf(ISSUER, OWNER_SUBJECT);
        var control = accounts.control(id);
        assertThat(control.admitted()).isTrue(); assertThat(control.author()).isTrue(); assertThat(control.admittedAt()).isEqualTo(NOW);
        assertThat(get(client, TABLES.userAccountDetailsTableName(), WAITING, waitingSk(NOW, id))).isEmpty();
        assertThat(devAccess.waiting()).isEmpty();
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(id), "ADMISSION#")).extracting(e -> e.get("action").s()).containsExactlyInAnyOrder("request", "admit");
        assertThat(signInService.authenticate(ownerIdentity, proof('o')).cloudAuthor()).isTrue();
        // Anyone else still waits for the owner.
        failsWith(() -> signInService.admit(identity("s-1", "one@example.invalid", "one@example.invalid"), proof('a')), 403, "ACCOUNT_ADMISSION_REQUIRED");
    }

    @Test void theSessionTouchIsOnceAMinuteOnTheSessionAloneAndNeverFencesTheControl() {
        var identity = identity("s-2", "two@example.invalid", "two@example.invalid");
        UUID id = admitted(identity);
        var admission = signInService.admit(identity, proof('a'));
        long revision = accounts.control(id).revision();
        clock.advance(Duration.ofSeconds(30));
        signInService.authenticate(identity, proof('a')); signInService.authenticate(identity, proof('a'));
        assertThat(session(id, admission.signInId()).get("lastActiveAt").s()).isEqualTo(NOW.toString());
        assertThat(accounts.control(id).revision()).as("no control write for a touch").isEqualTo(revision);
        clock.advance(Duration.ofSeconds(31));
        signInService.authenticate(identity, proof('a'));
        assertThat(session(id, admission.signInId()).get("lastActiveAt").s()).isEqualTo(NOW.plus(Duration.ofSeconds(61)).toString());
        assertThat(accounts.control(id).revision()).isEqualTo(revision);
        // Idle expiry still counts from the last touch.
        clock.advance(Duration.ofMinutes(29)); signInService.authenticate(identity, proof('a'));
        clock.advance(Duration.ofMinutes(31)); failsWith(() -> signInService.authenticate(identity, proof('a')), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test void revokingARevokedSignInIsNotAConflictAndAReplacedRevokedSlotIsAccepted() {
        var first = identity("s-3", "three@example.invalid", "three@example.invalid");
        UUID id = admitted(first);
        var one = signInService.admit(first, proof('a'));
        clock.advance(Duration.ofSeconds(1));
        var two = signInService.admit(identity("s-3", "three@example.invalid", "three@example.invalid"), proof('b'));
        // Both slots taken: a third device gets a challenge.
        clock.advance(Duration.ofSeconds(1));
        var third = identity("s-3", "three@example.invalid", "three@example.invalid");
        var challenge = signInService.admit(third, proof('c'));
        assertThat(challenge.challengeToken()).isNotNull();
        // Meanwhile the first device ends the second slot, twice: the second call writes nothing and answers the same.
        assertThat(signInService.revoke(first, proof('a'), two.signInId())).isFalse();
        assertThat(signInService.revoke(first, proof('a'), two.signInId())).isFalse();
        assertThat(session(id, two.signInId()).get("revokedAt")).isNotNull();
        // The challenge still names the (now revoked) slot: it is replaced without a conflict, as the SQL COALESCE does.
        var replaced = signInService.replace(third, proof('c'), challenge.challengeToken(), two.signInId());
        assertThat(replaced.signInId()).isNotNull();
        assertThat(signInService.authenticate(third, proof('c')).accountId()).isEqualTo(id);
        assertThat(signInService.inventory(first, proof('a')).entries()).extracting(SignInModels.Entry::signInId).containsExactly(one.signInId(), replaced.signInId());
    }

    @Test void aChallengeDiesWithTheSignInGenerationItWasIssuedUnder() {
        var first = identity("s-4", "four@example.invalid", "four@example.invalid");
        UUID id = admitted(first);
        signInService.admit(first, proof('a'));
        clock.advance(Duration.ofSeconds(1));
        signInService.admit(identity("s-4", "four@example.invalid", "four@example.invalid"), proof('b'));
        clock.advance(Duration.ofSeconds(1));
        var third = identity("s-4", "four@example.invalid", "four@example.invalid");
        var challenge = signInService.admit(third, proof('c'));
        assertThat(challenge.challengeToken()).isNotNull();
        // "End other sign-ins" advances the generation: the pending challenge cannot be redeemed any more.
        signInService.revokeOthers(first, proof('a'));
        assertThat(accounts.control(id).authGeneration()).isEqualTo(1);
        failsWith(() -> signInService.challenge(third, proof('c'), challenge.challengeToken()), 400, "SIGN_IN_CHALLENGE_INVALID");
        failsWith(() -> signInService.replace(third, proof('c'), challenge.challengeToken(), UUID.randomUUID()), 400, "SIGN_IN_CHALLENGE_INVALID");
        assertThat(get(client, TABLES.userAccountDetailsTableName(), account(id), challengeSk(Sha256.hex(challenge.challengeToken()))).get("authGeneration").n()).isEqualTo("0");
    }

    @Test void usernameRefreshesKeepTheDirectoryCurrentForCaseChangesAndSecondIdentities() {
        var identity = identity("s-5", "Five@example.invalid", "five@example.invalid");
        failsWith(() -> signInService.admit(identity, proof('a')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        UUID id = accounts.accountOf(ISSUER, "s-5");
        // A case-only username change while waiting: the directory item is updated in place (same lower-cased key).
        failsWith(() -> signInService.admit(identity("s-5", "five@example.invalid", "five@example.invalid"), proof('a')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        var person = get(client, TABLES.userAccountDetailsTableName(), DIRECTORY, personSk("five@example.invalid", id));
        assertThat(person.get("username").s()).isEqualTo("five@example.invalid");
        assertThat(accounts.control(id).username()).isEqualTo("five@example.invalid");
        // A second identity joins the waiting account by email; a later sign-in with a changed username refreshes the directory's list.
        clock.advance(Duration.ofSeconds(1));
        var google = identity("s-6", "Google_5", "five@example.invalid");
        failsWith(() -> signInService.admit(google, proof('g')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        assertThat(accounts.accountOf(ISSUER, "s-6")).isEqualTo(id);
        failsWith(() -> signInService.admit(identity("s-6", "Google_5renamed", "five@example.invalid"), proof('h')), 403, "ACCOUNT_ADMISSION_REQUIRED");
        assertThat(strings(get(client, TABLES.userAccountDetailsTableName(), DIRECTORY, personSk("five@example.invalid", id)), "usernames")).containsExactly("five@example.invalid", "google_5renamed");
        assertThat(accounts.usernames(id)).containsExactly("five@example.invalid", "Google_5renamed");
        new DevAccessService(devAccess, access, staff).admit(owner, new DevAccessAdmission(id.toString(), List.of()));
        var directory = new AccountDirectoryStore(client, TABLES);
        assertThat(directory.lookup("google_5renamed")).isPresent();
    }

    @Test void theSignInRecordsJobRetiresDeadSessionsAndStaleChallengesAndKeepsTheFamilyTombstones() {
        var identity = identity("s-7", "seven@example.invalid", "seven@example.invalid");
        UUID id = admitted(identity);
        var old = signInService.admit(identity, proof('a'));
        clock.advance(Duration.ofSeconds(1));
        var other = identity("s-7", "seven@example.invalid", "seven@example.invalid");
        signInService.admit(other, proof('b'));
        clock.advance(Duration.ofSeconds(1));
        var third = identity("s-7", "seven@example.invalid", "seven@example.invalid");
        var challenge = signInService.admit(third, proof('c'));
        signInService.revoke(other, proof('b'), old.signInId());
        var job = new SignInRecordRetirementJob(new SignInRecordStore(client, TABLES, clock));
        assertThat(job.name()).isEqualTo("sign-in-records");
        // Too young: nothing goes (the retention is 7 days for sessions, the 15-minute window for challenges).
        assertThat(job.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(0, 0, 0, false));
        clock.advance(Duration.ofMinutes(21));
        assertThat(job.run(false).cleaned()).as("the expired challenge").isEqualTo(1);
        assertThat(get(client, TABLES.userAccountDetailsTableName(), account(id), challengeSk(Sha256.hex(challenge.challengeToken())))).isEmpty();
        clock.advance(Duration.ofDays(7));
        assertThat(job.run(false).cleaned()).as("both sessions, long dead").isEqualTo(2);
        assertThat(query(client, TABLES.userAccountDetailsTableName(), account(id), "SESSION#")).isEmpty();
        // The family binding stays and still denies the revoked family; a fresh sign-in works.
        failsWith(() -> signInService.admit(new SignInIdentity(identity.issuer(), identity.subject(), identity.family(), identity.username(), identity.displayName(), clock.instant(), identity.verifiedEmail()), proof('a')), 401, "AUTHENTICATION_REQUIRED");
        assertThat(signInService.admit(identity("s-7", "seven@example.invalid", "seven@example.invalid"), proof('z')).signInId()).isNotNull();
        assertThat(job.run(false).cleaned()).isZero();
    }

    @Test void theErasureQueueKeepsAYoungEntryWhoseDeleteIsStillInFlight() {
        var erasure = new AccountErasureStore(accounts, staff, new StaticListableBeanFactory().getBeanProvider(StudyPlanAccountErasure.class),
                new StaticListableBeanFactory().getBeanProvider(MembershipErasure.class));
        UUID id = UUID.randomUUID();
        erasure.queue(owner.accountId(), id);
        var sweep = new AccountErasureJob(erasure); // DLV-1016: the grace rule is the job's
        assertThat(sweep.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(0, 0, 0, false));
        assertThat(erasure.queued(5)).singleElement().satisfies(q -> { assertThat(q.id()).isEqualTo(id); assertThat(q.queuedAt()).isEqualTo(NOW); });
        clock.advance(AccountErasureJob.GRACE.plusSeconds(1));
        assertThat(sweep.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(1, 0, 0, false));
        assertThat(erasure.queued(5)).isEmpty();
        assertThat(DynamoWrites.MAX_ITEMS).isEqualTo(100);
    }
}
