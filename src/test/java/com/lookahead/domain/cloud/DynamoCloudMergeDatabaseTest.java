package com.lookahead.domain.cloud;

import static com.lookahead.domain.cloud.CloudAccountItems.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.access.AccountAdmissionService;
import com.lookahead.domain.access.AccountRemovalService;
import com.lookahead.domain.access.DynamoAccountRemovalStore;
import com.lookahead.domain.access.DynamoAdmissionStore;
import com.lookahead.domain.access.DynamoStaffAccessRepository;
import com.lookahead.domain.access.LearningDataErasure;
import com.lookahead.domain.access.MembershipErasure;
import com.lookahead.domain.access.MembershipFacts;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.StudyPlanFacts;
import com.lookahead.domain.access.TestAccountRegistryLookup;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.DynamoPlanErasure;
import com.lookahead.learning.content.repository.DynamoPlanFacts;
import com.lookahead.learning.content.repository.DynamoPlanRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-969 S36.6 on DynamoDB Local, the cases of {@code CloudMergeDatabaseTest}: accounts that already share a verified
 * email are merged by the person through an emailed single-use link. The sign-in pool is mocked and never called; email
 * goes to an in-memory fake; plans are real items of lane B's repository.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoCloudMergeDatabaseTest {
    static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_Synthetic", OWNER_SUBJECT = "owner-subject";
    static final Pattern LINK = Pattern.compile("https://dev\\.devlookahead\\.com/account/merge\\?token=([A-Za-z0-9_-]{43})");
    static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoLocalFixture.TABLES;
    record Sent(String to, String subject, String body) {}
    final List<Sent> outbox = new ArrayList<>();
    final EmailSender fake = (to, subject, body) -> outbox.add(new Sent(to, subject, body));
    final MutableClock clock = new MutableClock(NOW);
    final ObjectMapper json = new ObjectMapper();
    DynamoCloudSignInRegistry registry; DynamoCloudAccounts accounts; DynamoAdmissionStore admissions; DynamoStaffAccessRepository staff; StaffAccessService access;
    DynamoPlanRepository plans; DynamoPlanFacts planFacts; CognitoSignInAccounts pool; MembershipFacts memberships; AccountRemovalService removal; DynamoCloudMergeService merges;
    UUID ownerId; AccountPrincipal owner;

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
        DynamoLocalFixture.purge(client, TABLES.userAccountDetailsTableName(), "PLAN#");
        DynamoLocalFixture.seedOwner(client, ISSUER, OWNER_SUBJECT);
        outbox.clear();
        registry = new DynamoCloudSignInRegistry(client, TABLES, clock);
        accounts = new DynamoCloudAccounts(client, TABLES, clock);
        admissions = new DynamoAdmissionStore(client, TABLES, clock);
        staff = new DynamoStaffAccessRepository(client, TABLES, json, clock);
        var catalog = mock(SnapshotValidator.class);
        access = new StaffAccessService(staff, new LocalAuthorAccess(new MockEnvironment().withProperty("app.deployment-environment", "dev")), catalog);
        registry.configureStaffAccess(access);
        plans = new DynamoPlanRepository(client, TABLES, json);
        planFacts = new DynamoPlanFacts(client, TABLES, new DynamoPlanErasure(client, TABLES, json), json);
        pool = mock(CognitoSignInAccounts.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        memberships = mock(MembershipFacts.class, withSettings().mockMaker(MockMakers.PROXY));
        var beans = new StaticListableBeanFactory();
        beans.addBean("pool", pool); beans.addBean("planFacts", planFacts); beans.addBean("memberships", memberships);
        beans.addBean("membershipErasure", (MembershipErasure) account -> {});
        var store = new DynamoAccountRemovalStore(client, TABLES, staff, beans.getBeanProvider(TestAccountRegistryLookup.class),
                beans.getBeanProvider(LearningDataErasure.class), beans.getBeanProvider(MembershipErasure.class), clock);
        removal = new AccountRemovalService(store, access, beans.getBeanProvider(CognitoSignInAccounts.class));
        merges = new DynamoCloudMergeService(client, TABLES, clock, removal, fake, "https://dev.devlookahead.com/",
                beans.getBeanProvider(TestAccountRegistryLookup.class), beans.getBeanProvider(MembershipFacts.class), beans.getBeanProvider(StudyPlanFacts.class));
        var ownerIdentity = identity(OWNER_SUBJECT, "owner@example.invalid", "owner@example.invalid");
        String ownerProof = proof();
        registry.admit(ownerIdentity, ownerProof);
        ownerId = accounts.accountOf(ISSUER, OWNER_SUBJECT);
        owner = registry.authenticate(ownerIdentity, ownerProof);
    }

    CognitoIdentity identity(String subject, String username, String email) { return new CognitoIdentity(ISSUER, subject, UUID.randomUUID().toString(), username, "Name of " + subject, clock.instant(), email); }
    static CognitoIdentity again(CognitoIdentity identity) { return new CognitoIdentity(identity.issuer(), identity.subject(), UUID.randomUUID().toString(), identity.username(), identity.displayName(), identity.authenticatedAt(), identity.verifiedEmail()); }
    static String proof() { return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0, 32).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, f -> { assertThat(f.status()).isEqualTo(status); assertThat(f.code()).isEqualTo(code); });
    }
    UUID contact(CognitoIdentity identity) { failsWith(() -> registry.admit(identity, proof()), 403, "ACCOUNT_ADMISSION_REQUIRED"); return accounts.accountOf(identity.issuer(), identity.subject()); }
    UUID admitted(CognitoIdentity identity) {
        UUID id = contact(identity);
        new AccountAdmissionService(admissions, access, staff).admit(owner, new AccountAdmissionService.Request(id.toString(), List.of()));
        return id;
    }
    /** One account of a person: its identity, a live sign-in and the session principal it gives. */
    record Person(UUID id, CognitoIdentity identity, String proof, AccountPrincipal session) {}
    /**
     * An admitted account with {@code email}; {@code first=false} makes it a second account for an email that already has
     * one: created under another address, it then signs in carrying the shared one (S36 linking would otherwise join them).
     */
    Person person(String subject, String username, String email, boolean first) {
        UUID id = admitted(identity(subject, username, first ? email : subject + "@other.invalid"));
        clock.advance(Duration.ofSeconds(1));
        var signedIn = identity(subject, username, email); String proof = proof();
        registry.admit(signedIn, proof);
        return new Person(id, signedIn, proof, registry.authenticate(signedIn, proof));
    }
    /** Two accounts of one Gmail: Google first, then email and password. */
    List<Person> duplicates(String name) {
        String email = name + "@gmail.invalid";
        return List.of(person(name + "-google", "Google_" + name, email, true), person(name + "-password", email, email, false));
    }
    String token(int index) { var matcher = LINK.matcher(outbox.get(index).body()); assertThat(matcher.find()).isTrue(); return matcher.group(1); }
    UUID plan(UUID account, String name, int completed) {
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID();
        var snapshot = json.readTree("{\"config\":{\"topicIds\":[\"java\"],\"days\":2},\"days\":[{\"day\":1,\"assignments\":[{\"id\":\"a1\"},{\"id\":\"a2\"}]},{\"day\":2,\"assignments\":[{\"id\":\"a3\"},{\"id\":\"a4\"}]}]}");
        var progress = json.readTree("{\"completedSessionIds\":" + json.writeValueAsString(List.of("a1", "a2", "a3", "a4").subList(0, completed)) + "}");
        long number = plans.allocatePlanNumber(account);
        plans.createPlan(account, plan, version, name, progress, name, number);
        plans.insertVersion(account, plan, version, null, snapshot, "d", json.createObjectNode(), json.createObjectNode(), "created", json.createObjectNode());
        clock.advance(Duration.ofSeconds(1));
        return plan;
    }
    int tokens(String email) { return query(client, TABLES.userAccountDetailsTableName(), emailPk(email), "MERGE#").size(); }
    long liveSignIns(UUID account) {
        var control = accounts.control(account);
        return registry.sessions(account).stream().filter(s -> DynamoCloudSignInRegistry.active(s, control, clock.instant())).count();
    }

    @Test void theSignInOffersTheMergeOnlyWhileAnotherAccountHasTheEmail() {
        var people = duplicates("offer");
        assertThat(registry.admit(again(people.get(0).identity()), proof()).mergeAvailable()).isTrue();
        assertThat(registry.admit(again(people.get(1).identity()), proof()).mergeAvailable()).isTrue();
        var single = person("offer-single", "Google_single", "single@gmail.invalid", true);
        assertThat(registry.admit(again(single.identity()), proof()).mergeAvailable()).isFalse();
        // Disabled or removed takes the offer away.
        removal.disable(owner, people.get(1).id(), new AccountRemovalService.Confirmation("DISABLE"));
        assertThat(registry.admit(again(people.get(0).identity()), proof()).mergeAvailable()).isFalse();
        removal.enable(owner, people.get(1).id());
        assertThat(registry.admit(again(people.get(0).identity()), proof()).mergeAvailable()).isTrue();
    }

    @Test void aRequestSendsOneLinkReplacesItOnRepeatAndStopsAtThreePerHour() {
        var people = duplicates("request"); String email = "request@gmail.invalid";
        try (var log = LogCapture.start()) {
            merges.request(people.get(1).session());
            var line = log.only("merge.request");
            assertThat(line.getFormattedMessage()).isEqualTo("Merge link sent for account " + people.get(1).id() + ": 2 accounts share its verified email; the link is single use and valid for 30 minutes");
            assertThat(LogCapture.keys(line)).containsEntry("event.outcome", "success").containsEntry("account.id", people.get(1).id()).containsEntry("count", 2);
            log.containsNone(email, token(0));
        }
        assertThat(outbox).hasSize(1); assertThat(tokens(email)).isEqualTo(1);
        var sent = outbox.getFirst();
        assertThat(sent.to()).isEqualTo(email); assertThat(sent.subject()).isEqualTo(CloudMergeService.SUBJECT);
        assertThat(sent.body()).contains("https://dev.devlookahead.com/account/merge?token=").contains(CloudMergeService.WARNING).contains("within 30 minutes");
        String first = token(0);
        // Only the digest is stored, with the requester.
        var stored = get(client, TABLES.userAccountDetailsTableName(), mergePk(CloudSignInRegistry.digest(first)), "TOKEN");
        assertThat(stored.get("requestedBy").s()).isEqualTo(people.get(1).id().toString()); assertThat(stored.get("email").s()).isEqualTo(email);
        assertThat(query(client, TABLES.userAccountDetailsTableName(), mergePk(first), null)).isEmpty();
        Instant expires = merges.candidates(people.get(0).session(), first).expiresAt();
        assertThat(expires).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        // A repeat within the hour: a new token with the same expiry, the earlier link stops working.
        clock.advance(Duration.ofMinutes(1));
        merges.request(people.get(0).session());
        assertThat(outbox).hasSize(2); String second = token(1); assertThat(second).isNotEqualTo(first);
        assertThat(merges.candidates(people.get(0).session(), second).expiresAt()).isEqualTo(expires);
        failsWith(() -> merges.candidates(people.get(0).session(), first), 404, "MERGE_TOKEN_INVALID");
        merges.request(people.get(0).session()); assertThat(outbox).hasSize(3);
        try (var log = LogCapture.start()) {
            merges.request(people.get(1).session());
            var limit = log.only("merge.request");
            assertThat(limit.getLoggerName()).isEqualTo("lookahead.limit");
            assertThat(limit.getFormattedMessage()).isEqualTo("Merge link not sent for account " + people.get(1).id() + ": already 3 links for this email in the last hour (the caller still gets 202)");
            assertThat(LogCapture.keys(limit)).containsEntry("reason", "MERGE_REQUEST_RATE_LIMITED").containsEntry("limit.max", 3L);
            log.containsNone(email);
        }
        assertThat(outbox).hasSize(3); assertThat(tokens(email)).isEqualTo(3);
        // Links older than a day are cleared on the next request; the limit then allows a send again.
        clock.advance(Duration.ofDays(2));
        merges.request(people.get(1).session());
        assertThat(outbox).hasSize(4); assertThat(tokens(email)).isEqualTo(1);
        assertThat(accounts.emailRevision(email)).as("every request advances the email sentinel").isGreaterThanOrEqualTo(4);
    }

    @Test void nothingIsSentWhenThereIsNothingToMergeAndTheCallerCannotTell() {
        var single = person("lonely-google", "Google_lonely", "lonely@gmail.invalid", true);
        try (var log = LogCapture.start()) {
            merges.request(single.session());
            merges.request(new AccountPrincipal(single.id(), "x", "x", true, false, null));
            assertThat(log.lines("merge.request")).extracting(line -> LogCapture.keys(line).get("reason")).containsExactly("NOTHING_TO_MERGE", "NO_VERIFIED_EMAIL");
            assertThat(log.lines("merge.request").getFirst().getFormattedMessage()).isEqualTo("Merge link not sent for account " + single.id() + ": 1 account(s) carry this email, nothing to merge (the caller still gets 202)");
            log.containsNone("lonely@gmail.invalid");
        }
        // A session whose email is not its account's never sends either.
        var people = duplicates("notmine");
        merges.request(new AccountPrincipal(single.id(), "x", "x", true, false, "notmine@gmail.invalid"));
        assertThat(outbox).isEmpty(); assertThat(tokens("lonely@gmail.invalid") + tokens("notmine@gmail.invalid")).isZero();
        assertThat(people).hasSize(2);
    }

    @Test void anEmailOutageStillAnswersAndCostsOneSend() {
        var people = duplicates("outage");
        var down = new DynamoCloudMergeService(client, TABLES, clock, removal, (to, subject, body) -> { throw new IllegalStateException("SES down"); }, "https://dev.devlookahead.com",
                new StaticListableBeanFactory().getBeanProvider(TestAccountRegistryLookup.class), new StaticListableBeanFactory().getBeanProvider(MembershipFacts.class), new StaticListableBeanFactory().getBeanProvider(StudyPlanFacts.class));
        try (var log = LogCapture.start()) {
            assertThatCode(() -> down.request(people.get(0).session())).doesNotThrowAnyException();
            var line = log.only("merge.request");
            assertThat(line.getFormattedMessage()).isEqualTo("Merge link for account " + people.get(0).id() + " was created but the email was not sent (IllegalStateException); it counts as one of the 3 sends per hour and the caller still gets 202");
            log.containsNone("outage@gmail.invalid", "SES down");
        }
        assertThat(tokens("outage@gmail.invalid")).isEqualTo(1);
    }

    @Test void theLinkListsTheCandidatesWithTheirPlansToACandidateOnly() {
        var people = duplicates("list");
        var keep = people.get(0); var drop = people.get(1);
        UUID a = plan(keep.id(), "Half done", 2); UUID b = plan(keep.id(), "Not started", 0);
        when(memberships.hasPaidMembership(drop.id())).thenReturn(true);
        merges.request(keep.session()); String token = token(0);
        var listed = merges.candidates(drop.session(), token);
        assertThat(listed.email()).isEqualTo("list@gmail.invalid");
        assertThat(listed.candidates()).extracting(CloudMergeService.Candidate::accountId).containsExactly(keep.id(), drop.id());
        var first = listed.candidates().get(0);
        assertThat(first.displayName()).isEqualTo("Name of list-google"); assertThat(first.admitted()).isTrue(); assertThat(first.lastSignInAt()).isNotNull();
        assertThat(first.signInMethods()).containsExactly(Map.of("provider", "google", "email", "list@gmail.invalid"));
        assertThat(first.studyPlans()).containsExactly(new CloudMergeService.Plan(a, "Half done", 50), new CloudMergeService.Plan(b, "Not started", 0));
        assertThat(first.paidMembership()).isFalse(); assertThat(listed.candidates().get(1).paidMembership()).isTrue();
        assertThat(listed.candidates().get(1).signInMethods()).containsExactly(Map.of("provider", "cognito", "email", "list@gmail.invalid"));
        // Someone else with a live session: not a candidate, so the link is simply not valid for them.
        var stranger = person("list-stranger", "Google_ls", "ls@gmail.invalid", true);
        try (var log = LogCapture.start()) {
            failsWith(() -> merges.candidates(stranger.session(), token), 404, "MERGE_TOKEN_INVALID");
            assertThat(log.only("merge.candidates").getFormattedMessage()).isEqualTo("Merge link opened refused for account " + stranger.id() + ": MERGE_TOKEN_INVALID");
            log.containsNone(token, "list@gmail.invalid");
        }
        failsWith(() -> merges.candidates(keep.session(), "short"), 404, "MERGE_TOKEN_INVALID");
    }

    @Test void theMergeMovesTheSignInsDeletesTheOtherAccountAndEndsEverySession() {
        var people = duplicates("merge");
        var keep = people.get(0); var drop = people.get(1);
        UUID keptPlan = plan(keep.id(), "Kept plan", 2); plan(drop.id(), "Dropped plan", 3);
        registry.preferences(drop.identity(), drop.proof(), "dark");
        merges.request(drop.session()); String token = token(0);
        assertThat(liveSignIns(keep.id())).isPositive(); assertThat(liveSignIns(drop.id())).isPositive();
        long emailRevision = accounts.emailRevision("merge@gmail.invalid");
        try (var log = LogCapture.start()) {
            assertThat(merges.merge(drop.session(), token, keep.id())).isEqualTo(new CloudMergeService.Kept(keep.id()));
            var gone = log.only("account.merge-removed");
            assertThat(gone.getFormattedMessage()).isEqualTo("Account " + drop.id() + " merged into " + keep.id() + ": 1 sign-in(s) moved to the kept account, its learning data erased and the account marked deleted; no sign-in pool call, the pool users now belong to the kept account");
            assertThat(LogCapture.keys(gone)).containsEntry("account.id", drop.id()).containsEntry("target.id", drop.id()).containsEntry("into", keep.id()).containsEntry("admitted", true).containsEntry("count", 1);
            var merged = log.only("account.merge");
            assertThat(merged.getFormattedMessage()).isEqualTo("Accounts merged by account " + drop.id() + ": kept " + keep.id() + ", 1 other account(s) deleted into it; every sign-in of all of them ended and the link used up");
            assertThat(log.lines().indexOf(gone)).isLessThan(log.lines().indexOf(merged));
            log.containsNone("merge@gmail.invalid", token, "Google_merge");
        }
        // Both identities under the kept account; the binding of the moved one resolves to it; the dropped account has none.
        assertThat(accounts.identities(keep.id())).extracting(DynamoCloudAccounts.Identity::subject).containsExactly("merge-google", "merge-password");
        assertThat(accounts.identities(keep.id()).get(1).linkSource()).isEqualTo("merge");
        assertThat(accounts.accountOf(ISSUER, "merge-password")).isEqualTo(keep.id());
        assertThat(accounts.identities(drop.id())).isEmpty();
        assertThat(accounts.emailAccounts("merge@gmail.invalid")).containsExactlyInAnyOrder(keep.id(), drop.id()); // the deleted account's own carrier stays, as the SQL row does
        assertThat(accounts.emailControls("merge@gmail.invalid")).extracting(DynamoCloudAccounts.Control::id).containsExactly(keep.id());
        assertThat(accounts.emailRevision("merge@gmail.invalid")).isEqualTo(emailRevision + 1);
        // The dropped account is deleted the way Delete deletes: REMOVED with its marker, the erasure finished, nothing queued.
        var dropped = accounts.control(drop.id());
        assertThat(dropped.removed()).isTrue(); assertThat(dropped.admitted()).isTrue(); assertThat(dropped.authGeneration()).isEqualTo(1);
        assertThat(get(client, TABLES.administratorAuditEventsTableName(), account(drop.id()), "REMOVED").get("changedBy").s()).isEqualTo(drop.id().toString());
        assertThat(get(client, TABLES.userAccountDetailsTableName(), removalJobPk(drop.id()), "JOB").get("phase").s()).isEqualTo("DONE");
        assertThat(query(client, TABLES.userAccountDetailsTableName(), com.lookahead.domain.access.DynamoAccountErasure.QUEUE_PARTITION, null)).isEmpty();
        assertThat(planFacts.hasPlan(drop.id())).isFalse(); assertThat(plans.findPlan(keep.id(), keptPlan, false)).isPresent();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), account(drop.id()), "PREFERENCES")).isEmpty();
        assertThat(accounts.control(keep.id()).active()).isTrue();
        // Every sign-in of both ended; the link is used up; the merge event names the account that went.
        assertThat(liveSignIns(keep.id())).isZero(); assertThat(liveSignIns(drop.id())).isZero();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), mergePk(CloudSignInRegistry.digest(token)), "TOKEN").get("usedAt")).isNotNull();
        var events = query(client, TABLES.administratorAuditEventsTableName(), account(drop.id()), "ADMISSION#").stream().filter(e -> "merge".equals(e.get("action").s())).toList();
        assertThat(events).singleElement().satisfies(e -> { assertThat(e.get("subject").s()).isEqualTo("merge-password"); assertThat(e.get("actorId").s()).isEqualTo(drop.id().toString()); assertThat(e.get("into").s()).isEqualTo(keep.id().toString()); });
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(keep.id()), "ADMISSION#")).noneMatch(e -> "merge".equals(e.get("action").s()));
        assertThatThrownBy(() -> registry.authenticate(keep.identity(), keep.proof())).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> registry.authenticate(drop.identity(), drop.proof())).isInstanceOf(AccountFailure.class);
        // Both sign-in methods now reach the kept account; the directory knows both usernames.
        var back = registry.admit(again(drop.identity()), proof());
        assertThat(back.accountId()).isEqualTo(keep.id()); assertThat(back.mergeAvailable()).isFalse();
        assertThat(accounts.usernames(keep.id())).containsExactly("Google_merge", "merge@gmail.invalid");
        assertThat(strings(get(client, TABLES.userAccountDetailsTableName(), DIRECTORY, personSk("Google_merge", keep.id())), "usernames")).containsExactly("google_merge", "merge@gmail.invalid");
        try (var log = LogCapture.start()) {
            failsWith(() -> merges.merge(keep.session(), token, keep.id()), 404, "MERGE_TOKEN_INVALID");
            failsWith(() -> merges.candidates(keep.session(), token), 404, "MERGE_TOKEN_INVALID");
            assertThat(log.only("merge.merge").getFormattedMessage()).isEqualTo("Merge refused for account " + keep.id() + ": MERGE_TOKEN_INVALID");
            log.containsNone(token, "merge@gmail.invalid");
        }
        verifyNoInteractions(pool);
    }

    @Test void aCandidateDisabledSinceTheListIsAConflictAndAnUnknownKeepIsRefused() {
        var people = duplicates("conflict");
        merges.request(people.get(0).session()); String token = token(0);
        assertThat(merges.candidates(people.get(0).session(), token).candidates()).hasSize(2);
        failsWith(() -> merges.merge(people.get(0).session(), token, UUID.randomUUID()), 422, "MERGE_KEEP_NOT_CANDIDATE");
        failsWith(() -> merges.merge(people.get(0).session(), token, null), 422, "MERGE_KEEP_NOT_CANDIDATE");
        var stranger = person("conflict-stranger", "Google_cs", "cs@gmail.invalid", true);
        failsWith(() -> merges.merge(people.get(0).session(), token, stranger.id()), 422, "MERGE_KEEP_NOT_CANDIDATE");
        removal.disable(owner, people.get(1).id(), new AccountRemovalService.Confirmation("DISABLE"));
        failsWith(() -> merges.merge(people.get(0).session(), token, people.get(0).id()), 409, "MERGE_CONFLICT");
        failsWith(() -> merges.candidates(people.get(0).session(), token), 409, "MERGE_CONFLICT");
        // Nothing changed and the link is still live.
        assertThat(get(client, TABLES.userAccountDetailsTableName(), mergePk(CloudSignInRegistry.digest(token)), "TOKEN").get("usedAt")).isNull();
        assertThat(accounts.identities(people.get(1).id())).hasSize(1);
        removal.enable(owner, people.get(1).id());
        // A deleted candidate kept is a conflict, not "not a candidate"; one account left: nothing to merge.
        removal.remove(owner, people.get(1).id(), new AccountRemovalService.Confirmation("DELETE"));
        failsWith(() -> merges.merge(people.get(0).session(), token, people.get(1).id()), 409, "MERGE_CONFLICT");
        failsWith(() -> merges.merge(people.get(0).session(), token, people.get(0).id()), 409, "MERGE_CONFLICT");
    }

    @Test void anExpiredLinkIsInvalidAndASentinelAdvanceBeforeTheReadIsNoRace() {
        var people = duplicates("expired");
        merges.request(people.get(0).session()); String token = token(0);
        clock.advance(Duration.ofMinutes(31));
        failsWith(() -> merges.candidates(people.get(0).session(), token), 404, "MERGE_TOKEN_INVALID");
        failsWith(() -> merges.merge(people.get(0).session(), token, people.get(0).id()), 404, "MERGE_TOKEN_INVALID");
        // A new request is a fresh 30 minutes.
        merges.request(people.get(0).session()); String fresh = token(1);
        assertThat(merges.candidates(people.get(0).session(), fresh).expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        // Linking or another request advanced the email sentinel before this merge read it: no race, the merge fences on what it read.
        long before = accounts.emailRevision("expired@gmail.invalid");
        var tx = accounts.transaction(); accounts.emailSentinel(tx, "expired@gmail.invalid", before); tx.commit();
        assertThat(merges.merge(people.get(0).session(), fresh, people.get(0).id()).keptAccountId()).isEqualTo(people.get(0).id());
        assertThat(accounts.emailRevision("expired@gmail.invalid")).isEqualTo(before + 2);
    }

    @Test void aWaitingAccountCanGoButNotBeKeptAndAnAuthorAccountIsNeverTheOneThatGoes() {
        var admittedOne = person("waiting-google", "Google_waiting", "waiting@gmail.invalid", true);
        // A waiting account made under another address that then signs in carrying the shared one (linking would otherwise join them).
        UUID waiting = contact(identity("waiting-password", "waiting@gmail.invalid", "wp@other.invalid"));
        clock.advance(Duration.ofSeconds(1));
        failsWith(() -> registry.admit(identity("waiting-password", "waiting@gmail.invalid", "waiting@gmail.invalid"), proof()), 403, "ACCOUNT_ADMISSION_REQUIRED");
        assertThat(accounts.control(waiting).admitted()).isFalse(); assertThat(accounts.emailAccounts("waiting@gmail.invalid")).containsExactlyInAnyOrder(admittedOne.id(), waiting);
        merges.request(admittedOne.session()); String token = token(0);
        var listed = merges.candidates(admittedOne.session(), token).candidates();
        assertThat(listed).extracting(CloudMergeService.Candidate::admitted).containsExactly(true, false);
        assertThat(listed.get(1).lastSignInAt()).isNull();
        failsWith(() -> merges.merge(admittedOne.session(), token, waiting), 422, "MERGE_KEEP_NOT_CANDIDATE");
        assertThat(admissions.waiting()).extracting(w -> w.accountId()).contains(waiting);
        merges.merge(admittedOne.session(), token, admittedOne.id());
        // Deleted outright as the cleanup deletes: no control, no subject, no waiting row; the identity moved first.
        assertThat(accounts.control(waiting)).isNull();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), account(waiting), "META")).isEmpty();
        assertThat(admissions.waiting()).extracting(w -> w.accountId()).doesNotContain(waiting);
        assertThat(accounts.accountOf(ISSUER, "waiting-password")).isEqualTo(admittedOne.id());
        assertThat(accounts.emailAccounts("waiting@gmail.invalid")).containsExactly(admittedOne.id());
        var event = query(client, TABLES.administratorAuditEventsTableName(), account(waiting), "ADMISSION#").stream().filter(e -> "merge".equals(e.get("action").s())).findFirst().orElseThrow();
        assertThat(event.get("subject").s()).isEqualTo("waiting-password"); assertThat(event.get("into").s()).isEqualTo(admittedOne.id().toString());
        assertThatCode(() -> new DynamoAdmissionCleanupStore(client, TABLES, clock).expire()).doesNotThrowAnyException();

        var authors = duplicates("author");
        var tx = accounts.transaction(); accounts.control(tx, accounts.control(authors.get(0).id()), Map.of("author", bool(true)), null); tx.commit();
        merges.request(authors.get(1).session()); String second = token(1);
        failsWith(() -> merges.merge(authors.get(1).session(), second, authors.get(1).id()), 422, "MERGE_KEEP_NOT_CANDIDATE");
        assertThat(merges.merge(authors.get(1).session(), second, authors.get(0).id()).keptAccountId()).isEqualTo(authors.get(0).id());
    }

    @Test void aDeleteRetryOnAMergedAwayAccountTellsThePoolNothingAndTheKeptUserIsUntouched() {
        var people = duplicates("retry");
        merges.request(people.get(0).session());
        merges.merge(people.get(0).session(), token(0), people.get(0).id());
        assertThat(accounts.usernames(people.get(1).id())).isEmpty();
        assertThat(removal.remove(owner, people.get(1).id(), new AccountRemovalService.Confirmation("DELETE")).state()).isEqualTo("removed");
        verifyNoInteractions(pool);
        assertThat(accounts.usernames(people.get(0).id())).containsExactly("Google_retry", "retry@gmail.invalid");
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(people.get(1).id()), "ADMISSION#")).noneMatch(e -> "remove".equals(e.get("action").s()));
    }

    @Test void aSignInBringsTheProvidersCurrentEmailSoTheOldOneNoLongerMatches() {
        var people = duplicates("moved");
        var changed = new CognitoIdentity(ISSUER, "moved-google", UUID.randomUUID().toString(), "Google_moved", "Name of moved-google", clock.instant(), "moved-new@gmail.invalid");
        assertThat(registry.admit(changed, proof()).mergeAvailable()).isFalse();
        assertThat(accounts.control(people.get(0).id()).verifiedEmail()).isEqualTo("moved-new@gmail.invalid");
        assertThat(accounts.sameEmailAccounts(people.get(1).id(), "moved@gmail.invalid")).isZero();
        merges.request(people.get(1).session());
        assertThat(outbox).isEmpty();
    }
}
