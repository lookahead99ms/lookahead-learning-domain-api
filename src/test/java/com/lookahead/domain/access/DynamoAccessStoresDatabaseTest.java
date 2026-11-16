package com.lookahead.domain.access;

import static com.lookahead.domain.cloud.CloudAccountItems.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lookahead.domain.DomainFixtureSeeder;
import com.lookahead.domain.DynamoFixtureSeedStore;
import com.lookahead.domain.cloud.CognitoIdentity;
import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.cloud.DynamoCloudSignInRegistry;
import com.lookahead.domain.cloud.DynamoLocalFixture;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoCatalog;
import com.lookahead.domain.testaccounts.TestAccountPolicy;
import com.lookahead.domain.testaccounts.TestIdentityOperator;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.repository.DynamoAccountStore;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.util.CatalogIds;
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

/** Real DynamoDB Local: staff access, role labels, the directories, grants, the seed and owner maintenance on the lane A items. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoAccessStoresDatabaseTest {
    static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_Synthetic";
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoLocalFixture.TABLES;
    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    final MutableClock clock = new MutableClock(NOW);
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true, "owner@example.invalid");
    DynamoStaffAccessRepository staff;
    StaffAccessService access;
    DynamoCloudAccounts accounts;
    DynamoCloudSignInRegistry registry;
    DynamoAdmissionStore admissions;
    SnapshotValidator catalog;

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
        // The label counters too: this class bootstraps them itself and expects the first numbers.
        DynamoLocalFixture.purge(client, TABLES.userAccountDetailsTableName(), "STAFF_ROLE#");
        DynamoLocalFixture.purge(client, TABLES.administratorAuditEventsTableName(), "STAFF_ROLE#");
        DynamoLocalFixture.seedOwner(client, ISSUER, "owner-subject");
        staff = new DynamoStaffAccessRepository(client, TABLES, new ObjectMapper(), clock);
        catalog = mock(SnapshotValidator.class); when(catalog.allTopicIds()).thenReturn(Set.of("learn:docker", "grow:system-design"));
        access = new StaffAccessService(staff, new LocalAuthorAccess(new MockEnvironment().withProperty("app.deployment-environment", "dev")), catalog);
        accounts = new DynamoCloudAccounts(client, TABLES, clock);
        registry = new DynamoCloudSignInRegistry(client, TABLES, clock);
        admissions = new DynamoAdmissionStore(client, TABLES, clock);
    }
    static void failsWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, f -> { assertThat(f.status()).isEqualTo(status); assertThat(f.code()).isEqualTo(code); });
    }
    /** An admitted DEV account with a username and a display name, through the registry and the admission store. */
    UUID admittedAccount(String subject, String username, String displayName, String email) {
        var identity = new CognitoIdentity(ISSUER, subject, UUID.randomUUID().toString(), username, displayName, clock.instant(), email);
        // DLV-970: the protected owner identity admits itself at first contact; everyone else waits for the owner.
        if (subject.equals("owner-subject")) { registry.admit(identity, "p".repeat(43)); return accounts.accountOf(ISSUER, subject); }
        failsWith(() -> registry.admit(identity, "p".repeat(43)), 403, "ACCOUNT_ADMISSION_REQUIRED");
        UUID id = accounts.accountOf(ISSUER, subject);
        new AccountAdmissionService(admissions, access, staff).admit(owner, new AccountAdmissionService.Request(id.toString(), List.of()));
        return id;
    }

    @Test void staffAssignmentsAreSavedUnderTheirRevisionWithPointersInvitationsAndEvents() {
        UUID person = UUID.randomUUID(), id = UUID.randomUUID();
        var saved = access.save(owner, new StaffAccessService.Change(id, person, null, Set.of("admin"), Set.of("learn:docker"), false, 0));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(staff.forAccount(person)).isEqualTo(saved); assertThat(staff.lock(id)).isEqualTo(saved);
        assertThat(staff.list()).containsExactly(saved);
        failsWith(() -> access.save(owner, new StaffAccessService.Change(id, person, null, Set.of("tester"), Set.of(), false, 0)), 409, "ACCESS_ASSIGNMENT_CONFLICT");
        // A second assignment for the same person: the pointer sentinel refuses it.
        failsWith(() -> access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), person, null, Set.of(), Set.of(), true, 0)), 409, "ACCESS_ASSIGNMENT_CONFLICT");
        clock.advance(Duration.ofMinutes(1));
        var updated = access.save(owner, new StaffAccessService.Change(id, person, null, Set.of("tester"), Set.of(), true, 1));
        assertThat(updated.revision()).isEqualTo(2); assertThat(staff.forAccount(person).roles()).containsExactly("tester");
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), staffEventPk(id), "EVENT#")).hasSize(2)
            .allSatisfy(e -> { assertThat(e.get("actorId").s()).isEqualTo(owner.accountId().toString()); assertThat(e.get("action").s()).isEqualTo("assign"); });
        assertThat(access.permissions(new AccountPrincipal(person, "p", "P", true)).feedbackRole()).isEqualTo("tester");
        // An invitation by email, claimed on first contact, never twice, and expired after 14 days.
        UUID invite = UUID.randomUUID();
        clock.advance(Duration.ofMinutes(1));
        access.save(owner, new StaffAccessService.Change(invite, null, "Reviewer@Example.invalid", Set.of("reviewer"), Set.of(), false, 0));
        failsWith(() -> access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), null, "reviewer@example.invalid", Set.of("admin"), Set.of(), false, 0)), 409, "ACCESS_ASSIGNMENT_CONFLICT");
        assertThat(staff.pending("reviewer@example.invalid").id()).isEqualTo(invite);
        clock.advance(Duration.ofMinutes(1));
        var later = access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), UUID.randomUUID(), null, Set.of(), Set.of("learn:docker"), false, 0));
        assertThat(staff.list()).extracting(StaffAccessRepository.Assignment::id).containsExactly(later.id(), invite, id);
        UUID claimant = UUID.randomUUID();
        var claimed = access.claimPrepared(claimant, "reviewer@example.invalid");
        assertThat(claimed.accountId()).isEqualTo(claimant); assertThat(claimed.revision()).isEqualTo(2);
        assertThat(staff.forAccount(claimant).roles()).containsExactly("reviewer");
        assertThat(staff.pending("reviewer@example.invalid")).isNull();
        assertThat(access.claimPrepared(UUID.randomUUID(), "reviewer@example.invalid")).isNull();
        assertThat(get(client, TABLES.userAccountDetailsTableName(), invitePk("reviewer@example.invalid"), "ASSIGNMENT")).isEmpty();
        access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), null, "late@example.invalid", Set.of("tester"), Set.of(), false, 0));
        clock.advance(Duration.ofDays(14));
        assertThat(staff.pending("late@example.invalid")).isNull();
        // A verified claim refuses to merge an account that already holds an assignment.
        access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), null, "second@example.invalid", Set.of("tester"), Set.of(), false, 0));
        failsWith(() -> access.claimVerified(new AccountPrincipal(claimant, "c", "C", true), "second@example.invalid"), 409, "ACCESS_ASSIGNMENT_CONFLICT");
        access.claimVerified(new AccountPrincipal(UUID.randomUUID(), "n", "N", true), null);
        // The single-call methods other lanes use.
        var single = new StaffAccessRepository.Assignment(UUID.randomUUID(), UUID.randomUUID(), null, Set.of(), Set.of(), false, 1);
        staff.insert(single); staff.update(new StaffAccessRepository.Assignment(single.id(), single.accountId(), null, Set.of("admin"), Set.of(), false, 2));
        staff.event(owner.accountId(), "assign", single, staff.lock(single.id()));
        assertThat(staff.lock(single.id()).revision()).isEqualTo(2);
        staff.mutationActor(owner.accountId());
    }

    @Test void roleLabelsOwnersAndTheOwnerCheckComeFromTheControlItemsAndThePermanentAllocator() {
        DomainDynamoCatalog.catalog().step(2).apply(client, TABLES);
        UUID ownerId = admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
        UUID admin = admittedAccount("s-a", "admin@example.invalid", "Admin", null), learner = admittedAccount("s-l", "learner@example.invalid", "Learner", null);
        access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), admin, null, Set.of("admin", "tester"), Set.of(), false, 0));
        var aliases = new StaffAliases(new DynamoStaffAliasStore(client, TABLES, clock), access, staff);
        assertThat(aliases.label(ownerId)).isEqualTo("Owner");
        assertThat(aliases.label(admin)).isEqualTo("Admin 1");
        assertThat(aliases.label(learner)).isEqualTo("Learner 1");
        assertThat(aliases.label(UUID.randomUUID())).isEqualTo("Learner 2");
        assertThat(aliases.label(admin)).isEqualTo("Admin 1");
        assertThat(aliases.label(null)).isEqualTo("Unknown");
        assertThat(staff.owners()).containsExactly(ownerId);
        assertThat(aliases.seesIdentities(owner)).isTrue();
        assertThat(aliases.seesIdentities(new AccountPrincipal(learner, "l", "L", true))).isFalse();
    }

    @Test void theDirectoriesListLookUpAndDescribeAdmittedEnabledAccountsOnly() {
        UUID zoe = admittedAccount("s-z", "Zoe@example.invalid", "Zoe", "zoe@example.invalid"), amy = admittedAccount("s-m", "amy@example.invalid", "Amy Person", "amy@example.invalid");
        clock.advance(Duration.ofSeconds(1));
        var google = new CognitoIdentity(ISSUER, "s-m-google", UUID.randomUUID().toString(), "Google_77", "Amy", clock.instant(), "amy@example.invalid");
        registry.admit(google, "g".repeat(43));
        var waiting = new CognitoIdentity(ISSUER, "s-w", UUID.randomUUID().toString(), "wait@example.invalid", "Waiting", clock.instant(), null);
        failsWith(() -> registry.admit(waiting, "w".repeat(43)), 403, "ACCOUNT_ADMISSION_REQUIRED");
        var store = new DynamoAccountDirectoryStore(client, TABLES, clock);
        var directory = new AccountDirectory(new StaticListableBeanFactory().getBeanProvider(com.lookahead.domain.security.IdentitySettings.class), store);
        assertThat(directory.list("", 0).accounts()).extracting(AccountDirectory.Account::accountId).containsExactly(amy, zoe);
        assertThat(directory.list("PERSON", 0).accounts()).extracting(AccountDirectory.Account::username).containsExactly("amy@example.invalid");
        assertThat(directory.list("zoe@", 0).accounts()).extracting(AccountDirectory.Account::email).containsExactly("zoe@example.invalid");
        assertThat(directory.list("", 1).accounts()).isEmpty(); assertThat(directory.list("", 1).hasNext()).isFalse();
        assertThat(directory.lookup("google_77").accountId()).isEqualTo(amy);
        assertThat(directory.lookup("ZOE@example.invalid").accountId()).isEqualTo(zoe);
        failsWith(() -> directory.lookup("wait@example.invalid"), 404, "ACCOUNT_NOT_FOUND");
        assertThat(directory.byId(zoe).displayName()).isEqualTo("Zoe");
        failsWith(() -> directory.byId(accounts.accountOf(ISSUER, "s-w")), 404, "ACCOUNT_NOT_FOUND");
        store.ensureSubject(UUID.randomUUID());
        // The owner's row facts.
        var beans = new StaticListableBeanFactory();
        beans.addBean("tests", (TestAccountRegistryLookup) id -> id.equals(zoe));
        beans.addBean("memberships", new MembershipFacts() {
            @Override public boolean hasOpenPurchases(UUID account) { return account.equals(amy); }
            @Override public boolean hasPaidMembership(UUID account) { return false; }
        });
        var facts = new DynamoAdminDirectoryStore(client, TABLES, beans.getBeanProvider(TestAccountRegistryLookup.class), beans.getBeanProvider(MembershipFacts.class), clock);
        assertThat(facts.author(amy)).isFalse(); assertThat(facts.testAccount(zoe)).isTrue(); assertThat(facts.testAccount(amy)).isFalse();
        assertThat(facts.openPurchases(amy)).isTrue(); assertThat(facts.openPurchases(zoe)).isFalse();
        assertThat(facts.grants(amy)).isFalse();
        new DynamoAccountStore(client, TABLES, clock).grant(amy, "learn:docker");
        assertThat(facts.grants(amy)).isTrue();
        assertThat(facts.sameEmailAccounts(amy, "amy@example.invalid")).isZero(); assertThat(facts.sameEmailAccounts(amy, null)).isZero();
        assertThat(facts.signInMethods(amy)).extracting(m -> m.get("provider")).containsExactly("cognito", "google");
        assertThat(facts.github(amy)).isNull();
        var links = new LinkedHashMap<String, String>(); for (String kind : com.lookahead.domain.cloud.ProfileLinkPolicy.KINDS) links.put(kind, null); links.put("github", "https://github.com/amy");
        registry.links(google, "g".repeat(43), links);
        assertThat(facts.github(amy)).isEqualTo("https://github.com/amy");
        // Removed accounts leave the list; disabled ones stay.
        var removal = new DynamoAccountRemovalStore(client, TABLES, staff, beans.getBeanProvider(TestAccountRegistryLookup.class),
            beans.getBeanProvider(LearningDataErasure.class), beans.getBeanProvider(MembershipErasure.class), clock);
        UUID ownerId = admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
        assertThat(facts.author(ownerId)).isTrue();
        assertThatThrownBy(() -> removal.remove(ownerId, zoe)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("TEST_ACCOUNT_ROUTE_REQUIRED"));
        removal.remove(ownerId, amy);
        assertThat(directory.list("", 0).accounts()).extracting(AccountDirectory.Account::accountId).containsExactly(ownerId, zoe);
        failsWith(() -> directory.lookup("google_77"), 404, "ACCOUNT_NOT_FOUND");
    }

    @Test void grantsSubjectsAndTheLocalSeedAreInsertOnlyAndExpiryIsCheckedInCode() {
        var store = new DynamoAccountStore(client, TABLES, clock);
        UUID subject = UUID.randomUUID();
        store.ensureSubject(subject); store.ensureSubject(subject);
        assertThat(get(client, TABLES.userAccountDetailsTableName(), account(subject), "META").get("lastPlanNumber").n()).isEqualTo("0");
        store.grant(subject, "learn:docker"); store.grant(subject, "learn:docker");
        client.putItem(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
            .item(item(account(subject), grantSk("grow:system-design"), attrs("topicId", s("grow:system-design"), "validUntil", time(NOW.plusSeconds(60))))).build());
        var repository = new AccountRepository(store, staff, catalog);
        assertThat(repository.findTopicGrants(subject)).containsExactlyInAnyOrder("learn:docker", "grow:system-design");
        clock.advance(Duration.ofSeconds(60));
        assertThat(repository.findTopicGrants(subject)).containsExactly("learn:docker");
        assertThat(repository.isEnabled(subject)).isFalse();
        UUID ownerId = admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
        assertThat(repository.findTopicGrants(ownerId)).contains(CatalogIds.STAFF_DOCUMENTS_SCOPE, "learn:docker", "grow:system-design");
        assertThat(store.authorEnabled(subject)).isFalse();
        // The local-test seed: ten learners, learner09 with two topics only, nothing restored on a second run.
        var seed = new DynamoFixtureSeedStore(client, TABLES, clock);
        var environment = new MockEnvironment().withProperty("app.local-test.author-enabled", "true");
        var seeder = new DomainFixtureSeeder(seed, repository, catalog, environment, null);
        seeder.run(null);
        UUID learner1 = UUID.nameUUIDFromBytes("lookahead-local-test:learner01".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID learner9 = UUID.nameUUIDFromBytes("lookahead-local-test:learner09".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(store.grantTopics(learner1)).containsExactlyInAnyOrder("learn:docker", "grow:system-design");
        assertThat(store.grantTopics(learner9)).isEmpty();
        assertThat(store.grantTopics(LocalAuthorAccess.ACCOUNT_ID)).hasSize(2);
        new DynamoAccountStore(client, TABLES, clock).grant(learner1, "learn:docker");
        var tx = com.lookahead.domain.persistence.dynamodb.DynamoWrites.transaction(client);
        tx.delete(TABLES.userAccountDetailsTableName(), key(account(learner1), grantSk("learn:docker")), null); tx.commit();
        seeder.run(null);
        assertThat(store.grantTopics(learner1)).containsExactly("grow:system-design");
        when(catalog.allTopicIds()).thenReturn(Set.of());
        assertThatIllegalStateException().isThrownBy(() -> seeder.run(null));
    }

    @Test void ownerMaintenanceDowngradesAndDeletesFreeLearnersOnceWithRecordsKept() {
        UUID ownerId = admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
        var actor = new AccountPrincipal(ownerId, "owner", "Owner", true, true);
        UUID learner = admittedAccount("s-dl", "dl@example.invalid", "Deleted Learner", null);
        access.save(owner, new StaffAccessService.Change(UUID.randomUUID(), learner, null, Set.of("tester"), Set.of(), false, 0));
        new DynamoAccountStore(client, TABLES, clock).grant(learner, "learn:docker");
        var ended = new ArrayList<UUID>(); var erased = new ArrayList<UUID>();
        var beans = new StaticListableBeanFactory();
        beans.addBean("memberships", (MembershipErasure) ended::add);
        beans.addBean("plans", (LearningDataErasure) erased::add);
        beans.addBean("facts", new MembershipFacts() {
            @Override public boolean hasOpenPurchases(UUID account) { return false; }
            @Override public boolean hasPaidMembership(UUID account) { return false; }
        });
        var store = new DynamoAccountMaintenanceStore(client, TABLES, staff, beans.getBeanProvider(TestAccountRegistryLookup.class), beans.getBeanProvider(MembershipFacts.class),
            beans.getBeanProvider(MembershipErasure.class), beans.getBeanProvider(LearningDataErasure.class), clock);
        var directory = new AccountDirectory(new StaticListableBeanFactory().getBeanProvider(com.lookahead.domain.security.IdentitySettings.class), new DynamoAccountDirectoryStore(client, TABLES, clock));
        var environment = new MockEnvironment().withProperty("app.deployment-environment", "local").withProperty("app.test-accounts.enabled", "true").withProperty("app.local-test.author-enabled", "true");
        environment.setActiveProfiles("accounts", "local-test");
        var policy = new TestAccountPolicy(environment);
        assertThat(policy.local()).isTrue();
        var identity = mock(TestIdentityOperator.class);
        var maintenance = new RealAccountMaintenance(store, access, directory, policy, identity);
        failsWith(() -> maintenance.downgrade(actor, learner, new RealAccountMaintenance.Confirmation("nope")), 422, "ACCOUNT_CONFIRMATION_REQUIRED");
        failsWith(() -> maintenance.downgrade(actor, ownerId, new RealAccountMaintenance.Confirmation("DOWNGRADE")), 403, "OWNER_ACCOUNT_PROTECTED");
        assertThat(store.privileged(learner)).isTrue(); assertThat(store.grants(learner)).isTrue();
        maintenance.downgrade(actor, learner, new RealAccountMaintenance.Confirmation("DOWNGRADE"));
        assertThat(store.privileged(learner)).isFalse(); assertThat(store.grants(learner)).isFalse(); assertThat(ended).containsExactly(learner);
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(learner), "MAINTENANCE#")).singleElement().satisfies(e -> assertThat(e.get("action").s()).isEqualTo("downgrade"));
        assertThat(store.alreadyDeleted(learner)).isFalse();
        UUID unknown = UUID.randomUUID();
        failsWith(() -> maintenance.deleteLearner(actor, unknown, new RealAccountMaintenance.Confirmation("DELETE")), 404, "ACCOUNT_NOT_FOUND");
        maintenance.deleteLearner(actor, learner, new RealAccountMaintenance.Confirmation("DELETE"));
        assertThat(erased).containsExactly(learner); assertThat(store.alreadyDeleted(learner)).isTrue();
        maintenance.deleteLearner(actor, learner, new RealAccountMaintenance.Confirmation("DELETE"));
        assertThat(erased).hasSize(1);
        assertThat(store.subjectExists(learner)).isTrue(); assertThat(store.lockSubject(unknown)).isFalse();
        store.ensureSubject(unknown); assertThat(store.subjectExists(unknown)).isTrue();
        assertThat(store.author(ownerId)).isTrue(); assertThat(store.testAccount(learner)).isFalse(); assertThat(store.openPurchases(learner)).isFalse();
    }
}
