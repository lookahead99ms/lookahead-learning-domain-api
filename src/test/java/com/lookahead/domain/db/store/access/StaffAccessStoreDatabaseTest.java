package com.lookahead.domain.db.store.access;

import static com.lookahead.domain.db.store.signin.AccountItems.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipErasure;
import com.lookahead.domain.db.store.membership.MembershipFacts;
import com.lookahead.domain.db.store.membership.MembershipGrantsStore;
import com.lookahead.domain.db.store.plan.StudyPlanAccountErasure;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.signin.SignInStore;
import com.lookahead.domain.db.store.signin.SignInStoreLocalFixture;
import com.lookahead.domain.db.store.testaccounts.TestAccountFixtureSeedStore;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import com.lookahead.domain.dto.AccountMaintenanceConfirmation;
import com.lookahead.domain.dto.DevAccessAdmission;
import com.lookahead.domain.dto.StaffAccessChange;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.job.TestAccountFixtureSeedJob;
import com.lookahead.domain.local.identity.IdentityOperatorClient;
import com.lookahead.domain.model.AccountDirectoryModels;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoCatalog;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AccountDirectoryService;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.access.AccountMaintenanceService;
import com.lookahead.domain.service.access.AccountRemovalSteps;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.access.DevAccessService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.access.StaffAliasService;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import com.lookahead.domain.util.CatalogIds;
import com.lookahead.domain.validator.AccountProfileLinkValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Real DynamoDB Local: staff access, role labels, the directories, grants, the seed and owner
 * maintenance on the lane A items.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class StaffAccessStoreDatabaseTest {
  /**
   * DLV-1016: the removal decisions are the service's; these tests run its stored steps over the
   * real store.
   */
  private static AccountRemovalSteps steps(AccountRemovalStore store) {
    return new AccountRemovalSteps(store);
  }

  static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_Synthetic";
  private static DynamoDbClient client;
  private static final DynamoTableNames TABLES = SignInStoreLocalFixture.TABLES;
  private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
  final MutableClock clock = new MutableClock(NOW);
  final AccountPrincipal owner =
      new AccountPrincipal(
          UUID.randomUUID(), "owner", "Owner", true, true, "owner@example.invalid");
  StaffAccessStore staff;
  StaffAccessService access;
  AccountAggregateStore accounts;
  SignInService signInService;
  DevAccessStore devAccess;
  AccountCatalogService catalog;

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
    // The label counters too: this class bootstraps them itself and expects the first numbers.
    SignInStoreLocalFixture.purge(client, TABLES.userAccountDetailsTableName(), "STAFF_ROLE#");
    SignInStoreLocalFixture.purge(
        client, TABLES.administratorAuditEventsTableName(), "STAFF_ROLE#");
    SignInStoreLocalFixture.seedOwner(client, ISSUER, "owner-subject");
    staff = new StaffAccessStore(client, TABLES, new ObjectMapper(), clock);
    catalog = mock(AccountCatalogService.class);
    when(catalog.allTopicIds()).thenReturn(Set.of("learn:docker", "grow:system-design"));
    access =
        new StaffAccessService(
            staff,
            new AuthorPreviewLocalService(
                new MockEnvironment().withProperty("app.deployment-environment", "dev")),
            catalog);
    accounts = new AccountAggregateStore(client, TABLES, clock);
    signInService = new SignInService(new SignInStore(client, TABLES, clock), null);
    devAccess = new DevAccessStore(client, TABLES, clock);
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

  /**
   * An admitted DEV account with a username and a display name, through the registry and the
   * admission store.
   */
  UUID admittedAccount(String subject, String username, String displayName, String email) {
    var identity =
        new SignInIdentity(
            ISSUER,
            subject,
            UUID.randomUUID().toString(),
            username,
            displayName,
            clock.instant(),
            email);
    // DLV-970: the protected owner identity admits itself at first contact; everyone else waits for
    // the owner.
    if (subject.equals("owner-subject")) {
      signInService.admit(identity, "p".repeat(43));
      return accounts.accountOf(ISSUER, subject);
    }
    failsWith(
        () -> signInService.admit(identity, "p".repeat(43)), 403, "ACCOUNT_ADMISSION_REQUIRED");
    UUID id = accounts.accountOf(ISSUER, subject);
    new DevAccessService(devAccess, access, staff)
        .admit(owner, new DevAccessAdmission(id.toString(), List.of()));
    return id;
  }

  @Test
  void staffAssignmentsAreSavedUnderTheirRevisionWithPointersInvitationsAndEvents() {
    UUID person = UUID.randomUUID(), id = UUID.randomUUID();
    var saved =
        access.save(
            owner,
            new StaffAccessChange(
                id, person, null, Set.of("admin"), Set.of("learn:docker"), false, 0));
    assertThat(saved.revision()).isEqualTo(1);
    assertThat(staff.forAccount(person)).isEqualTo(saved);
    assertThat(staff.lock(id)).isEqualTo(saved);
    assertThat(staff.list()).containsExactly(saved);
    failsWith(
        () ->
            access.save(
                owner,
                new StaffAccessChange(id, person, null, Set.of("tester"), Set.of(), false, 0)),
        409,
        "ACCESS_ASSIGNMENT_CONFLICT");
    // A second assignment for the same person: the pointer sentinel refuses it.
    failsWith(
        () ->
            access.save(
                owner,
                new StaffAccessChange(
                    UUID.randomUUID(), person, null, Set.of(), Set.of(), true, 0)),
        409,
        "ACCESS_ASSIGNMENT_CONFLICT");
    clock.advance(Duration.ofMinutes(1));
    var updated =
        access.save(
            owner, new StaffAccessChange(id, person, null, Set.of("tester"), Set.of(), true, 1));
    assertThat(updated.revision()).isEqualTo(2);
    assertThat(staff.forAccount(person).roles()).containsExactly("tester");
    assertThat(
            query(client, TABLES.administratorAuditEventsTableName(), staffEventPk(id), "EVENT#"))
        .hasSize(2)
        .allSatisfy(e -> {
          assertThat(e.get("actorId").s()).isEqualTo(owner.accountId().toString());
          assertThat(e.get("action").s()).isEqualTo("assign");
        });
    assertThat(access.permissions(new AccountPrincipal(person, "p", "P", true)).feedbackRole())
        .isEqualTo("tester");
    // An invitation by email, claimed on first contact, never twice, and expired after 14 days.
    UUID invite = UUID.randomUUID();
    clock.advance(Duration.ofMinutes(1));
    access.save(
        owner,
        new StaffAccessChange(
            invite, null, "Reviewer@Example.invalid", Set.of("reviewer"), Set.of(), false, 0));
    failsWith(
        () ->
            access.save(
                owner,
                new StaffAccessChange(
                    UUID.randomUUID(),
                    null,
                    "reviewer@example.invalid",
                    Set.of("admin"),
                    Set.of(),
                    false,
                    0)),
        409,
        "ACCESS_ASSIGNMENT_CONFLICT");
    assertThat(staff.pending("reviewer@example.invalid").assignment().id()).isEqualTo(invite);
    assertThat(
            StaffAccessService.liveInvitation(
                staff.pending("reviewer@example.invalid"), "reviewer@example.invalid"))
        .isTrue();
    clock.advance(Duration.ofMinutes(1));
    var later =
        access.save(
            owner,
            new StaffAccessChange(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                Set.of(),
                Set.of("learn:docker"),
                false,
                0));
    assertThat(staff.list())
        .extracting(StaffAccess.Assignment::id)
        .containsExactly(later.id(), invite, id);
    UUID claimant = UUID.randomUUID();
    var claimed = access.claimPrepared(claimant, "reviewer@example.invalid");
    assertThat(claimed.accountId()).isEqualTo(claimant);
    assertThat(claimed.revision()).isEqualTo(2);
    assertThat(staff.forAccount(claimant).roles()).containsExactly("reviewer");
    assertThat(
            StaffAccessService.liveInvitation(
                staff.pending("reviewer@example.invalid"), "reviewer@example.invalid"))
        .isFalse();
    assertThat(access.claimPrepared(UUID.randomUUID(), "reviewer@example.invalid")).isNull();
    assertThat(
            get(
                client,
                TABLES.userAccountDetailsTableName(),
                invitePk("reviewer@example.invalid"),
                "ASSIGNMENT"))
        .isEmpty();
    access.save(
        owner,
        new StaffAccessChange(
            UUID.randomUUID(), null, "late@example.invalid", Set.of("tester"), Set.of(), false, 0));
    clock.advance(Duration.ofDays(14));
    assertThat(
            StaffAccessService.liveInvitation(
                staff.pending("late@example.invalid"), "late@example.invalid"))
        .isFalse();
    // A verified claim refuses to merge an account that already holds an assignment.
    access.save(
        owner,
        new StaffAccessChange(
            UUID.randomUUID(),
            null,
            "second@example.invalid",
            Set.of("tester"),
            Set.of(),
            false,
            0));
    failsWith(
        () ->
            access.claimVerified(
                new AccountPrincipal(claimant, "c", "C", true), "second@example.invalid"),
        409,
        "ACCESS_ASSIGNMENT_CONFLICT");
    access.claimVerified(new AccountPrincipal(UUID.randomUUID(), "n", "N", true), null);
    // The single-call methods other lanes use.
    var single =
        new StaffAccess.Assignment(
            UUID.randomUUID(), UUID.randomUUID(), null, Set.of(), Set.of(), false, 1);
    staff.insert(single);
    staff.update(
        new StaffAccess.Assignment(
            single.id(), single.accountId(), null, Set.of("admin"), Set.of(), false, 2));
    staff.event(owner.accountId(), "assign", single, staff.lock(single.id()));
    assertThat(staff.lock(single.id()).revision()).isEqualTo(2);
    // DLV-1016: an update of an assignment that is not there is the store's conflict (the answer of
    // a stale revision), with nothing written.
    var missing =
        new StaffAccess.Assignment(
            UUID.randomUUID(), UUID.randomUUID(), null, Set.of("admin"), Set.of(), false, 2);
    assertThatThrownBy(() -> staff.update(missing))
        .isInstanceOfSatisfying(
            DynamoWrites.Conflict.class,
            lost -> {
              assertThat(lost.conflict()).isTrue();
              assertThat(lost.reasons()).containsExactly("ConditionalCheckFailed");
            });
    assertThat(staff.lock(missing.id())).isNull();
    assertThat(staff.forAccount(missing.accountId())).isNull();
  }

  @Test
  void roleLabelsOwnersAndTheOwnerCheckComeFromTheControlItemsAndThePermanentAllocator() {
    DomainDynamoCatalog.catalog().step(2).apply(client, TABLES);
    UUID ownerId =
        admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
    UUID admin = admittedAccount("s-a", "admin@example.invalid", "Admin", null),
        learner = admittedAccount("s-l", "learner@example.invalid", "Learner", null);
    access.save(
        owner,
        new StaffAccessChange(
            UUID.randomUUID(), admin, null, Set.of("admin", "tester"), Set.of(), false, 0));
    var aliases = new StaffAliasService(new StaffAliasStore(client, TABLES, clock), access, staff);
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

  @Test
  void theDirectoriesListLookUpAndDescribeAdmittedEnabledAccountsOnly() {
    UUID zoe = admittedAccount("s-z", "Zoe@example.invalid", "Zoe", "zoe@example.invalid"),
        amy = admittedAccount("s-m", "amy@example.invalid", "Amy Person", "amy@example.invalid");
    clock.advance(Duration.ofSeconds(1));
    var google =
        new SignInIdentity(
            ISSUER,
            "s-m-google",
            UUID.randomUUID().toString(),
            "Google_77",
            "Amy",
            clock.instant(),
            "amy@example.invalid");
    signInService.admit(google, "g".repeat(43));
    var waiting =
        new SignInIdentity(
            ISSUER,
            "s-w",
            UUID.randomUUID().toString(),
            "wait@example.invalid",
            "Waiting",
            clock.instant(),
            null);
    failsWith(
        () -> signInService.admit(waiting, "w".repeat(43)), 403, "ACCOUNT_ADMISSION_REQUIRED");
    var store = new AccountDirectoryStore(client, TABLES, clock);
    var directory = new AccountDirectoryService(store, access);
    assertThat(directory.list("", 0).accounts())
        .extracting(AccountDirectoryModels.Account::accountId)
        .containsExactly(amy, zoe);
    assertThat(directory.list("PERSON", 0).accounts())
        .extracting(AccountDirectoryModels.Account::username)
        .containsExactly("amy@example.invalid");
    assertThat(directory.list("zoe@", 0).accounts())
        .extracting(AccountDirectoryModels.Account::email)
        .containsExactly("zoe@example.invalid");
    assertThat(directory.list("", 1).accounts()).isEmpty();
    assertThat(directory.list("", 1).hasNext()).isFalse();
    assertThat(directory.lookup(owner, "google_77").accountId()).isEqualTo(amy);
    assertThat(directory.lookup(owner, "ZOE@example.invalid").accountId()).isEqualTo(zoe);
    failsWith(() -> directory.lookup(owner, "wait@example.invalid"), 404, "ACCOUNT_NOT_FOUND");
    assertThat(directory.byId(zoe).displayName()).isEqualTo("Zoe");
    failsWith(() -> directory.byId(accounts.accountOf(ISSUER, "s-w")), 404, "ACCOUNT_NOT_FOUND");
    store.ensureSubject(UUID.randomUUID());
    // The owner's row facts.
    var beans = new StaticListableBeanFactory();
    beans.addBean("tests", (TestAccountRegistry) id -> id.equals(zoe));
    beans.addBean(
        "memberships",
        new MembershipFacts() {
          @Override
          public boolean hasOpenPurchases(UUID account) {
            return account.equals(amy);
          }

          @Override
          public boolean hasPaidMembership(UUID account) {
            return false;
          }
        });
    var facts =
        new AccountAdminDirectoryStore(
            client,
            TABLES,
            beans.getBeanProvider(TestAccountRegistry.class),
            beans.getBeanProvider(MembershipFacts.class),
            clock);
    assertThat(facts.author(amy)).isFalse();
    assertThat(facts.testAccount(zoe)).isTrue();
    assertThat(facts.testAccount(amy)).isFalse();
    assertThat(facts.openPurchases(amy)).isTrue();
    assertThat(facts.openPurchases(zoe)).isFalse();
    assertThat(facts.grants(amy)).isFalse();
    new AccountSubjectStore(client, TABLES, clock).grant(amy, "learn:docker");
    assertThat(facts.grants(amy)).isTrue();
    assertThat(facts.sameEmailAccounts(amy, "amy@example.invalid")).isZero();
    assertThat(facts.sameEmailAccounts(amy, null)).isZero();
    assertThat(facts.signInMethods(amy))
        .extracting(m -> m.get("provider"))
        .containsExactly("cognito", "google");
    assertThat(facts.github(amy)).isNull();
    var links = new LinkedHashMap<String, String>();
    for (String kind : AccountProfileLinkValidator.KINDS) links.put(kind, null);
    links.put("github", "https://github.com/amy");
    signInService.links(google, "g".repeat(43), links);
    assertThat(facts.github(amy)).isEqualTo("https://github.com/amy");
    // Removed accounts leave the list; disabled ones stay.
    var removal =
        new AccountRemovalStore(
            client,
            TABLES,
            staff,
            beans.getBeanProvider(TestAccountRegistry.class),
            beans.getBeanProvider(StudyPlanAccountErasure.class),
            beans.getBeanProvider(MembershipErasure.class),
            clock);
    UUID ownerId =
        admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
    assertThat(facts.author(ownerId)).isTrue();
    assertThatThrownBy(() -> steps(removal).removeAccount(ownerId, zoe))
        .isInstanceOfSatisfying(
            AccountFailure.class,
            f -> assertThat(f.code()).isEqualTo("TEST_ACCOUNT_ROUTE_REQUIRED"));
    steps(removal).removeAccount(ownerId, amy);
    assertThat(directory.list("", 0).accounts())
        .extracting(AccountDirectoryModels.Account::accountId)
        .containsExactly(ownerId, zoe);
    failsWith(() -> directory.lookup(owner, "google_77"), 404, "ACCOUNT_NOT_FOUND");
  }

  @Test
  void grantsSubjectsAndTheLocalSeedAreInsertOnlyAndExpiryIsCheckedInCode() {
    var store = new AccountSubjectStore(client, TABLES, clock);
    UUID subject = UUID.randomUUID();
    store.ensureSubject(subject);
    store.ensureSubject(subject);
    assertThat(
            get(client, TABLES.userAccountDetailsTableName(), account(subject), "META")
                .get("lastPlanNumber")
                .n())
        .isEqualTo("0");
    store.grant(subject, "learn:docker");
    store.grant(subject, "learn:docker");
    client.putItem(
        PutItemRequest.builder()
            .tableName(TABLES.userAccountDetailsTableName())
            .item(
                item(
                    account(subject),
                    grantSk("grow:system-design"),
                    attrs(
                        "topicId",
                        s("grow:system-design"),
                        "validUntil",
                        time(NOW.plusSeconds(60)))))
            .build());
    var repository =
        new AccountGrantService(
            store, staff, catalog, new MembershipGrantsStore(client, TABLES, new ObjectMapper()));
    assertThat(repository.findTopicGrants(subject))
        .containsExactlyInAnyOrder("learn:docker", "grow:system-design");
    clock.advance(Duration.ofSeconds(60));
    assertThat(repository.findTopicGrants(subject)).containsExactly("learn:docker");
    assertThat(repository.isEnabled(subject)).isFalse();
    UUID ownerId =
        admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
    assertThat(repository.findTopicGrants(ownerId))
        .contains(CatalogIds.STAFF_DOCUMENTS_SCOPE, "learn:docker", "grow:system-design");
    assertThat(store.authorEnabled(subject)).isFalse();
    // The local-test seed: ten learners, learner09 with two topics only, nothing restored on a
    // second run.
    var seed = new TestAccountFixtureSeedStore(client, TABLES, clock);
    // The job checks its fixture settings when it is built (DLV-1016), so this is the seeded Local
    // environment it runs in.
    var environment =
        new MockEnvironment()
            .withProperty("app.deployment-environment", "local")
            .withProperty("app.local-test.seed-enabled", "true")
            .withProperty("app.local-test.author-enabled", "true");
    environment.setActiveProfiles("accounts", "local-test");
    var seeder = new TestAccountFixtureSeedJob(seed, repository, catalog, environment);
    seeder.run(null);
    UUID learner1 =
        UUID.nameUUIDFromBytes("lookahead-local-test:learner01".getBytes(StandardCharsets.UTF_8));
    UUID learner9 =
        UUID.nameUUIDFromBytes("lookahead-local-test:learner09".getBytes(StandardCharsets.UTF_8));
    assertThat(store.grantTopics(learner1))
        .containsExactlyInAnyOrder("learn:docker", "grow:system-design");
    assertThat(store.grantTopics(learner9)).isEmpty();
    assertThat(store.grantTopics(AuthorPreviewLocalService.ACCOUNT_ID)).hasSize(2);
    new AccountSubjectStore(client, TABLES, clock).grant(learner1, "learn:docker");
    var tx = DynamoWrites.transaction(client);
    tx.delete(
        TABLES.userAccountDetailsTableName(),
        key(account(learner1), grantSk("learn:docker")),
        null);
    tx.commit();
    seeder.run(null);
    assertThat(store.grantTopics(learner1)).containsExactly("grow:system-design");
    when(catalog.allTopicIds()).thenReturn(Set.of());
    assertThatIllegalStateException().isThrownBy(() -> seeder.run(null));
  }

  @Test
  void ownerMaintenanceDowngradesAndDeletesFreeLearnersOnceWithRecordsKept() {
    UUID ownerId =
        admittedAccount("owner-subject", "owner@example.invalid", "Owner", "owner@example.invalid");
    var actor = new AccountPrincipal(ownerId, "owner", "Owner", true, true);
    UUID learner = admittedAccount("s-dl", "dl@example.invalid", "Deleted Learner", null);
    access.save(
        owner,
        new StaffAccessChange(
            UUID.randomUUID(), learner, null, Set.of("tester"), Set.of(), false, 0));
    new AccountSubjectStore(client, TABLES, clock).grant(learner, "learn:docker");
    var ended = new ArrayList<UUID>();
    var erased = new ArrayList<UUID>();
    var beans = new StaticListableBeanFactory();
    beans.addBean("memberships", (MembershipErasure) ended::add);
    beans.addBean("plans", (StudyPlanAccountErasure) erased::add);
    beans.addBean(
        "facts",
        new MembershipFacts() {
          @Override
          public boolean hasOpenPurchases(UUID account) {
            return false;
          }

          @Override
          public boolean hasPaidMembership(UUID account) {
            return false;
          }
        });
    var store =
        new AccountMaintenanceStore(
            client,
            TABLES,
            staff,
            beans.getBeanProvider(TestAccountRegistry.class),
            beans.getBeanProvider(MembershipFacts.class),
            beans.getBeanProvider(MembershipErasure.class),
            beans.getBeanProvider(StudyPlanAccountErasure.class),
            clock);
    var directory =
        new AccountDirectoryService(new AccountDirectoryStore(client, TABLES, clock), access);
    var environment =
        new MockEnvironment()
            .withProperty("app.deployment-environment", "local")
            .withProperty("app.test-accounts.enabled", "true")
            .withProperty("app.local-test.author-enabled", "true");
    environment.setActiveProfiles("accounts", "local-test");
    var policy = new TestAccountModeService(environment);
    assertThat(policy.local()).isTrue();
    var identity = mock(IdentityOperatorClient.class);
    var maintenance =
        new AccountMaintenanceService(store, store, access, directory, policy, identity);
    failsWith(
        () -> maintenance.downgrade(actor, learner, new AccountMaintenanceConfirmation("nope")),
        422,
        "ACCOUNT_CONFIRMATION_REQUIRED");
    failsWith(
        () ->
            maintenance.downgrade(actor, ownerId, new AccountMaintenanceConfirmation("DOWNGRADE")),
        403,
        "OWNER_ACCOUNT_PROTECTED");
    assertThat(store.privileged(learner)).isTrue();
    assertThat(store.grants(learner)).isTrue();
    maintenance.downgrade(actor, learner, new AccountMaintenanceConfirmation("DOWNGRADE"));
    assertThat(store.privileged(learner)).isFalse();
    assertThat(store.grants(learner)).isFalse();
    assertThat(ended).containsExactly(learner);
    assertThat(
            query(
                client,
                TABLES.administratorAuditEventsTableName(),
                account(learner),
                "MAINTENANCE#"))
        .singleElement()
        .satisfies(e -> assertThat(e.get("action").s()).isEqualTo("downgrade"));
    assertThat(store.alreadyDeleted(learner)).isFalse();
    UUID unknown = UUID.randomUUID();
    failsWith(
        () ->
            maintenance.deleteLearner(actor, unknown, new AccountMaintenanceConfirmation("DELETE")),
        404,
        "ACCOUNT_NOT_FOUND");
    maintenance.deleteLearner(actor, learner, new AccountMaintenanceConfirmation("DELETE"));
    assertThat(erased).containsExactly(learner);
    assertThat(store.alreadyDeleted(learner)).isTrue();
    maintenance.deleteLearner(actor, learner, new AccountMaintenanceConfirmation("DELETE"));
    assertThat(erased).hasSize(1);
    assertThat(store.subjectExists(learner)).isTrue();
    assertThat(store.subjectExists(unknown)).isFalse();
    store.ensureSubject(unknown);
    assertThat(store.subjectExists(unknown)).isTrue();
    assertThat(store.author(ownerId)).isTrue();
    assertThat(store.testAccount(learner)).isFalse();
    assertThat(store.openPurchases(learner)).isFalse();
  }
}
