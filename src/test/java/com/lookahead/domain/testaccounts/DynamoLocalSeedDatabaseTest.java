package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.membership.*;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.learning.content.repository.DynamoPlanRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.service.PlanLifecycle;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.membership.DynamoLocalFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The roster seed end to end on DynamoDB Local with lane B's real plan repository: the subject ({@code ACCOUNT#id / META})
 * is durable before the scenario's plan writes fence on it, the seed repeats as a no-op, and the DEV roster admits the
 * same way. The lifecycle is a mock (its store is proved in its own database test): the single valid template fixture
 * would make the two plans of a SEVERAL scenario reserve the same subjects on the same days.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoLocalSeedDatabaseTest {
    private static DynamoDbClient client;
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final String DATA = TABLES.userAccountDetailsTableName();
    private final ObjectMapper json = new ObjectMapper();
    private final DynamoTestAccountsDatabaseTest.MutableClock clock = new DynamoTestAccountsDatabaseTest.MutableClock(NOW);
    private final DynamoTestAccountsDatabaseTest.Fakes fakes = new DynamoTestAccountsDatabaseTest.Fakes();
    private final SnapshotValidator catalog = mock(SnapshotValidator.class);
    private final ProtectedContentPolicy publication = mock(ProtectedContentPolicy.class);
    @TempDir Path root;
    private String fixtures;
    private Set<String> topics;

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); ensureTables(client); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void fixtures() throws Exception {
        purgeLaneC(client);
        for (var entry : TestAccountRoster.entries()) purgeRoster(entry.accountId());
        purgeRoster(LocalAuthorAccess.ACCOUNT_ID);
        seedCatalog(client, json, NOW);
        JsonNode source = json.readTree(getClass().getResourceAsStream("/study-plans/synthetic-adoption-template.json"));
        topics = new HashSet<>(); source.path("topicIds").forEach(t -> topics.add(t.asText()));
        var asset = new ProtectedContentPolicy.Asset("fixture", "a".repeat(64), "application/json", "pro", topics, Set.of(), "all");
        when(publication.find(anyString())).thenReturn(Optional.of(asset));
        when(publication.read(asset)).thenReturn(json.writeValueAsBytes(source));
        JsonNode custom = json.readTree(getClass().getResourceAsStream("/accounts/generated-plan.json"));
        var bundle = json.createObjectNode().put("schemaVersion", "test-account-plans/v1"); bundle.set("customLearn", custom); bundle.set("customGrow", custom);
        Path file = root.resolve("plans.json"); Files.writeString(file, bundle.toString()); fixtures = file.toString();
        Map<String, String> members = new LinkedHashMap<>();
        for (var day : custom.path("snapshot").path("days")) for (var assignment : day.path("assignments")) members.put(assignment.path("id").asText(), assignment.path("contentId").asText());
        when(catalog.allTopicIds()).thenReturn(topics);
        when(catalog.validate(any(), any(), eq(false), eq(topics))).thenReturn(new SnapshotValidator.ValidationResult(members, Set.copyOf(members.values()), "fixture", custom.path("provenance")));
    }
    private static void purgeRoster(UUID account) {
        purgeAccount(client, account);
        purge(client, DATA, "PLAN#" + account);
        purge(client, TABLES.administratorAuditEventsTableName(), TestAccountItems.resetPk(account));
    }
    private DynamoTestAccountStore store() { return new DynamoTestAccountStore(client, TABLES, json, fakes, fakes, fakes, fakes, clock); }
    private TestAccountsService service(TestAccountPolicy policy, StaffAccessService staff) {
        var plans = new ScenarioPlans(publication, catalog, new DynamoPlanRepository(client, TABLES, json), mock(PlanLifecycle.class), new PlanJson(json), json, policy, fixtures);
        return new TestAccountsService(store(), json, policy, new ScenarioPurchases(new DynamoScenarioPurchaseStore(client, TABLES, json, clock), json, policy),
                plans, staff, new AccessSummaryService(new DynamoAccessSummaryStore(client, TABLES, json, fakes), staff, clock), catalog);
    }
    private static int plansOf(LearnerScenario scenario) {
        return switch (scenario.planKind()) { case NONE -> 0; case SEVERAL -> 2; default -> 1; };
    }
    private Map<String, AttributeValue> meta(UUID account) { return item(client, DATA, DynamoItems.account(account), MembershipItems.META_SK); }
    /** The subject in lane A's shape, its plan-number sequence at the plan count, and its revision moved by every plan write and the commit. */
    private void assertSubjectAndPlans(UUID account, int plans) {
        var meta = meta(account);
        assertThat(meta.get("schemaVersion").n()).isEqualTo("1");
        assertThat(meta).containsKeys("revision", "lastPlanNumber", "createdAt");
        assertThat(DynamoItems.number(meta, "lastPlanNumber")).isEqualTo(plans);
        assertThat(DynamoItems.number(meta, "revision")).isGreaterThanOrEqualTo(2 + plans);
        var pointers = MembershipItems.query(client, DATA, DynamoItems.account(account), "PLAN#");
        assertThat(pointers).hasSize(plans);
        assertThat(MembershipItems.query(client, DATA, DynamoItems.account(account), "PLANLIST#")).hasSize(plans);
        for (var pointer : pointers) {
            String partition = DynamoItems.plan(account, DynamoItems.uuid(pointer, "planId"));
            assertThat(MembershipItems.query(client, DATA, partition, "VERSION#")).hasSize(1);
            assertThat(MembershipItems.query(client, DATA, partition, "ACTIVITY#")).hasSize(1);
        }
    }

    @Test void theLocalSeedCreatesEverySubjectBeforeItsScenarioPlansAndRepeatsAsANoOp() {
        var service = service(new TestAccountPolicy(TestAccountPolicyTest.local()), mock(StaffAccessService.class));
        assertThat(service.seed()).isEqualTo(TestAccountRoster.entries().size());
        int plans = 0;
        var revisions = new HashMap<UUID, Long>();
        for (var entry : TestAccountRoster.entries()) {
            var scenario = LearnerScenario.of(Integer.parseInt(entry.template().substring(7)));
            assertSubjectAndPlans(entry.accountId(), plansOf(scenario));
            plans += plansOf(scenario);
            revisions.put(entry.accountId(), DynamoItems.number(meta(entry.accountId()), "revision"));
            var row = store().transact(work -> work.registry(entry.accountId()));
            assertThat(row.initialization()).isEqualTo("scenario");
            assertThat(row.lastChangedBy()).isEqualTo("seed");
            assertThat(row.revision()).isEqualTo(1);
        }
        assertThat(plans).isPositive();
        assertThat(meta(LocalAuthorAccess.ACCOUNT_ID)).containsKeys("revision", "lastPlanNumber", "createdAt"); // the operator of the scenario purchases
        assertThat(store().activeRegistry(false)).hasSize(TestAccountRoster.entries().size());
        assertThat(fakes.grants.get(new TestAccountRoster.Entry("learner04").accountId())).containsExactly("learn:algorithmic-patterns", "learn:core-java");
        // A repeat seed finds every subject and registry row: nothing inserted again, no plan added, no revision moved.
        assertThat(service.seed()).isZero();
        for (var entry : TestAccountRoster.entries()) {
            var scenario = LearnerScenario.of(Integer.parseInt(entry.template().substring(7)));
            assertSubjectAndPlans(entry.accountId(), plansOf(scenario));
            assertThat(DynamoItems.number(meta(entry.accountId()), "revision")).isEqualTo(revisions.get(entry.accountId()));
            assertThat(store().transact(work -> work.registry(entry.accountId())).revision()).isEqualTo(1);
        }
        assertThat(store().activeRegistry(false)).hasSize(TestAccountRoster.entries().size());
    }

    @Test void theDevRosterAdmitsEachAccountWithItsSubjectDurableBeforeItsPlan() {
        var policy = new TestAccountPolicy(TestAccountPolicyTest.cloud("dev"));
        var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true);
        seedAccount(client, owner.accountId(), NOW);
        fakes.person(owner, "sub-owner", true, List.of());
        var staff = mock(StaffAccessService.class);
        var ownerAccess = mock(LocalAuthorAccess.class);
        when(ownerAccess.allowed(owner)).thenReturn(true);
        var roster = new DevTestRoster(store(), policy, ownerAccess, service(policy, staff));
        assertThat(roster.next(owner).ready()).isEqualTo(5);
        for (var entry : TestAccountRoster.entries().subList(0, 5)) {
            var scenario = LearnerScenario.of(Integer.parseInt(entry.template().substring(7)));
            assertSubjectAndPlans(entry.accountId(), plansOf(scenario)); // DEV: the custom scenario gets the ready-made plan
            assertThat(TestAccountItems.resets(client, TABLES, json, entry.accountId())).extracting(r -> r.path("preset").asText()).containsExactly("create");
            assertThat(store().transact(work -> work.registry(entry.accountId())).creatorId()).isEqualTo(owner.accountId());
        }
        assertThat(meta(TestAccountRoster.entries().get(5).accountId())).isEmpty();
    }
}
