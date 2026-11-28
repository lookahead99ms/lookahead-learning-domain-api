package com.lookahead.domain.db.store.testaccounts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.access.AccountIdentity;
import com.lookahead.domain.db.store.access.AccountRecords;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.plan.StudyPlanRosterActivity;
import com.lookahead.domain.service.testaccounts.TestAccountDevRosterService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.ObjectMapper;

/**
 * The test-account store over a mocked client, with the account and study-plan lookups it is always
 * built with answering nothing.
 */
class TestAccountStoreTest {
  private final DynamoDbClient client =
      mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
  private final DynamoTableNames tables = DynamoTableNames.local();
  private final ObjectMapper json = new ObjectMapper();
  private final Instant now = Instant.parse("2026-11-10T06:00:00Z");
  private final AccountIdentity lookup =
      mock(AccountIdentity.class, withSettings().mockMaker(MockMakers.PROXY));
  private final AccountRecords records =
      mock(AccountRecords.class, withSettings().mockMaker(MockMakers.PROXY));
  private final TestAccountStudyPlanErasure planErasure =
      mock(TestAccountStudyPlanErasure.class, withSettings().mockMaker(MockMakers.PROXY));
  private final StudyPlanRosterActivity planActivity =
      mock(StudyPlanRosterActivity.class, withSettings().mockMaker(MockMakers.PROXY));
  private final TestAccountStore store =
      new TestAccountStore(
          client,
          tables,
          json,
          lookup,
          records,
          planErasure,
          planActivity,
          Clock.fixed(now, ZoneOffset.UTC));
  private final UUID account = UUID.randomUUID(), creator = UUID.randomUUID();

  private void empty() {
    when(client.getItem(any(GetItemRequest.class)))
        .thenReturn(GetItemResponse.builder().item(Map.of()).build());
    when(client.query(any(QueryRequest.class)))
        .thenReturn(QueryResponse.builder().items(List.of()).build());
  }

  @Test
  void anAccountNobodyKnowsReadsAsEmptyAndItsRecordWritesGoToTheAccountRecords() {
    empty();
    assertThat(store.staffRoles(account)).isEmpty();
    assertThat(store.displayName(account)).isNull();
    assertThat(store.accountOf("i", "s")).isNull();
    assertThat(store.holder("i", "s")).isNull();
    assertThat(store.signInCandidates(null)).isEmpty();
    assertThat(store.activeRegistry(true)).isEmpty();
    assertThat(store.registeredIds()).isEmpty();
    assertThat(store.deletedRosterIds()).isEmpty();
    assertThat(store.rosterFacts()).isEmpty();
    assertThat(store.lifetimeSpend(creator)).isZero();
    store.transact(work -> {
      assertThat(work.registry(account)).isNull();
      assertThat(work.rosterRow(account)).isFalse();
      assertThat(work.deletedRosterRow(account)).isFalse();
      assertThat(work.resetReceipt(account, UUID.randomUUID())).isNull();
      assertThat(work.activeRevision(account)).isNull();
      assertThat(work.reservation(creator, UUID.randomUUID())).isNull();
      assertThat(work.holdsAndSpend(creator, Duration.ofMinutes(15))).isZero();
      assertThat(work.lifetimeSpend(creator)).isZero();
      assertThat(work.creationState(creator, account)).isNull();
      assertThat(work.hasAccountRecord(account)).isFalse();
      assertThat(work.registryLocked(account)).isNull();
      work.deletePlans(account);
      work.deleteGrants(account);
      work.supersedePurchases(account);
      work.activateCreation(creator, account);
      work.deleteCreation(account);
      work.restoreCreations(account);
      work.resetRegistry(account, "scenario", creator); // no row: nothing to change
      work.grantTopics(account, List.of("learn:x"));
      work.admitTestAccount(account, "u", "d", creator);
      return null;
    });
    verify(planErasure).resetPlans(account);
    verify(records).clearAccessRecords(account);
    verify(records).grantTopics(account, List.of("learn:x"));
    verify(records).admitTestAccount(account, "u", "d", creator);
    verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    assertThatNullPointerException()
        .isThrownBy(() ->
            new TestAccountStore(
                null, tables, json, lookup, records, planErasure, planActivity, Clock.systemUTC()));
    // The four lookups are beans of every environment: a store built without one is a wiring
    // mistake, refused when it is built.
    assertThatNullPointerException()
        .isThrownBy(() ->
            new TestAccountStore(
                client, tables, json, null, records, planErasure, planActivity, Clock.systemUTC()));
    assertThatNullPointerException()
        .isThrownBy(() ->
            new TestAccountStore(
                client, tables, json, lookup, null, planErasure, planActivity, Clock.systemUTC()));
    assertThatNullPointerException()
        .isThrownBy(() ->
            new TestAccountStore(
                client, tables, json, lookup, records, null, planActivity, Clock.systemUTC()));
    assertThatNullPointerException()
        .isThrownBy(() ->
            new TestAccountStore(
                client, tables, json, lookup, records, planErasure, null, Clock.systemUTC()));
  }

  @Test
  void aNewRosterAccountGetsItsSubjectAtOnceAndRegistryDirectoryAndReceiptTogether() {
    empty();
    var captured = new ArrayList<ExecuteTransactionRequest>();
    when(client.executeTransaction(any(ExecuteTransactionRequest.class)))
        .thenAnswer(call -> {
          captured.add(call.getArgument(0));
          return ExecuteTransactionResponse.builder().build();
        });
    // The subject exists from its INSERT on (DynamoDB Local proves the real read-after-write; the
    // mock follows the transaction).
    var meta =
        DynamoItems.item(
            DynamoItems.account(account),
            "META",
            1,
            Map.of(
                "revision",
                DynamoItems.n(1),
                "lastPlanNumber",
                DynamoItems.n(0),
                "createdAt",
                DynamoItems.s(now.toString())));
    when(client.getItem(any(GetItemRequest.class)))
        .thenAnswer(call -> {
          GetItemRequest request = call.getArgument(0);
          boolean subject =
              !captured.isEmpty()
                  && request.key().get("pk").s().equals(DynamoItems.account(account))
                  && request.key().get("sk").s().equals("META");
          return GetItemResponse.builder().item(subject ? meta : Map.of()).build();
        });
    store.transact(work -> {
      assertThat(work.createSubject(account)).isTrue();
      assertThat(captured)
          .hasSize(
              1); // durable before anything else in the body: the scenario's plan writes fence on
      // it
      assertThat(work.createSubject(account)).isFalse();
      assertThat(work.registerSeed(account, "learner01", "scenario")).isTrue();
      assertThat(work.registerSeed(account, "learner01", "scenario")).isFalse();
      assertThat(work.registry(account).creationState()).isEqualTo("active");
      work.resetRegistry(
          account, "legacy", creator); // changes the row before it is inserted: still one INSERT
      assertThat(work.registry(account).initialization()).isEqualTo("legacy");
      work.insertReset(account, UUID.randomUUID(), creator, "create");
      work.lockSubject(creator);
      work.reserve(creator, UUID.randomUUID(), UUID.randomUUID(), "test-1");
      return null;
    });
    assertThat(captured).hasSize(2);
    var subject = captured.get(0).transactStatements();
    assertThat(subject).hasSize(1);
    assertThat(subject.getFirst().statement())
        .startsWith("INSERT INTO ")
        .contains(tables.userAccountDetailsTableName());
    assertThat(subject.getFirst().parameters())
        .extracting(AttributeValue::s)
        .contains(DynamoItems.account(account), "META");
    var statements =
        captured.get(1).transactStatements().stream()
            .map(ParameterizedStatement::statement)
            .toList();
    assertThat(statements).hasSize(6);
    // The subject is never inserted twice: the commit moves its revision as it stands at commit
    // time (1 -> 2 here).
    assertThat(statements.getFirst()).startsWith("UPDATE ").contains("revision=?");
    assertThat(captured.get(1).transactStatements().getFirst().parameters())
        .extracting(AttributeValue::n)
        .contains("2", "1");
    assertThat(statements.subList(1, 6)).allMatch(s -> s.startsWith("INSERT INTO "));
    assertThat(
            statements.stream().filter(s -> s.contains(tables.administratorAuditEventsTableName())))
        .hasSize(1);
    // One item is written once per transaction.
    UUID key = UUID.randomUUID();
    assertThatThrownBy(() ->
            store.transact(work -> {
              work.reserve(creator, key, account, "a");
              work.reserve(creator, key, account, "b");
              return null;
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("once");
  }

  @Test
  void aSubjectCreatedConcurrentlyIsLockedAtItsRevisionAndNotInsertedAgain() {
    var meta =
        DynamoItems.item(
            DynamoItems.account(account),
            "META",
            1,
            Map.of(
                "revision",
                DynamoItems.n(4),
                "lastPlanNumber",
                DynamoItems.n(0),
                "createdAt",
                DynamoItems.s(now.toString())));
    var metaReads = new AtomicInteger();
    when(client.getItem(any(GetItemRequest.class)))
        .thenAnswer(call -> {
          GetItemRequest request = call.getArgument(0);
          if (!request.key().get("sk").s().equals("META"))
            return GetItemResponse.builder().item(Map.of()).build();
          return GetItemResponse.builder()
              .item(metaReads.getAndIncrement() == 0 ? Map.of() : meta)
              .build(); // absent at the lock, present after the lost INSERT
        });
    when(client.query(any(QueryRequest.class)))
        .thenReturn(QueryResponse.builder().items(List.of()).build());
    var captured = new ArrayList<ExecuteTransactionRequest>();
    when(client.executeTransaction(any(ExecuteTransactionRequest.class)))
        .thenAnswer(call -> {
          captured.add(call.getArgument(0));
          if (captured.size() == 1)
            throw TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("DuplicateItem").build())
                .build();
          return ExecuteTransactionResponse.builder().build();
        });
    store.transact(work -> {
      assertThat(work.createSubject(account)).isFalse();
      assertThat(work.registerSeed(account, "learner01", "preserved")).isTrue();
      return null;
    });
    assertThat(captured).hasSize(2);
    var statements =
        captured.get(1).transactStatements().stream()
            .map(ParameterizedStatement::statement)
            .toList();
    assertThat(statements).hasSize(3);
    assertThat(statements.getFirst())
        .startsWith("UPDATE ")
        .contains("revision=?"); // META at 4, the revision the concurrent creator left
    assertThat(captured.get(1).transactStatements().getFirst().parameters())
        .extracting(AttributeValue::n)
        .contains("5", "4");
    assertThat(statements.subList(1, 3)).allMatch(s -> s.startsWith("INSERT INTO "));
  }

  @Test
  void aMissingCreatorReadsAsNoneAndResetReceiptsSortByParsedTime() {
    var row = json.createObjectNode();
    row.put("accountId", account.toString())
        .put("username", "u")
        .put("initialization", "scenario")
        .put("creationState", "active")
        .put("lastChangedBy", "seed")
        .put("lastChangedAt", now.toString())
        .put("revision", 1);
    assertThat(TestAccountStore.registryRow(row).creatorId())
        .as("attribute never written")
        .isNull();
    row.putNull("creatorId");
    assertThat(TestAccountStore.registryRow(row).creatorId())
        .as("attribute cleared to NULL")
        .isNull();
    row.put("creatorId", creator.toString());
    assertThat(TestAccountStore.registryRow(row).creatorId()).isEqualTo(creator);
    // Receipts at …00Z and …00.000001Z: the text order is the wrong way round, the parsed order is
    // not.
    Instant earlier = now, later = now.plusNanos(1_000);
    var create = TestAccountItems.resetItem(account, UUID.randomUUID(), creator, "create", earlier);
    var scenario =
        TestAccountItems.resetItem(account, UUID.randomUUID(), creator, "scenario", later);
    assertThat(create.get("occurredAt").s()).isGreaterThan(scenario.get("occurredAt").s());
    when(client.query(any(QueryRequest.class)))
        .thenReturn(QueryResponse.builder().items(List.of(scenario, create)).build());
    assertThat(TestAccountItems.resets(client, tables, json, account))
        .extracting(r -> r.path("preset").asText())
        .containsExactly("create", "scenario");
    assertThat(TestAccountItems.latestResetAt(client, tables, account)).isEqualTo(later);
    assertThat(create).doesNotContainKey("recordedAt");
  }

  @Test
  void aRetryingBodyRunsAgainAfterALostRaceAndAPlainOneDoesNot() {
    empty();
    var runs = new AtomicInteger();
    var commits = new AtomicInteger();
    var losses = new AtomicInteger(1);
    when(client.executeTransaction(any(ExecuteTransactionRequest.class)))
        .thenAnswer(call -> {
          if (commits.incrementAndGet() <= losses.get())
            throw TransactionCanceledException.builder()
                .cancellationReasons(
                    CancellationReason.builder().code("ConditionalCheckFailed").build())
                .build();
          return ExecuteTransactionResponse.builder().build();
        });
    Function<TestAccount.Work, UUID> body =
        work -> {
          runs.incrementAndGet();
          work.lockSubject(creator);
          UUID id = UUID.randomUUID();
          work.reserve(creator, UUID.randomUUID(), id, "test-" + runs.get());
          return id;
        };
    assertThat(store.transactRetrying(body)).isNotNull();
    assertThat(runs.get()).as("the body read and built again from a fresh snapshot").isEqualTo(2);
    assertThat(commits.get()).isEqualTo(2);
    runs.set(0);
    commits.set(0);
    losses.set(Integer.MAX_VALUE);
    assertThatThrownBy(() -> store.transactRetrying(body))
        .isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(runs.get()).as("bounded").isEqualTo(TestAccountStore.ATTEMPTS);
    runs.set(0);
    commits.set(0);
    assertThatThrownBy(() -> store.transact(body)).isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(runs.get()).as("a body with side effects runs once and keeps its 409").isEqualTo(1);
  }

  @Test
  void existingRowsAreUpdatedAtTheirRevisionAndStatusReadsCombineTheLanes() {
    var registry =
        DynamoItems.item(
            DynamoItems.account(account),
            TestAccountItems.REGISTRY_SK,
            1,
            Map.of(
                "accountId",
                DynamoItems.s(account.toString()),
                "username",
                DynamoItems.s("learner02@lookahead.dev"),
                "initialization",
                DynamoItems.s("scenario"),
                "creationState",
                DynamoItems.s("active"),
                "creatorId",
                DynamoItems.s(creator.toString()),
                "lastChangedBy",
                DynamoItems.s("x"),
                "lastChangedAt",
                DynamoItems.s(now.toString()),
                "createdAt",
                DynamoItems.s(now.toString()),
                "revision",
                DynamoItems.n(3)));
    var meta =
        DynamoItems.item(
            DynamoItems.account(account), "META", 1, Map.of("revision", DynamoItems.n(7)));
    when(client.getItem(any(GetItemRequest.class)))
        .thenAnswer(call -> {
          GetItemRequest request = call.getArgument(0);
          String sk = request.key().get("sk").s();
          return GetItemResponse.builder()
              .item(sk.equals("META") ? meta : sk.equals("TEST") ? registry : Map.of())
              .build();
        });
    when(client.query(any(QueryRequest.class)))
        .thenAnswer(call -> {
          QueryRequest request = call.getArgument(0);
          String pk = request.expressionAttributeValues().get(":pk").s();
          return QueryResponse.builder()
              .items(pk.equals(TestAccountItems.DIRECTORY_PK) ? List.of(registry) : List.of())
              .build();
        });
    var captured = new ArrayList<ExecuteTransactionRequest>();
    when(client.executeTransaction(any(ExecuteTransactionRequest.class)))
        .thenAnswer(call -> {
          captured.add(call.getArgument(0));
          return ExecuteTransactionResponse.builder().build();
        });
    // DLV-1016: the store answers the facts; "changed" is TestAccountDevRosterService's rule over
    // them.
    assertThat(store.rosterFacts())
        .singleElement()
        .satisfies(facts -> {
          assertThat(facts.accountId()).isEqualTo(account);
          assertThat(facts.creationState()).isEqualTo("active");
          assertThat(facts.hasPlan()).isFalse();
          assertThat(TestAccountDevRosterService.changed(facts)).isTrue();
        });
    assertThat(store.activeRegistry(true))
        .extracting(TestAccountRegistryRows.RegistryRow::username)
        .containsExactly("learner02@lookahead.dev");
    assertThat(store.deletedRosterIds()).isEmpty();
    store.transact(work -> {
      work.lockSubject(account);
      assertThat(work.rosterRow(account)).isTrue();
      assertThat(work.activeRevision(account)).isEqualTo(3);
      work.deleteRegistry(account, creator);
      assertThat(work.registry(account).creationState()).isEqualTo("deleted");
      assertThat(work.deletedRosterRow(account)).isTrue();
      return null;
    });
    var statements =
        captured.getFirst().transactStatements().stream()
            .map(ParameterizedStatement::statement)
            .toList();
    assertThat(statements).hasSize(3).allMatch(s -> s.startsWith("UPDATE "));
    assertThat(statements.get(0)).contains("revision=?"); // META at 7
    assertThat(statements.get(1)).contains("\"creationState\"=?").contains("revision=?");
    assertThat(statements.get(2))
        .doesNotContain("AND revision"); // the directory projection follows the registry row
  }
}
