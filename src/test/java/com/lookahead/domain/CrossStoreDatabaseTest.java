package com.lookahead.domain;

import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.util.MembershipRules;
import static com.lookahead.domain.db.store.signin.AccountItems.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lookahead.domain.db.store.access.AccountErasureStore;
import com.lookahead.domain.db.store.access.AccountIdentity;
import com.lookahead.domain.db.store.membership.MembershipStoreLocalFixture;
import com.lookahead.domain.job.AccountErasureJob;
import com.lookahead.domain.db.store.access.AccountRecordsStore;
import com.lookahead.domain.db.store.access.StaffAccessStore;
import com.lookahead.domain.db.store.plan.StudyPlanAccountErasure;
import com.lookahead.domain.db.store.membership.MembershipErasure;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.signin.SignInStoreLocalFixture;
import com.lookahead.domain.db.store.membership.MembershipFactsStore;
import com.lookahead.domain.db.store.membership.MembershipGrantsStore;
import com.lookahead.domain.db.store.membership.MembershipStore;
import com.lookahead.domain.db.store.membership.MembershipItems;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.job.MaintenanceJobRegistry;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistryStore;
import com.lookahead.domain.db.store.testaccounts.TestAccountItems;
import com.lookahead.domain.db.store.access.AccountSubjectStore;
import com.lookahead.domain.db.store.plan.StudyPlanErasureStore;
import com.lookahead.domain.db.store.plan.StudyPlanFactsStore;
import com.lookahead.domain.db.store.plan.StudyPlanStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970 lane G on real DynamoDB Local: the cross-lane adapters read and write the providing lanes' items in their
 * own shapes, and the erasure queue finds interrupted account deletions without a Scan.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class CrossStoreDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = SignInStoreLocalFixture.TABLES;
    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    final MutableClock clock = new MutableClock(NOW);
    final ObjectMapper json = new ObjectMapper();
    final UUID owner = UUID.randomUUID();
    StaffAccessStore staff;
    AccountRecordsStore records;

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
        MembershipStoreLocalFixture.purgeLaneC(client);
        SignInStoreLocalFixture.purge(client, TABLES.userAccountDetailsTableName(), "PLAN#", "WORK#");
        staff = new StaffAccessStore(client, TABLES, json, clock);
        records = new AccountRecordsStore(client, TABLES, staff, clock);
    }
    /** A subject in lane A's META shape, as the test-account store commits it with the registry row. */
    static void subject(UUID id, Instant at) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .item(item(account(id), "META", attrs("revision", n(1), "lastPlanNumber", n(0), "createdAt", time(at)))).build());
    }
    static Map<String, AttributeValue> stored(String pk, String sk) {
        return get(client, TABLES.userAccountDetailsTableName(), pk, sk);
    }

    @Test void laneAsRecordsAnswerIdentityHolderAdmittedStaffAndGrantQuestionsAndAdmitATestAccountInLaneAsShape() {
        UUID learner = UUID.randomUUID(), admin = UUID.randomUUID();
        subject(learner, NOW);
        assertThat(records.hasAccountRecord(learner)).isFalse();
        assertThat(records.admitted(learner)).isNull(); assertThat(records.displayName(learner)).isNull();
        assertThat(records.accountOf(SignInIdentity.TEST_ISSUER, learner.toString())).isNull();
        records.admitTestAccount(learner, "learner01@lookahead.dev", "Learner One", admin);
        assertThat(records.hasAccountRecord(learner)).isTrue();
        // The CONTROL item is what lane A's registry and control store read: admitted, enabled, managed test, on the test issuer.
        var accounts = new AccountAggregateStore(client, TABLES, clock);
        var control = accounts.control(learner);
        assertThat(control.issuer()).isEqualTo(SignInIdentity.TEST_ISSUER); assertThat(control.subject()).isEqualTo(learner.toString());
        assertThat(control.admitted()).isTrue(); assertThat(control.enabled()).isTrue(); assertThat(control.managedTest()).isTrue(); assertThat(control.author()).isFalse();
        assertThat(control.revision()).isEqualTo(1); assertThat(control.admittedAt()).isEqualTo(NOW);
        assertThat(accounts.identities(learner)).singleElement().satisfies(identity -> {
            assertThat(identity.provider()).isEqualTo("test"); assertThat(identity.linkSource()).isEqualTo("first_contact"); assertThat(identity.verifiedEmail()).isNull();
        });
        assertThat(accounts.accountOf(SignInIdentity.TEST_ISSUER, learner.toString())).isEqualTo(learner);
        assertThat(stored(DIRECTORY, personSk("learner01@lookahead.dev", learner)).get("managedTest").bool()).isTrue();
        assertThat(query(client, TABLES.administratorAuditEventsTableName(), account(learner), "ADMISSION#")).singleElement()
                .satisfies(event -> { assertThat(event.get("action").s()).isEqualTo("admit"); assertThat(event.get("actorId").s()).isEqualTo(admin.toString()); });
        assertThat(stored(account(learner), "META").get("lastPlanNumber").n()).as("the subject is the store's, untouched").isEqualTo("0");
        // The same account again is a duplicate, never a second record.
        assertThatThrownBy(() -> records.admitTestAccount(learner, "learner01@lookahead.dev", "Learner One", admin)).isInstanceOf(DynamoWrites.Conflict.class);
        // Identity questions.
        assertThat(records.accountOf(SignInIdentity.TEST_ISSUER, learner.toString())).isEqualTo(learner);
        assertThat(records.holder(SignInIdentity.TEST_ISSUER, learner.toString())).isEqualTo(new AccountIdentity.Holder(learner, false));
        assertThat(records.holder(SignInIdentity.TEST_ISSUER, "nobody")).isNull();
        assertThat(records.admitted(learner)).isEqualTo(new AccountIdentity.Admitted(SignInIdentity.TEST_ISSUER, "Learner One", true, true));
        assertThat(records.displayName(learner)).isEqualTo("Learner One");
        // A disabled account is no holder.
        var disable = accounts.transaction(); accounts.control(disable, control, attrs("accountState", s("DISABLED")), null); disable.commit();
        assertThat(records.holder(SignInIdentity.TEST_ISSUER, learner.toString())).isNull();
        assertThat(records.admitted(learner).enabled()).isTrue();
        // Staff: roles in stored order, the assignment's updated time, and topic grants counted with the account's own grants.
        assertThat(records.staffRoles(learner)).isEmpty(); assertThat(records.staffAccessUpdatedAt(learner)).isNull();
        var assignment = new StaffAccess.Assignment(UUID.randomUUID(), learner, null, Set.of("tester"), Set.of("grow:system-design"), false, 1);
        staff.insert(assignment);
        assertThat(records.staffRoles(learner)).containsExactly("tester");
        assertThat(records.staffAccessUpdatedAt(learner)).isEqualTo(NOW);
        records.grantTopics(learner, List.of("learn:docker", "learn:core-java"));
        records.grantTopics(learner, List.of("learn:docker"));
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .item(item(account(learner), grantSk("learn:expired"), attrs("topicId", s("learn:expired"), "validUntil", time(NOW.plusSeconds(60))))).build());
        assertThat(records.grantedTopics(learner, NOW)).containsExactly("grow:system-design", "learn:core-java", "learn:docker", "learn:expired");
        assertThat(records.grantedTopics(learner, NOW.plusSeconds(60))).containsExactly("grow:system-design", "learn:core-java", "learn:docker");
        assertThat(records.grantedTopics(UUID.randomUUID(), NOW)).isEmpty();
        // Clearing: every grant gone, the assignment emptied at the next revision and its time moved.
        clock.advance(Duration.ofMinutes(5));
        records.clearAccessRecords(learner);
        assertThat(records.grantedTopics(learner, NOW)).isEmpty();
        var emptied = staff.forAccount(learner);
        assertThat(emptied.roles()).isEmpty(); assertThat(emptied.topicGrants()).isEmpty(); assertThat(emptied.fullCurriculum()).isFalse(); assertThat(emptied.revision()).isEqualTo(2);
        assertThat(records.staffAccessUpdatedAt(learner)).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        records.clearAccessRecords(UUID.randomUUID()); // nothing there: nothing to do
    }

    @Test void laneCsMembershipFactsReadPurchasesEndOpenOnesUnderTheOwnerFenceAndNameTheRegistry() {
        var catalog = mock(AccountCatalogService.class);
        when(catalog.allTopicIds()).thenReturn(Set.of("learn:docker", "grow:system-design", "blaze:ai"));
        var store = new MembershipStore(client, TABLES, json, clock);
        var grants = new MembershipGrantsStore(client, TABLES, json);
        var facts = new MembershipFactsStore(client, TABLES, json, store, clock);
        MembershipStoreLocalFixture.seedCatalog(client, json, NOW);
        UUID member = UUID.randomUUID(), nobody = UUID.randomUUID();
        subject(member, NOW);
        assertThat(MembershipRules.topics(grants.catalogScopes(member), catalog.allTopicIds())).isEmpty(); assertThat(facts.hasOpenPurchases(member)).isFalse(); assertThat(facts.hasPaidMembership(member)).isFalse();
        facts.endMemberships(nobody); // no subject, no purchases: nothing to end
        UUID learn = UUID.randomUUID(), free = UUID.randomUUID(), old = UUID.randomUUID();
        String table = TABLES.userAccountDetailsTableName();
        var plan = store.definition("learn");
        purchase(member, learn, "learn", plan, 0, "succeeded", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)), null);
        purchase(member, free, "free", store.definition("free"), 0, "succeeded", NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(3650)), null);
        purchase(member, old, "grow", store.definition("grow"), 9999, "succeeded", NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(35)), NOW.minus(Duration.ofDays(35)));
        assertThat(MembershipRules.topics(grants.catalogScopes(member), catalog.allTopicIds())).containsExactly("learn:docker");
        assertThat(facts.hasOpenPurchases(member)).isTrue();
        assertThat(facts.hasPaidMembership(member)).as("the learn plan is priced even at a zero paid price").isTrue();
        facts.endMemberships(member);
        var ended = MembershipItems.purchases(client, TABLES, json, member);
        assertThat(ended).allSatisfy(p -> assertThat(p.path("supersededAt").asText()).isNotEmpty());
        assertThat(ended.stream().filter(p -> p.path("id").asText().equals(old.toString())).findFirst().orElseThrow().path("supersededAt").asText())
                .as("an already superseded purchase keeps its time").isEqualTo(NOW.minus(Duration.ofDays(35)).toString());
        assertThat(ended.stream().filter(p -> p.path("id").asText().equals(learn.toString())).findFirst().orElseThrow().path("revision").asLong()).isEqualTo(2);
        assertThat(stored(account(member), "META").get("revision").n()).as("ended under the owner fence").isEqualTo("2");
        assertThat(facts.hasOpenPurchases(member)).isFalse(); assertThat(facts.hasPaidMembership(member)).isFalse(); assertThat(MembershipRules.topics(grants.catalogScopes(member), catalog.allTopicIds())).isEmpty();
        facts.endMemberships(member); // nothing open: no transaction
        assertThat(stored(account(member), "META").get("revision").n()).isEqualTo("2");
        // A paid price counts even when the plan is free of charge today; an expired one does not.
        UUID paid = UUID.randomUUID();
        purchase(member, paid, "free", store.definition("free"), 100, "scheduled", NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null);
        assertThat(facts.hasPaidMembership(member)).isTrue();
        clock.advance(Duration.ofDays(31));
        assertThat(facts.hasPaidMembership(member)).isFalse();
        // The registry lookup is the TEST row, whatever its creation state.
        var registry = new TestAccountRegistryStore(client, TABLES);
        assertThat(registry.isTestAccount(member)).isFalse();
        client.putItem(PutItemRequest.builder().tableName(table).item(DynamoItems.item(account(member), TestAccountItems.REGISTRY_SK, 1,
                Map.of("creationState", DynamoItems.s("deleted")))).build());
        assertThat(registry.isTestAccount(member)).isTrue();
    }
    private void purchase(UUID account, UUID id, String planId, JsonNode plan, int price, String status, Instant startsAt, Instant endsAt, Instant supersededAt) {
        var row = MembershipItems.storedPurchase(json, id, account, planId, plan, "fp", id.toString(), price, status, startsAt, endsAt, "dev", account, null, null, null, supersededAt, null, startsAt);
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(MembershipItems.item(account(account), MembershipItems.purchaseSk(id), row, 1)).build());
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(MembershipItems.purchasePointerPk(id), "ACCOUNT", 1,
                Map.of("accountId", DynamoItems.s(account.toString())))).build());
    }

    @Test void laneBsPlanFactsReadPlanActivityResetKeepsLedgersAndTombstonesReceiptsAndErasureRemovesEverything() {
        var plans = new StudyPlanStore(client, TABLES, json);
        var facts = new StudyPlanFactsStore(client, TABLES, new StudyPlanErasureStore(client, TABLES, json));
        subject(owner, NOW);
        assertThat(facts.hasPlan(owner)).isFalse(); assertThat(facts.latestPlanWriteAt(owner)).isNull();
        UUID planId = UUID.randomUUID(), version = UUID.randomUUID(), key = UUID.randomUUID();
        assertThat(plans.allocatePlanNumber(owner)).isEqualTo(1);
        plans.createPlan(owner, planId, version, "goal", json.createObjectNode(), "Plan 1", 1);
        plans.insertVersion(owner, planId, version, null, json.createObjectNode().put("a", 1), "digest", json.createObjectNode(), json.createObjectNode(), "created", json.createObjectNode());
        plans.insertReceipt(owner, key, "hash", planId, json.createObjectNode().put("ok", true), 201);
        String pk = account(owner);
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(pk, "LIFECYCLE#" + planId, 1, Map.of("courseId", DynamoItems.s("c")))).build());
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(pk, "FREECREATE#" + planId, 1, Map.of("at", DynamoItems.s(NOW.toString())))).build());
        assertThat(facts.hasPlan(owner)).isTrue();
        // The repository's own clock stamps the plan: the latest write is the plan item's updatedAt.
        Instant created = Instant.parse(plans.findPlan(owner, planId, false).orElseThrow().updatedAt());
        assertThat(facts.latestPlanWriteAt(owner)).isEqualTo(created);
        plans.rename(owner, planId, "Plan one", 2);
        Instant renamed = Instant.parse(plans.findPlan(owner, planId, false).orElseThrow().updatedAt());
        assertThat(facts.latestPlanWriteAt(owner)).isEqualTo(renamed).isAfterOrEqualTo(created);
        assertThat(plans.findReceipt(owner, key)).get().satisfies(receipt -> assertThat(receipt.deleted()).isFalse());
        // Reset: the plan, its partition, list row and lifecycle gone; the receipt a tombstone; the free ledger kept.
        facts.resetPlans(owner);
        assertThat(facts.hasPlan(owner)).isFalse(); assertThat(facts.latestPlanWriteAt(owner)).isNull();
        assertThat(query(client, TABLES.userAccountDetailsTableName(), pk, "PLAN#")).isEmpty();
        assertThat(query(client, TABLES.userAccountDetailsTableName(), pk, "PLANLIST#")).isEmpty();
        assertThat(query(client, TABLES.userAccountDetailsTableName(), pk, "LIFECYCLE#")).isEmpty();
        assertThat(query(client, TABLES.userAccountDetailsTableName(), DynamoItems.plan(owner, planId), null)).isEmpty();
        assertThat(query(client, TABLES.userAccountDetailsTableName(), pk, "FREECREATE#")).hasSize(1);
        var tombstone = stored(pk, "MUTATION#" + key);
        assertThat(tombstone.get("deleted").bool()).isTrue(); assertThat(tombstone.get("response").nul()).as("no response bytes remain").isTrue();
        assertThat(plans.findReceipt(owner, key)).get().satisfies(receipt -> { assertThat(receipt.deleted()).isTrue(); assertThat(receipt.status()).isEqualTo(201); });
        assertThat(stored(pk, "META").get("lastPlanNumber").n()).as("the plan-number sequence survives").isEqualTo("1");
        // Account removal: receipts go; the free ledgers and the lifetime counter stay, as the retired PostgreSQL store kept them.
        facts.eraseAccount(owner);
        assertThat(query(client, TABLES.userAccountDetailsTableName(), pk, "FREECREATE#")).hasSize(1);
        assertThat(stored(pk, "MUTATION#" + key)).isEmpty();
        assertThat(stored(pk, "META")).isNotEmpty();
        // Test-account erasure (lane C): the ledgers go too and the counter resets; META stays.
        facts.erase(owner);
        assertThat(query(client, TABLES.userAccountDetailsTableName(), pk, "FREECREATE#")).isEmpty();
        assertThat(stored(pk, "META").get("freePlanCreations").n()).isEqualTo("0");
        facts.erase(owner); // repeatable
        var tombstonedPlan = UUID.randomUUID();
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(pk, "PLAN#" + tombstonedPlan, 1,
                Map.of("planId", DynamoItems.s(tombstonedPlan.toString()), "deleted", DynamoItems.bool(true), "updatedAt", DynamoItems.s(NOW.toString())))).build());
        assertThat(facts.hasPlan(owner)).as("a tombstone is no plan").isFalse();
        assertThat(facts.latestPlanWriteAt(owner)).as("but it is a write").isEqualTo(NOW);
    }

    @Test void theErasureQueueFindsInterruptedDeletionsDropsStaleEntriesAndKeepsFailedOnesForTheNextSweep() {
        var accounts = new AccountAggregateStore(client, TABLES, clock);
        var erased = new ArrayList<UUID>();
        var beans = new StaticListableBeanFactory();
        beans.addBean("plans", (StudyPlanAccountErasure) erased::add);
        beans.addBean("memberships", (MembershipErasure) account -> {});
        var erasure = new AccountErasureStore(accounts, staff, beans.getBeanProvider(StudyPlanAccountErasure.class), beans.getBeanProvider(MembershipErasure.class));
        UUID actor = UUID.randomUUID(), pending = UUID.randomUUID(), stale = UUID.randomUUID();
        subject(pending, NOW); subject(stale, NOW);
        new AccountSubjectStore(client, TABLES, clock).grant(pending, "learn:docker");
        // The removal's job receipt in its ERASE phase (what the control store writes with the REMOVED transition).
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(removalJobPk(pending), "JOB", 1,
                Map.of("phase", DynamoItems.s("ERASE"), "operationId", DynamoItems.s(UUID.randomUUID().toString())))).build());
        erasure.queue(actor, pending); erasure.queue(actor, pending); erasure.queue(actor, stale);
        assertThat(erasure.queued(AccountErasureStore.MAX_QUEUED)).extracting(AccountErasureStore.Queued::id).containsExactlyInAnyOrder(pending, stale);
        assertThat(erasure.queued(1)).hasSize(1);
        assertThatThrownBy(() -> erasure.queued(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> erasure.queued(26)).isInstanceOf(IllegalArgumentException.class);
        var job = new AccountErasureJob(erasure);
        // A stale entry younger than the grace period may be a Delete still between its queue and its transition: kept.
        assertThat(job.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(1, 0, 0, false));
        assertThat(erasure.queued(AccountErasureStore.MAX_QUEUED)).extracting(AccountErasureStore.Queued::id).containsExactly(stale);
        clock.advance(AccountErasureJob.GRACE.plusSeconds(1));
        MaintenanceJobRegistry.Page page = job.run(false);
        assertThat(page).isEqualTo(new MaintenanceJobRegistry.Page(1, 0, 0, false));
        assertThat(erased).containsExactly(pending);
        assertThat(stored(removalJobPk(pending), "JOB").get("phase").s()).isEqualTo("DONE");
        assertThat(stored(removalJobPk(pending), "JOB").get("actorId").s()).isEqualTo(actor.toString());
        assertThat(query(client, TABLES.userAccountDetailsTableName(), account(pending), "GRANT#")).isEmpty();
        assertThat(erasure.queued(AccountErasureStore.MAX_QUEUED)).isEmpty();
        assertThat(job.run(false)).isEqualTo(MaintenanceJobRegistry.Page.NOTHING);
        // A failing lane erasure stays queued and pending; the page reports it and ends the sweep.
        var failing = new StaticListableBeanFactory();
        failing.addBean("plans", (StudyPlanAccountErasure) account -> { throw new IllegalStateException("plans down"); });
        var broken = new AccountErasureStore(accounts, staff, failing.getBeanProvider(StudyPlanAccountErasure.class), beans.getBeanProvider(MembershipErasure.class));
        var reopen = accounts.transaction(); reopen.update(TABLES.userAccountDetailsTableName(), key(removalJobPk(pending), "JOB"), Map.of("phase", s("ERASE")), null); reopen.commit();
        broken.queue(actor, pending);
        try (var log = LogCapture.start()) {
            assertThat(new AccountErasureJob(broken).run(false)).isEqualTo(new MaintenanceJobRegistry.Page(0, 0, 1, false));
            assertThat(log.only("account.erasure").getFormattedMessage()).startsWith("Erasure of deleted account " + pending + " did not complete (IllegalStateException)");
        }
        assertThat(broken.pending(pending)).isTrue();
        assertThat(broken.queued(AccountErasureStore.MAX_QUEUED)).extracting(AccountErasureStore.Queued::id).containsExactly(pending);
        // The next sweep, with the lane back, finishes it.
        assertThat(job.run(false)).isEqualTo(new MaintenanceJobRegistry.Page(1, 0, 0, false));
        assertThat(erasure.pending(pending)).isFalse(); assertThat(erasure.queued(AccountErasureStore.MAX_QUEUED)).isEmpty();
    }
}
