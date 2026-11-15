package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.StaffAccessRepository;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.StaffAliases;
import com.lookahead.domain.access.StaffPermissions;
import com.lookahead.domain.membership.*;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.time.*;
import java.util.*;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.membership.DynamoLocalFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DEV test accounts on DynamoDB Local: roster, creations, deletion, reset, restore and test sessions through {@link DynamoTestAccountStore}. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoTestAccountsDatabaseTest {
    private static DynamoDbClient client;
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final String ISSUER = "https://cognito.example/pool";
    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock(NOW);
    private final TestAccountPolicy policy = new TestAccountPolicy(TestAccountPolicyTest.cloud("dev"));
    private final Fakes fakes = new Fakes();
    private final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true);
    private final AccountPrincipal adminA = new AccountPrincipal(UUID.randomUUID(), "admin-a", "Admin A", true);
    private final AccountPrincipal adminB = new AccountPrincipal(UUID.randomUUID(), "admin-b", "Admin B", true);
    private LocalAuthorAccess ownerAccess;
    private StaffAccessRepository repository;
    private ScenarioPlans plans;
    private SnapshotValidator catalog;

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    /** Lane A and lane B in memory: identities, admitted records, staff roles, grants and plans. */
    static final class Fakes implements AccountIdentityLookup, AccountRecordsMaintenance, PlanErasure, PlanActivityLookup, AccountGrantsLookup {
        final Map<String, UUID> identities = new HashMap<>();
        final Map<UUID, Admitted> admitted = new HashMap<>();
        final Set<UUID> owners = new HashSet<>();
        final Map<UUID, List<String>> roles = new HashMap<>();
        final Map<UUID, Set<String>> grants = new HashMap<>();
        final Set<UUID> withPlans = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        void person(AccountPrincipal who, String subject, boolean isOwner, List<String> staff) {
            identities.put(ISSUER + "|" + subject, who.accountId());
            admitted.put(who.accountId(), new Admitted(ISSUER, who.displayName(), true, true));
            if (isOwner) owners.add(who.accountId());
            roles.put(who.accountId(), staff);
        }
        @Override public UUID accountOf(String issuer, String subject) { return identities.get(issuer + "|" + subject); }
        @Override public Holder holder(String issuer, String subject) {
            UUID account = accountOf(issuer, subject);
            return account == null || !admitted.containsKey(account) ? null : new Holder(account, owners.contains(account));
        }
        @Override public Admitted admitted(UUID account) { return admitted.get(account); }
        @Override public List<String> staffRoles(UUID account) { return roles.getOrDefault(account, List.of()); }
        @Override public String displayName(UUID account) { var a = admitted.get(account); return a == null ? null : a.displayName(); }
        @Override public Instant staffAccessUpdatedAt(UUID account) { return null; }
        @Override public void admitTestAccount(UUID account, String username, String displayName, UUID admittedBy) {
            admitted.put(account, new Admitted(TestAccountRoster.TEST_ISSUER, displayName, true, true));
            identities.put(TestAccountRoster.TEST_ISSUER + "|" + account, account);
            calls.add("admit:" + username);
        }
        @Override public boolean hasAccountRecord(UUID account) { return admitted.containsKey(account); }
        @Override public void grantTopics(UUID account, Collection<String> topics) { grants.computeIfAbsent(account, k -> new TreeSet<>()).addAll(topics); }
        @Override public void clearAccessRecords(UUID account) { grants.remove(account); roles.remove(account); calls.add("clear:" + account); }
        @Override public void erase(UUID account) { withPlans.remove(account); calls.add("erase:" + account); }
        @Override public void resetPlans(UUID account) { withPlans.remove(account); calls.add("resetPlans:" + account); }
        @Override public boolean hasPlan(UUID account) { return withPlans.contains(account); }
        @Override public Instant latestPlanWriteAt(UUID account) { return null; }
        @Override public Set<String> grantedTopics(UUID account, Instant now) { return grants.getOrDefault(account, Set.of()); }
    }

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); ensureTables(client); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void seed() {
        purgeLaneC(client);
        for (var entry : TestAccountRoster.entries()) { purgeAccount(client, entry.accountId()); purge(client, TABLES.administratorAuditEventsTableName(), TestAccountItems.resetPk(entry.accountId())); }
        seedCatalog(client, json, NOW);
        for (var who : List.of(owner, adminA, adminB)) seedAccount(client, who.accountId(), NOW);
        fakes.person(owner, "sub-owner", true, List.of());
        fakes.person(adminA, "sub-admin-a", false, List.of("admin"));
        fakes.person(adminB, "sub-admin-b", false, List.of("admin"));
        ownerAccess = mock(LocalAuthorAccess.class);
        when(ownerAccess.allowed(owner)).thenReturn(true);
        repository = mock(StaffAccessRepository.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        for (var admin : List.of(adminA, adminB))
            when(repository.forAccount(admin.accountId())).thenReturn(new StaffAccessRepository.Assignment(UUID.randomUUID(), admin.accountId(), null, Set.of("admin"), Set.of(), false, 1));
        plans = mock(ScenarioPlans.class);
        catalog = mock(SnapshotValidator.class);
        when(catalog.allTopicIds()).thenReturn(Set.of("learn:hands-on-dsa", "learn:algorithmic-patterns", "learn:core-java"));
    }
    DynamoTestAccountStore store() { return storeOn(client); }
    DynamoTestAccountStore storeOn(DynamoDbClient on) { return new DynamoTestAccountStore(on, TABLES, json, fakes, fakes, fakes, fakes, clock); }
    /** A client that runs every call on the real one, so a test can act between a store's read and its commit. */
    static DynamoDbClient delegating(DynamoDbClient real) {
        return mock(DynamoDbClient.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY).defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(real)));
    }
    /** A checkout that commits a purchase on the account, as the membership store does: the row, its pointer and the META revision bump. */
    void checkout(UUID account) {
        var store = new DynamoMembershipStore(client, TABLES, json, clock);
        store.transact(owner.accountId(), work -> {
            work.lockAccount(account);
            var plan = work.definition("grow");
            work.insertPurchase(new MembershipStore.NewPurchase(UUID.randomUUID(), account, "grow", plan, "c".repeat(64), "race-" + UUID.randomUUID(), plan.path("priceCents").asInt(),
                    clock.instant(), clock.instant().plus(Duration.ofDays(365)), "checkout", account, null));
            return null;
        });
    }
    List<tools.jackson.databind.node.ObjectNode> purchases(UUID account) { return MembershipItems.purchases(client, TABLES, json, account); }
    static boolean open(tools.jackson.databind.node.ObjectNode p) { return MembershipItems.unset(p.path("supersededAt")); }
    static boolean hasParameter(software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest request, String prefix) {
        return request.transactStatements().stream().flatMap(st -> st.parameters().stream()).map(software.amazon.awssdk.services.dynamodb.model.AttributeValue::s)
                .anyMatch(v -> v != null && v.startsWith(prefix));
    }
    StaffAccessService staffFor(AccountPrincipal who) {
        var staff = mock(StaffAccessService.class);
        when(staff.permissions(any())).thenAnswer(call -> {
            AccountPrincipal actor = call.getArgument(0);
            return StaffPermissions.forRoles(actor.accountId().equals(owner.accountId()), actor.accountId().equals(owner.accountId()) ? Set.of() : Set.of("admin"));
        });
        if (!who.accountId().equals(owner.accountId())) doThrow(new AccountFailure(403, "OWNER_REQUIRED", "Owner only")).when(staff).requireOwner(any());
        return staff;
    }
    MembershipService memberships(StaffAccessService staff) {
        return new MembershipService(new DynamoMembershipStore(client, TABLES, json, clock), json,
                new OwnerApprovalPaymentGateway(new MockStripePaymentGateway(new DynamoMockPaymentStore(client, TABLES), "success")), staff, clock);
    }
    TestAccountsService service(AccountPrincipal who) { return serviceOn(client, who); }
    TestAccountsService serviceOn(DynamoDbClient on, AccountPrincipal who) {
        var staff = staffFor(who);
        var service = new TestAccountsService(storeOn(on), json, policy, new ScenarioPurchases(new DynamoScenarioPurchaseStore(on, TABLES, json, clock), json, policy),
                plans, staff, new AccessSummaryService(new DynamoAccessSummaryStore(on, TABLES, json, fakes), staff, clock), catalog);
        service.configureMemberships(new MembershipService(new DynamoMembershipStore(on, TABLES, json, clock), json,
                new OwnerApprovalPaymentGateway(new MockStripePaymentGateway(new DynamoMockPaymentStore(on, TABLES), "success")), staff, clock));
        service.configureAliases(aliases());
        return service;
    }
    StaffAliases aliases() {
        var aliases = mock(StaffAliases.class);
        when(aliases.seesIdentities(any())).thenAnswer(call -> ((AccountPrincipal) call.getArgument(0)).accountId().equals(owner.accountId()));
        when(aliases.label(any())).thenAnswer(call -> call.getArgument(0).equals(adminA.accountId()) ? "Admin 1" : call.getArgument(0).equals(adminB.accountId()) ? "Admin 2" : "Owner");
        return aliases;
    }
    CreatedTestAccounts created(AccountPrincipal who) {
        var created = new CreatedTestAccounts(store(), policy, repository, ownerAccess, mock(TestIdentityOperator.class));
        created.configureAccounts(service(who));
        return created;
    }
    DevTestRoster roster(AccountPrincipal who) { return new DevTestRoster(store(), policy, ownerAccess, service(who)); }
    DevTestSessions sessions() {
        var sessions = new DevTestSessions(store(), policy, ISSUER, "synthetic-gateway-secret");
        // As DevTestSessionConfiguration wires it: the picker shows who upgraded each account.
        sessions.configureMarkers(new UpgradeMarkers(store(), memberships(staffFor(adminB)), aliases()));
        return sessions;
    }
    static UUID roster(String username) { return new TestAccountRoster.Entry(username).accountId(); }
    static AccountFailure failure(ThrowableAssert.ThrowingCallable call) { return catchThrowableOfType(AccountFailure.class, call); }
    TestAccountStore.RegistryRow registry(UUID id) { return store().transact(work -> work.registry(id)); }

    @Test void theRosterIsCreatedInBatchesAndTestSessionsFollowTheHierarchy() {
        var roster = roster(owner);
        assertThat(failure(() -> roster.next(adminA)).code()).isEqualTo("TEST_ROSTER_OWNER_ONLY");
        var first = roster.next(owner);
        assertThat(first).isEqualTo(new DevTestRoster.Status(27, 5, 0, first.withoutPlan(), first.changed()));
        var second = roster.next(owner);
        assertThat(second.ready()).isEqualTo(10);
        assertThat(roster.statusFor(adminA)).isNull();
        // Every ready learner whose scenario has a study plan is still without it (the plan fixture is a mock), and counts as changed.
        int needing = (int) TestAccountRoster.entries().subList(0, 10).stream().filter(e -> DevTestRoster.needsPlan(e.accountId())).count();
        assertThat(roster.statusFor(owner)).isEqualTo(new DevTestRoster.Status(27, 10, 0, needing, needing));
        assertThat(fakes.calls).contains("admit:learner01@lookahead.dev", "admit:learner10@lookahead.dev");
        var learner05 = registry(roster("learner05"));
        assertThat(learner05.username()).isEqualTo("learner05@lookahead.dev");
        assertThat(learner05.creatorId()).isEqualTo(owner.accountId());
        assertThat(learner05.initialization()).isEqualTo("scenario");
        assertThat(TestAccountItems.resets(client, TABLES, json, learner05.accountId())).extracting(r -> r.path("preset").asText()).containsExactly("create");
        assertThat(MembershipItems.purchases(client, TABLES, json, learner05.accountId())).hasSize(1);
        assertThat(fakes.grants.get(roster("learner04"))).containsExactly("learn:algorithmic-patterns", "learn:core-java");
        var list = service(owner).list(owner);
        assertThat(list).extracting(row -> row.path("username").asText()).startsWith("learner01@lookahead.dev", "learner02@lookahead.dev").hasSize(10);
        var row05 = list.get(4);
        assertThat(row05.path("access").path("label").asText()).isEqualTo("Regular · Learn");
        assertThat(row05.path("creatorId").asText()).isEqualTo(owner.accountId().toString());
        assertThat(row05.path("canReset").asBoolean()).isTrue();
        assertThat(row05.path("canDelete").asBoolean()).isFalse();
        assertThat(row05.path("pendingPurchase").isNull()).isTrue();
        assertThat(row05.path("upgradedBy").isNull()).isTrue();
        assertThat(service(adminA).list(adminA).get(4).path("creatorId").asText()).isEqualTo("Owner");
        var sessions = sessions();
        assertThat(sessions.usable("sub-admin-a")).extracting(DevTestSessions.Usable::username).hasSize(10).contains("learner05@lookahead.dev");
        assertThat(sessions.usable("sub-admin-a").get(4).scenario()).startsWith("Learn · active");
        assertThat(sessions.usable("sub-nobody")).isEmpty();
        var acting = sessions.start("sub-owner", roster("learner05"));
        assertThat(acting.username()).isEqualTo("learner05@lookahead.dev");
        assertThat(failure(() -> sessions.actAs("sub-nobody", roster("learner05"))).code()).isEqualTo("TEST_SESSION_FORBIDDEN");
        assertThat(failure(() -> sessions.actAs("sub-admin-a", UUID.randomUUID())).code()).isEqualTo("TEST_ACCOUNT_NOT_FOUND");
        assertThat(failure(() -> sessions.actAs("sub-admin-a", roster("learner20"))).code()).isEqualTo("TEST_ACCOUNT_NOT_FOUND"); // not created yet
        assertThat(roster.next(owner).ready()).isEqualTo(15);
        assertThat(failure(() -> created(owner).delete(owner, roster("learner05"))).code()).isEqualTo("ROSTER_ACCOUNT_PERMANENT");
    }

    @Test void createdAccountsCountLifetimeSpendAndPendingHoldsExpireAfterFifteenMinutes() {
        var created = created(adminA);
        assertThat(created.allowance(adminA).limit()).isEqualTo(10);
        UUID key = UUID.randomUUID();
        var first = created.create(adminA, new CreatedTestAccounts.Create(key, null, "learner05", false));
        assertThat(first.username()).endsWith("@" + TestAccountRoster.CLOUD_DOMAIN);
        assertThat(created.create(adminA, new CreatedTestAccounts.Create(key, null, "learner05", false))).isEqualTo(first);
        var registry = registry(first.accountId());
        assertThat(registry.initialization()).isEqualTo("created");
        assertThat(registry.creatorId()).isEqualTo(adminA.accountId());
        assertThat(fakes.calls).contains("admit:" + first.username());
        assertThat(MembershipItems.purchases(client, TABLES, json, first.accountId())).hasSize(1);
        assertThat(created.allowance(adminA).used()).isEqualTo(1);
        assertThat(failure(() -> created.create(adminA, new CreatedTestAccounts.Create(UUID.randomUUID(), null, "nope", false))).code()).isEqualTo("UNKNOWN_SCENARIO");
        assertThat(failure(() -> created.create(adminA, new CreatedTestAccounts.Create(null, null, null, false))).code()).isEqualTo("INVALID_TEST_ACCOUNT");
        assertThat(service(adminA).list(adminA)).extracting(row -> row.path("username").asText()).containsExactly(first.username());
        assertThat(sessions().usable("sub-admin-b")).extracting(DevTestSessions.Usable::accountId).containsExactly(first.accountId());
        // creator or owner only
        assertThat(failure(() -> created(adminB).delete(adminB, first.accountId())).code()).isEqualTo("DELETE_FORBIDDEN");
        created.delete(adminA, first.accountId());
        assertThat(registry(first.accountId()).creationState()).isEqualTo("deleted");
        assertThat(MembershipItems.purchases(client, TABLES, json, first.accountId())).isEmpty();
        assertThat(fakes.calls).contains("erase:" + first.accountId(), "clear:" + first.accountId());
        assertThat(created.allowance(adminA).used()).isEqualTo(1); // lifetime spend survives deletion
        assertThat(failure(() -> created.create(adminA, new CreatedTestAccounts.Create(key, null, null, false))).code()).isEqualTo("TEST_ACCOUNT_DELETED");
        created.delete(adminA, first.accountId()); // repeated: nothing more to do
        assertThat(sessions().usable("sub-admin-b")).isEmpty();
        for (int n = 0; n < 8; n++) created.create(adminA, new CreatedTestAccounts.Create(UUID.randomUUID(), null, null, false));
        assertThat(created.allowance(adminA).remaining()).isEqualTo(1);
        // A pending hold younger than 15 minutes takes the last place; after 15 minutes it does not.
        UUID held = UUID.randomUUID();
        store().transact(work -> { work.lockSubject(adminA.accountId()); work.reserve(adminA.accountId(), held, UUID.randomUUID(), "test-held"); return null; });
        assertThat(created.allowance(adminA).used()).isEqualTo(9);
        clock.advance(Duration.ofMinutes(14));
        assertThat(failure(() -> created.create(adminA, new CreatedTestAccounts.Create(UUID.randomUUID(), null, null, false))).code()).isEqualTo("TEST_ACCOUNT_LIMIT");
        clock.advance(Duration.ofMinutes(2));
        var tenth = created.create(adminA, new CreatedTestAccounts.Create(UUID.randomUUID(), null, null, false));
        assertThat(tenth.active()).isTrue();
        assertThat(failure(() -> created.create(adminA, new CreatedTestAccounts.Create(UUID.randomUUID(), null, null, false))).code()).isEqualTo("TEST_ACCOUNT_LIMIT");
        assertThat(created.allowance(owner).remaining()).isEqualTo(Integer.MAX_VALUE);
        var learner = new AccountPrincipal(UUID.randomUUID(), "learner", "Learner", true);
        assertThat(failure(() -> created.allowance(learner)).code()).isEqualTo("TEST_ACCOUNT_CREATION_FORBIDDEN");
        assertThat(item(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(adminA.accountId()), "META").get("revision").n()).isNotEqualTo("1");
    }

    @Test void resetNeedsTheRevisionKeepsKeysAndHonoursTheUpgradeMarker() {
        roster(owner).next(owner);
        UUID learner03 = roster("learner03");
        var service = service(adminA);
        var reset = new TestAccountsService.Reset(UUID.randomUUID(), (Long) null, "learner03@lookahead.dev", "scenario");
        assertThat(failure(() -> service.reset(adminA, learner03, reset)).code()).isEqualTo("REVISION_REQUIRED");
        assertThat(failure(() -> service.reset(adminA, learner03, new TestAccountsService.Reset(reset.key(), 7L, reset.confirmation(), "scenario"))).code()).isEqualTo("REVISION_CONFLICT");
        assertThat(failure(() -> service.reset(adminA, learner03, new TestAccountsService.Reset(reset.key(), 1L, reset.confirmation(), "legacy"))).code()).isEqualTo("RESET_UNAVAILABLE");
        assertThat(failure(() -> service.reset(adminA, learner03, new TestAccountsService.Reset(reset.key(), 1L, "wrong", "scenario"))).code()).isEqualTo("RESET_CONFIRMATION_REQUIRED");
        service.reset(adminA, learner03, new TestAccountsService.Reset(reset.key(), 1L, reset.confirmation(), "scenario"));
        var row = registry(learner03);
        assertThat(row.revision()).isEqualTo(2);
        assertThat(row.lastChangedBy()).isEqualTo(adminA.accountId().toString());
        assertThat(TestAccountItems.resets(client, TABLES, json, learner03)).extracting(r -> r.path("preset").asText()).containsExactlyInAnyOrder("create", "scenario");
        assertThat(fakes.calls).contains("resetPlans:" + learner03, "clear:" + learner03);
        service.reset(adminA, learner03, new TestAccountsService.Reset(reset.key(), 1L, reset.confirmation(), "scenario")); // the same key: a retry, nothing changes
        assertThat(registry(learner03).revision()).isEqualTo(2);
        assertThat(failure(() -> service(adminB).reset(adminB, learner03, new TestAccountsService.Reset(reset.key(), 2L, reset.confirmation(), "scenario"))).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        var createdAccount = created(adminA).create(adminA, new CreatedTestAccounts.Create(UUID.randomUUID(), null, null, false));
        assertThat(failure(() -> service.reset(adminA, createdAccount.accountId(), new TestAccountsService.Reset(UUID.randomUUID(), 1L, createdAccount.username(), "scenario"))).code()).isEqualTo("RESET_UNAVAILABLE");
        // learner05 is upgraded through a request adminA sends in a test session and the owner approves.
        UUID learner05 = roster("learner05");
        clock.advance(Duration.ofSeconds(1)); // the request and its approval come after the account's scenario start
        var acting = sessions().actAs("sub-admin-a", learner05);
        var purchases = memberships(staffFor(adminA));
        UUID learn = UUID.fromString(purchases.access(owner, learner05).getFirst().path("id").asText());
        var request = purchases.buy(acting, learner05, new MembershipService.Buy("advance", learn, List.of()), "dev-upgrade-key", false, adminA.accountId());
        assertThat(request.path("status").asText()).isEqualTo("pending");
        var ownerList = service(owner).list(owner);
        var pending = ownerList.stream().filter(r -> r.path("accountId").asText().equals(learner05.toString())).findFirst().orElseThrow().path("pendingPurchase");
        assertThat(pending.path("requestedBy").path("label").asText()).isEqualTo("Admin 1");
        assertThat(pending.path("requestedBy").path("name").asText()).isEqualTo("Admin A");
        assertThat(pending.path("kind").asText()).isEqualTo("upgrade");
        assertThat(pending.path("fromPlan").path("code").asText()).isEqualTo("learn");
        var decided = service(owner).decidePurchase(owner, new MembershipService.Decision(UUID.fromString(request.path("id").asText()), "approve"));
        assertThat(decided.path("purchase").path("status").asText()).isEqualTo("succeeded");
        var marked = service(adminB).list(adminB).stream().filter(r -> r.path("accountId").asText().equals(learner05.toString())).findFirst().orElseThrow();
        assertThat(marked.path("upgradedBy").path("label").asText()).isEqualTo("Admin 1");
        assertThat(marked.path("upgradedBy").path("plan").path("code").asText()).isEqualTo("advance");
        assertThat(marked.path("canReset").asBoolean()).isFalse();
        assertThat(sessions().usable("sub-admin-b").get(4).upgradedBy().path("plan").asText()).isEqualTo("Advance");
        assertThat(failure(() -> service(adminB).reset(adminB, learner05, new TestAccountsService.Reset(UUID.randomUUID(), 1L, "learner05@lookahead.dev", "scenario"))).code()).isEqualTo("RESET_RESERVED");
        clock.advance(Duration.ofSeconds(1));
        service.reset(adminA, learner05, new TestAccountsService.Reset(UUID.randomUUID(), 1L, "learner05@lookahead.dev", "scenario"));
        assertThat(purchases.approvedRequest(learner05)).isNull();
        assertThat(purchases.access(owner, learner05)).extracting(p -> p.path("status").asText()).containsExactly("active", "expired", "expired");
    }

    @Test void restoreBringsDeletedRosterAccountsBackForTheOwnerOnly() {
        var roster = roster(owner);
        roster.next(owner);
        UUID learner02 = roster("learner02");
        store().transact(work -> { work.lockSubject(learner02); work.deleteRegistry(learner02, owner.accountId()); return null; });
        assertThat(roster.statusFor(owner).deleted()).isEqualTo(1);
        assertThat(sessions().usable("sub-owner")).extracting(DevTestSessions.Usable::accountId).doesNotContain(learner02);
        assertThat(failure(() -> roster(adminA).restore(adminA, new DevTestRoster.Restore("RESTORE"))).code()).isEqualTo("OWNER_REQUIRED");
        assertThat(failure(() -> roster.restore(owner, new DevTestRoster.Restore("nope"))).code()).isEqualTo("ACCOUNT_CONFIRMATION_REQUIRED");
        assertThat(roster.restore(owner, new DevTestRoster.Restore("RESTORE")).restored()).isEqualTo(1);
        var row = registry(learner02);
        assertThat(row.creationState()).isEqualTo("active");
        assertThat(row.revision()).isEqualTo(3);
        assertThat(TestAccountItems.resets(client, TABLES, json, learner02)).extracting(r -> r.path("preset").asText()).containsExactlyInAnyOrder("create", "restore");
        assertThat(roster.restore(owner, new DevTestRoster.Restore("RESTORE")).restored()).isZero();
        assertThat(roster.statusFor(owner).deleted()).isZero();
        assertThat(sessions().usable("sub-owner")).extracting(DevTestSessions.Usable::accountId).contains(learner02);
    }

    @Test void aCheckoutDuringADeleteConflictsWithTheFencedErasureAndTheRetryRemovesItToo() {
        var account = created(adminA).create(adminA, new CreatedTestAccounts.Create(UUID.randomUUID(), null, "learner05", false));
        UUID id = account.accountId();
        assertThat(purchases(id)).hasSize(1);
        var spy = delegating(client);
        var slipped = new java.util.concurrent.atomic.AtomicInteger(); var conflicts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest request = call.getArgument(0);
            boolean erasing = request.transactStatements().stream().anyMatch(st -> st.statement().startsWith("DELETE FROM"));
            // Between the erasure's read of META and its first delete, a checkout commits a purchase and moves the revision.
            if (erasing && slipped.compareAndSet(0, 1)) checkout(id);
            try { return client.executeTransaction(request); }
            catch (software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException cancelled) { if (erasing) conflicts.incrementAndGet(); throw cancelled; }
        }).when(spy).executeTransaction(any(software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest.class));
        var deleting = new CreatedTestAccounts(storeOn(spy), policy, repository, ownerAccess, mock(TestIdentityOperator.class));
        deleting.configureAccounts(serviceOn(spy, adminA));
        deleting.delete(adminA, id);
        assertThat(conflicts.get()).as("the fenced delete lost once").isEqualTo(1);
        assertThat(slipped.get()).isEqualTo(1);
        assertThat(purchases(id)).as("the retry read the new purchase under its own fence and deleted it too").isEmpty();
        assertThat(MembershipItems.query(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(id), MembershipItems.MEMBERSHIP_PREFIX)).isEmpty();
        assertThat(registry(id).creationState()).isEqualTo("deleted");
        assertThat(fakes.calls.stream().filter(c -> c.equals("erase:" + id)).count()).as("the idempotent lanes ran on each attempt").isEqualTo(2);
        // The registry commit fenced on the revision the erasure left: a purchase could not have appeared between the two.
        assertThat(DynamoItems.number(item(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(id), "META"), "revision")).isGreaterThanOrEqualTo(4);
    }

    @Test void aCheckoutWhileAnAccountIsResetIsNeverSilentlySupersededAndTheNextResetTakesItIn() {
        roster(owner).next(owner);
        UUID learner05 = roster("learner05");
        var before = purchases(learner05);
        assertThat(before).isNotEmpty().allMatch(DynamoTestAccountsDatabaseTest::open);
        // 1. The checkout commits after the body decided (its purchases read, its upgrade-marker check) and before the commit reads its fence.
        var spy = delegating(client);
        var scenarioWritten = new java.util.concurrent.atomic.AtomicBoolean(); var fired = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest request = call.getArgument(0);
            if (hasParameter(request, MembershipItems.MEMBERSHIP_PREFIX)) scenarioWritten.set(true); // the body's scenario rows are in
            return client.executeTransaction(request);
        }).when(spy).executeTransaction(any(software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest.class));
        doAnswer(call -> {
            software.amazon.awssdk.services.dynamodb.model.GetItemRequest request = call.getArgument(0);
            boolean fence = request.key().get("pk").s().equals(DynamoItems.account(learner05)) && request.key().get("sk").s().equals("META");
            if (fence && scenarioWritten.get() && fired.compareAndSet(false, true)) checkout(learner05);
            return client.getItem(request);
        }).when(spy).getItem(any(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.class));
        var refused = failure(() -> serviceOn(spy, adminA).reset(adminA, learner05, new TestAccountsService.Reset(UUID.randomUUID(), 1L, "learner05@lookahead.dev", "scenario")));
        assertThat(fired.get()).isTrue();
        assertThat(refused.status()).isEqualTo(409); assertThat(refused.code()).isEqualTo("REVISION_CONFLICT");
        // Nothing of the reset committed; what its scenario wrote before the commit stays beside the old rows and the checkout's.
        assertThat(TestAccountItems.resets(client, TABLES, json, learner05)).extracting(r -> r.path("preset").asText()).containsExactly("create");
        assertThat(registry(learner05).revision()).isEqualTo(1);
        var after = purchases(learner05);
        assertThat(after).hasSizeGreaterThan(before.size() + 1).allMatch(DynamoTestAccountsDatabaseTest::open);
        assertThat(after.stream().filter(p -> !MembershipItems.fixture(p)).count()).as("the checkout's row").isEqualTo(1);
        // 2. The next reset supersedes every one of them, the checkout's included, and starts the scenario again.
        service(adminA).reset(adminA, learner05, new TestAccountsService.Reset(UUID.randomUUID(), 1L, "learner05@lookahead.dev", "scenario"));
        var healed = purchases(learner05);
        assertThat(healed).hasSizeGreaterThan(after.size());
        assertThat(healed.stream().filter(DynamoTestAccountsDatabaseTest::open).toList()).hasSize(healed.size() - after.size()).allMatch(MembershipItems::fixture);
        assertThat(registry(learner05).revision()).isEqualTo(2);
        // 3. A checkout that commits after the commit read its fence fails the commit's revision condition itself.
        var late = delegating(client); var lateFired = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest request = call.getArgument(0);
            if (hasParameter(request, TestAccountItems.RESET_PREFIX) && lateFired.compareAndSet(false, true)) checkout(learner05);
            return client.executeTransaction(request);
        }).when(late).executeTransaction(any(software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest.class));
        assertThatThrownBy(() -> serviceOn(late, adminA).reset(adminA, learner05, new TestAccountsService.Reset(UUID.randomUUID(), 2L, "learner05@lookahead.dev", "scenario")))
                .isInstanceOf(com.lookahead.domain.persistence.dynamodb.DynamoWrites.Conflict.class);
        assertThat(lateFired.get()).isTrue();
        assertThat(registry(learner05).revision()).isEqualTo(2);
        assertThat(TestAccountItems.resets(client, TABLES, json, learner05)).hasSize(2);
    }

    @Test void theLocalSeedCommitsOneAccountAtATimeSoAnyRosterSizeFitsAndAFailureLeavesEarlierAccountsComplete() {
        var local = new TestAccountPolicy(TestAccountPolicyTest.local());
        var roster = new ArrayList<TestAccountRoster.Entry>();
        for (int n = 1; n <= 40; n++) roster.add(new TestAccountRoster.Entry("roster%02d".formatted(n), "learner%02d".formatted((n - 1) % 27 + 1)));
        for (var entry : roster) purgeAccount(client, entry.accountId());
        purgeAccount(client, LocalAuthorAccess.ACCOUNT_ID);
        var spy = delegating(client); var sizes = new ArrayList<Integer>();
        doAnswer(call -> {
            software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest request = call.getArgument(0);
            sizes.add(request.transactStatements().size());
            return client.executeTransaction(request);
        }).when(spy).executeTransaction(any(software.amazon.awssdk.services.dynamodb.model.ExecuteTransactionRequest.class));
        var purchases = mock(ScenarioPurchases.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        UUID failing = roster.get(4).accountId();
        doThrow(new IllegalStateException("fixture down")).when(purchases).populate(eq(failing), any(), any(), any());
        var staff = staffFor(owner);
        var service = new TestAccountsService(storeOn(spy), json, local, purchases, plans, staff, new AccessSummaryService(new DynamoAccessSummaryStore(spy, TABLES, json, fakes), staff, clock), catalog);
        assertThatThrownBy(() -> service.seed(roster)).isInstanceOf(IllegalStateException.class).hasMessage("fixture down");
        for (int n = 0; n < roster.size(); n++) {
            UUID id = roster.get(n).accountId();
            var meta = item(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(id), "META");
            var row = registry(id);
            if (n < 5) { assertThat(meta).as("account %d: subject", n).isNotEmpty(); assertThat(row.initialization()).as("account %d: registered with its subject", n).isEqualTo("scenario"); }
            else { assertThat(meta).as("account %d: nothing yet", n).isEmpty(); assertThat(row).isNull(); }
        }
        verify(purchases, times(5)).populate(any(), any(), any(), any());
        assertThat(item(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(LocalAuthorAccess.ACCOUNT_ID), "META")).as("the operator's subject").isNotEmpty();
        // The rerun leaves the registered accounts alone (the failed one keeps what its scenario left) and finishes the rest.
        reset(purchases);
        assertThat(service.seed(roster)).isEqualTo(35);
        assertThat(service.seed(roster)).isZero();
        verify(purchases, times(35)).populate(any(), any(), any(), any());
        assertThat(storeOn(client).activeRegistry(false)).extracting(TestAccountStore.RegistryRow::username).hasSize(40).contains("roster01", "roster05", "roster40");
        assertThat(sizes).as("every transaction small, whatever the roster size").isNotEmpty().allMatch(size -> size <= 3);
        assertThat(sizes.size()).isGreaterThan(40);
    }
}
