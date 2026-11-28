package com.lookahead.domain.db.store.plan;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Real DynamoDB Local: lifetime creation receipts with their counter, the rolling deletion window
 * and the active free plan count.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class StudyPlanFreeLedgerStoreDatabaseTest {
  private static DynamoDbClient client;
  private static final String TABLE =
      StudyPlanStoreLocalFixture.TABLES.userAccountDetailsTableName();
  private final StudyPlanStoreLocalFixture.MutableClock clock =
      new StudyPlanStoreLocalFixture.MutableClock(Instant.parse("2026-10-16T12:00:00Z"));
  private final StudyPlanFreeLedgerStore store =
      new StudyPlanFreeLedgerStore(client, StudyPlanStoreLocalFixture.TABLES, clock);
  private UUID owner;

  @BeforeAll
  static void connect() {
    client = StudyPlanStoreLocalFixture.connect();
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
  }

  @BeforeEach
  void owner() {
    owner = StudyPlanStoreLocalFixture.owner(client);
  }

  long meta(String name) {
    return DynamoItems.number(
        StudyPlanStoreLocalFixture.item(client, TABLE, DynamoItems.account(owner), "META"), name);
  }

  void put(String pk, String sk, Map<String, AttributeValue> attributes) {
    client.putItem(
        PutItemRequest.builder()
            .tableName(TABLE)
            .item(DynamoItems.item(pk, sk, 1, attributes))
            .build());
  }

  @Test
  void lifetimeReceiptsCountOnceAndTheCounterNeverDecrements() {
    UUID plan = UUID.randomUUID();
    assertThat(store.creations(owner)).isZero();
    assertThat(store.creationRecorded(owner, plan)).isFalse();
    store.recordCreation(owner, plan);
    store.recordCreation(owner, plan);
    assertThat(store.creations(owner)).isEqualTo(1);
    assertThat(meta("freePlanCreations")).isEqualTo(1);
    assertThat(store.creationRecorded(owner, plan)).isTrue();
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "FREECREATE#"))
        .hasSize(1);
    // Inside one unit: two creations land together with one counter move, visible to the unit
    // before commit.
    new TransactionTemplate(new OwnerTransactionManager(client, StudyPlanStoreLocalFixture.TABLES))
        .executeWithoutResult(status -> {
          UUID second = UUID.randomUUID();
          store.recordCreation(owner, second);
          store.recordCreation(owner, second);
          assertThat(store.creationRecorded(owner, second)).isTrue();
          assertThat(store.creations(owner)).isEqualTo(2);
          assertThat(meta("freePlanCreations")).isEqualTo(1);
        });
    assertThat(meta("freePlanCreations")).isEqualTo(2);
    assertThat(meta("revision")).isEqualTo(3);
  }

  @Test
  void deletionWindowIsRollingAndStrictlyAfterSince() {
    Instant since = clock.instant().minus(Duration.ofDays(21));
    assertThat(store.deletionsSince(owner, since)).isZero();
    assertThat(store.oldestDeletionSince(owner, since)).isNull();
    store.recordDeletion(owner, UUID.randomUUID());
    clock.advance(Duration.ofDays(1));
    store.recordDeletion(owner, UUID.randomUUID());
    assertThat(store.deletionsSince(owner, since)).isEqualTo(2);
    assertThat(store.oldestDeletionSince(owner, since))
        .isEqualTo(Instant.parse("2026-10-16T12:00:00Z"));
    assertThat(store.deletionsSince(owner, Instant.parse("2026-10-16T12:00:00Z"))).isEqualTo(1);
    new TransactionTemplate(new OwnerTransactionManager(client, StudyPlanStoreLocalFixture.TABLES))
        .executeWithoutResult(status -> {
          store.recordDeletion(owner, UUID.randomUUID());
          assertThat(store.deletionsSince(owner, since)).isEqualTo(3);
        });
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "FREEDELETE#"))
        .hasSize(3);
  }

  @Test
  void plansAnswerEveryLivePlanWithItsLifecycleRowAndContentIdsReadOneVersion() {
    LocalDate today = LocalDate.parse("2026-10-16");
    UUID fresh = UUID.randomUUID(),
        archived = UUID.randomUUID(),
        expired = UUID.randomUUID(),
        deleted = UUID.randomUUID(),
        excluded = UUID.randomUUID(),
        paid = UUID.randomUUID(),
        undated = UUID.randomUUID();
    for (UUID id : List.of(fresh, archived, expired, deleted, excluded, paid, undated)) {
      UUID version = UUID.randomUUID();
      put(
          DynamoItems.account(owner),
          "PLAN#" + id,
          Map.of(
              "planId",
              DynamoItems.s(id.toString()),
              "currentVersion",
              DynamoItems.s(version.toString()),
              "deleted",
              DynamoItems.bool(id.equals(deleted))));
      put(
          DynamoItems.plan(owner, id),
          "VERSION#" + version,
          Map.of(
              "contentIds",
              DynamoItems.strings(
                  id.equals(paid) ? Set.of("lesson-paid") : Set.of("lesson-free"))));
    }
    for (var entry :
        Map.of(fresh, "active", archived, "archived", expired, "active", excluded, "active")
            .entrySet())
      put(
          DynamoItems.account(owner),
          "LIFECYCLE#" + entry.getKey(),
          Map.of(
              "planId",
              DynamoItems.s(entry.getKey().toString()),
              "state",
              DynamoItems.s(entry.getValue()),
              "startDate",
              DynamoItems.s(entry.getKey().equals(expired) ? "2026-01-01" : "2026-10-10"),
              "dayCount",
              DynamoItems.n(7),
              "dailyMinutes",
              DynamoItems.n(60),
              "subjectIds",
              DynamoItems.strings(Set.of("learn:x"))));
    // DLV-1016: the store answers rows; which of them count is StudyPlanFreeAllowanceService's rule
    // (tested there).
    var rows = store.plans(owner);
    assertThat(rows)
        .extracting(StudyPlanFreeLedger.PlanRow::id)
        .containsExactlyInAnyOrder(fresh, archived, expired, excluded, paid, undated)
        .doesNotContain(deleted);
    var byId = new HashMap<UUID, StudyPlanFreeLedger.PlanRow>();
    for (var row : rows) byId.put(row.id(), row);
    assertThat(byId.get(archived).state()).isEqualTo("archived");
    assertThat(byId.get(undated).state()).as("no lifecycle row").isNull();
    assertThat(byId.get(expired).startDate()).isEqualTo(LocalDate.parse("2026-01-01"));
    assertThat(byId.get(fresh).startDate()).isEqualTo(today.minusDays(6));
    assertThat(byId.get(fresh).dayCount()).isEqualTo(7);
    assertThat(store.contentIds(owner, paid, byId.get(paid).currentVersion()))
        .containsExactly("lesson-paid");
    assertThat(store.contentIds(owner, fresh, byId.get(fresh).currentVersion()))
        .containsExactly("lesson-free");
    assertThatIllegalStateException()
        .isThrownBy(() -> store.contentIds(owner, fresh, UUID.randomUUID()))
        .withMessage("Plan has no current version");
  }
}
