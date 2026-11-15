package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.service.DynamoFreePlanLedgerStore;
import com.lookahead.learning.content.service.DynamoPlanLifecycleStore;
import com.lookahead.learning.content.service.PlanService;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * Real DynamoDB Local: the three erasures next to their SQL twins. Reset (JdbcTestAccountStore: plans cascade, every
 * receipt tombstoned, ledgers kept), account removal (JdbcAccountRemovalStore.erase: ledgers and lifetime counter kept)
 * and test-account erasure (JdbcAccountDataErasure: ledgers deleted, counter 0).
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoPlanErasureDatabaseTest {
    private static DynamoDbClient client;
    private static final String ACCOUNTS = DynamoLocalFixture.TABLES.userAccountDetailsTableName();
    private final JsonMapper mapper = new JsonMapper();
    private final PlanJson json = new PlanJson(mapper);
    private final DynamoLocalFixture.MutableClock clock = new DynamoLocalFixture.MutableClock(Instant.parse("2026-11-10T06:00:00Z"));
    private final DynamoPlanRepository repository = new DynamoPlanRepository(client, DynamoLocalFixture.TABLES, mapper, clock);
    private final DynamoFreePlanLedgerStore ledgers = new DynamoFreePlanLedgerStore(client, DynamoLocalFixture.TABLES);
    private final DynamoPlanLifecycleStore lifecycle = new DynamoPlanLifecycleStore(client, DynamoLocalFixture.TABLES);
    private final DynamoSupportReceiptRepository receipts = new DynamoSupportReceiptRepository(client, DynamoLocalFixture.TABLES, clock);
    private final DynamoPlanErasure erasure = new DynamoPlanErasure(client, DynamoLocalFixture.TABLES, mapper, clock);
    private UUID owner;

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void owner() { owner = DynamoLocalFixture.owner(client); }

    long meta(String name) { return DynamoItems.number(DynamoLocalFixture.item(client, ACCOUNTS, DynamoItems.account(owner), "META"), name); }
    int count(String prefix) { return DynamoLocalFixture.items(client, DynamoItems.account(owner), prefix).size(); }
    UUID plan(UUID key) {
        UUID id = UUID.randomUUID(), version = UUID.randomUUID();
        repository.createPlan(owner, id, version, "Goal", mapper.createObjectNode(), "Plan", repository.allocatePlanNumber(owner));
        repository.insertVersion(owner, id, version, null, mapper.createObjectNode().put("a", 1), "digest", mapper.createObjectNode(), mapper.createObjectNode(), "create",
                mapper.createObjectNode().put("session", "fixture-a"));
        repository.insertReceipt(owner, key, "sha256:create", id, mapper.createObjectNode().put("planId", id.toString()), 201);
        return id;
    }

    SnapshotValidator validator() {
        try (var input = getClass().getResourceAsStream("/accounts/catalog.json")) { return new SnapshotValidator(mapper, mapper.readTree(input)); }
        catch (java.io.IOException unreadable) { throw new IllegalStateException(unreadable); }
    }

    @Test void resetTombstonesEveryReceiptRemovalKeepsLedgersAndTestAccountErasureResetsTheCounter() {
        UUID createKey = UUID.randomUUID(), deleteKey = UUID.randomUUID();
        UUID kept = plan(createKey), deleted = plan(UUID.randomUUID());
        lifecycle.reserve(owner, kept, LocalDate.parse("2026-11-10"), 7, 60, Set.of("learn:core-java"));
        repository.deletePlan(owner, deleted);
        // The 204 receipt as PlanService.delete writes it: the exact request hash of {"expectedRevision":1}.
        repository.insertReceipt(owner, deleteKey, json.digest("delete:" + deleted + ":" + json.canonical(mapper.createObjectNode().put("expectedRevision", 1))), deleted, null, 204);
        ledgers.recordCreation(owner, kept);
        ledgers.recordDeletion(owner, deleted);
        assertThat(receipts.reserve(owner.toString(), "key-1234567890123456", "a".repeat(64)).dispatch()).isTrue();
        assertThat(meta("freePlanCreations")).isEqualTo(1);
        assertThat(meta("lastPlanNumber")).isEqualTo(2);
        assertThat(repository.findReceipt(owner, deleteKey).orElseThrow().deleted()).as("the delete's own receipt replays 204 while the plan tombstone stands").isFalse();

        // Reset: plans, versions, list rows and lifecycle gone; every receipt a tombstone (the 204 one included); ledgers, support and counters kept.
        erasure.resetPlans(owner);
        for (String prefix : DynamoPlanErasure.RESET_PREFIXES) assertThat(count(prefix)).as(prefix).isZero();
        assertThat(DynamoLocalFixture.items(client, DynamoItems.plan(owner, kept), null)).isEmpty();
        assertThat(count("MUTATION#")).isEqualTo(3);
        for (UUID key : java.util.List.of(createKey, deleteKey)) {
            var stored = DynamoLocalFixture.item(client, ACCOUNTS, DynamoItems.account(owner), "MUTATION#" + key);
            assertThat(DynamoItems.flag(stored, "deleted")).isTrue();
            assertThat(stored.get("response").nul()).isTrue();
            assertThat(repository.findReceipt(owner, key).orElseThrow().deleted()).isTrue();
        }
        // Like the SQL reset: a replayed delete key answers 410 PLAN_DELETED afterwards, through PlanService.replay.
        var service = new PlanService(repository, mapper, validator(), json);
        assertThatThrownBy(() -> service.delete(owner, deleted, deleteKey, 1)).isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.status()).isEqualTo(410));
        assertThat(count("FREECREATE#")).isEqualTo(1); assertThat(count("FREEDELETE#")).isEqualTo(1);
        assertThat(count("SUPPORT#")).isEqualTo(1); assertThat(count("SUPPORTTIME#")).isEqualTo(1);
        assertThat(meta("freePlanCreations")).isEqualTo(1); assertThat(meta("lastPlanNumber")).isEqualTo(2);
        erasure.resetPlans(owner); // repeatable

        // Account removal: receipts and support go; the ledgers, the lifetime counter and the sequence stay, META is not rewritten.
        long revision = meta("revision");
        erasure.eraseAccount(owner);
        assertThat(count("MUTATION#")).isZero(); assertThat(count("SUPPORT#")).isZero(); assertThat(count("SUPPORTTIME#")).isZero();
        assertThat(count("FREECREATE#")).isEqualTo(1); assertThat(count("FREEDELETE#")).isEqualTo(1);
        assertThat(meta("freePlanCreations")).isEqualTo(1); assertThat(meta("lastPlanNumber")).isEqualTo(2); assertThat(meta("revision")).isEqualTo(revision);

        // Test-account erasure: the ledgers go and the counter returns to 0 under the owner fence; the sequence stays; repeatable without a write.
        erasure.erase(owner);
        assertThat(DynamoLocalFixture.items(client, DynamoItems.account(owner), null)).extracting(item -> item.get("sk").s()).containsExactly("META");
        assertThat(meta("freePlanCreations")).isZero(); assertThat(meta("lastPlanNumber")).isEqualTo(2); assertThat(meta("revision")).isEqualTo(revision + 1);
        erasure.erase(owner);
        assertThat(meta("revision")).isEqualTo(revision + 1);
        assertThat(ledgers.creations(owner)).isZero();
        // An owner that never had META (or was removed already) has nothing to reset.
        erasure.erase(UUID.randomUUID());
    }

    @Test void moreThanNinetyNineLedgerReceiptsGoInBatchesWithTheCounterOnTheLastOne() {
        for (int i = 0; i < 120; i++) client.putItem(PutItemRequest.builder().tableName(ACCOUNTS).item(DynamoItems.item(DynamoItems.account(owner),
                DynamoItems.timeKey("FREEDELETE", clock.instant().plusSeconds(i), UUID.randomUUID()), 1, Map.of("deletedAt", DynamoItems.s(clock.instant().toString())))).build());
        client.updateItem(b -> b.tableName(ACCOUNTS).key(DynamoItems.key(DynamoItems.account(owner), "META"))
                .updateExpression("SET freePlanCreations = :n").expressionAttributeValues(Map.of(":n", DynamoItems.n(15))));
        erasure.erase(owner);
        assertThat(count("FREEDELETE#")).isZero();
        assertThat(meta("freePlanCreations")).isZero();
        assertThat(meta("revision")).isEqualTo(2);
    }
}
