package com.lookahead.domain.db.store.plan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionManager;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionReads;
import com.lookahead.domain.db.store.review.SupportReceiptStore;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.service.plan.StudyPlanFreeAllowanceService;
import com.lookahead.domain.service.plan.StudyPlanLifecycleService;
import com.lookahead.domain.service.plan.StudyPlanReadyMadeWriteService;
import com.lookahead.domain.service.plan.StudyPlanService;
import com.lookahead.domain.util.StudyPlanJson;
import com.lookahead.domain.util.SupportFeedbackRules;
import com.lookahead.domain.validator.StudyPlanRecoveryValidator;
import com.lookahead.domain.validator.StudyPlanSnapshotValidator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real DynamoDB Local: the whole StudyPlanService flow through the production validator, stores and
 * transaction manager.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class StudyPlanStoreDatabaseTest {
  private static DynamoDbClient client;
  private static final String ACCOUNTS =
      StudyPlanStoreLocalFixture.TABLES.userAccountDetailsTableName();
  private static final String STUDY =
      StudyPlanStoreLocalFixture.TABLES.userStudyPlanDetailsTableName();
  private final JsonMapper mapper = new JsonMapper();
  private final StudyPlanJson json = new StudyPlanJson(mapper);
  private final AccountGrantService accounts = mock(AccountGrantService.class);
  private final ProtectedContentManifestService publication =
      mock(ProtectedContentManifestService.class);
  private final OwnerTransactionManager manager =
      new OwnerTransactionManager(client, StudyPlanStoreLocalFixture.TABLES);
  private StudyPlanStore repository;
  private StudyPlanService service, undated;
  private ObjectNode template;
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
  void service() throws Exception {
    StudyPlanStoreLocalFixture.purge(client, ACCOUNTS, StudyPlanStore.PURGE_PARTITION);
    owner = StudyPlanStoreLocalFixture.owner(client);
    when(accounts.findTopicGrants(owner)).thenReturn(Set.of("learn:core-java"));
    when(publication.freeContentIds()).thenReturn(Set.of("fixture-a"));
    JsonNode catalog;
    try (var input = getClass().getResourceAsStream("/accounts/catalog.json")) {
      catalog = mapper.readTree(input);
    }
    try (var input = getClass().getResourceAsStream("/accounts/generated-plan.json")) {
      template = (ObjectNode) mapper.readTree(input);
    }
    repository = new StudyPlanStore(client, StudyPlanStoreLocalFixture.TABLES, mapper);
    accountCatalog = new AccountCatalogService(mapper, catalog);
    var validator = new StudyPlanSnapshotValidator(accountCatalog.catalog());
    service = proxy(validator, true);
    // Like the SQL free-allowance test: no lifecycle, so every plan stays undated and counts as an
    // active free plan.
    undated = proxy(validator, false);
  }

  private AccountCatalogService accountCatalog;

  private StudyPlanService proxy(StudyPlanSnapshotValidator validator, boolean lifecycle) {
    var target =
        new StudyPlanService(
            repository,
            repository,
            repository,
            mapper,
            validator,
            accountCatalog,
            json,
            new StudyPlanRecoveryValidator(accountCatalog.catalog()));
    target.configureGrantAccounts(accounts);
    if (lifecycle)
      target.configureLifecycle(
          new StudyPlanLifecycleService(
              new StudyPlanLifecycleStore(client, StudyPlanStoreLocalFixture.TABLES), mapper),
          mock(StudyPlanReadyMadeWriteService.class));
    target.configureFreeAllowance(
        new StudyPlanFreeAllowanceService(
            new StudyPlanFreeLedgerStore(client, StudyPlanStoreLocalFixture.TABLES),
            accounts,
            publication));
    var factory = new ProxyFactory(target);
    factory.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (StudyPlanService) factory.getProxy();
  }

  UUID id(JsonNode result) {
    return UUID.fromString(result.path("planId").asText());
  }

  long meta(String name) {
    return DynamoItems.number(
        StudyPlanStoreLocalFixture.item(client, ACCOUNTS, DynamoItems.account(owner), "META"),
        name);
  }

  void failure(String code, ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  ObjectNode body(JsonNode previous) {
    return mapper.createObjectNode().put("expectedRevision", previous.path("revision").asLong());
  }

  /**
   * A dated create: the lifecycle reserves the course from that day, so each plan of one owner
   * starts on its own days.
   */
  ObjectNode dated(int daysFromNow) {
    return template
        .deepCopy()
        .put("startDate", LocalDate.now(ZoneOffset.UTC).plusDays(daysFromNow).toString());
  }

  @Test
  void createReplayListRenameActivityVersionLifecycleAndDeleteAreEachOneOwnerTransaction() {
    UUID key = UUID.randomUUID();
    JsonNode first = service.create(owner, key, dated(0), false).data();
    assertThat(first.path("planNumber").asLong()).isEqualTo(1);
    assertThat(first.path("name").asText()).startsWith("Study plan #1_");
    assertThat(first.path("lifecycle").path("state").asText()).isEqualTo("active");
    assertThatThrownBy(() -> service.create(owner, UUID.randomUUID(), template.deepCopy(), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(meta("revision")).isEqualTo(2);
    assertThat(meta("lastPlanNumber")).isEqualTo(1);
    // Exact-hash replay returns the stored answer; a different body on the same key is refused.
    assertThat(json.canonical(service.create(owner, key, dated(0), false).data()))
        .isEqualTo(json.canonical(first));
    assertThat(meta("revision")).isEqualTo(2);
    failure(
        "IDEMPOTENCY_KEY_REUSED",
        () -> service.create(owner, key, dated(0).put("goal", "Changed"), false));
    failure(
        "PLAN_SUBJECT_CONFLICT", () -> service.create(owner, UUID.randomUUID(), dated(3), false));
    failure("PLAN_NOT_FOUND", () -> service.get(UUID.randomUUID(), id(first), null));
    failure("VERSION_NOT_FOUND", () -> service.get(owner, id(first), UUID.randomUUID()));
    failure("AUTHENTICATION_REQUIRED", () -> service.list(UUID.randomUUID(), 10, null));

    UUID renameKey = UUID.randomUUID();
    JsonNode renamed =
        service
            .rename(owner, id(first), renameKey, body(first).put("name", "  Interview practice  "))
            .data();
    assertThat(renamed.path("name").asText()).isEqualTo("Interview practice");
    assertThat(renamed.path("revision").asLong()).isEqualTo(2);
    assertThat(
            json.canonical(
                service
                    .rename(
                        owner,
                        id(first),
                        renameKey,
                        body(first).put("name", "  Interview practice  "))
                    .data()))
        .isEqualTo(json.canonical(renamed));
    failure(
        "REVISION_CONFLICT",
        () ->
            service.rename(owner, id(first), UUID.randomUUID(), body(first).put("name", "Stale")));

    var activity =
        body(renamed).put("versionId", renamed.path("versionId").asText()).put("studyDay", 1);
    activity.set(
        "operations",
        mapper.readTree(
            "[{\"type\":\"setContentCompletion\",\"canonicalContentId\":\"fixture-a"
                + "\",\"completed\":true},{\"type\":\"setNote\","
                + "\"canonicalContentId\":\"fixture-a\",\"text\":\"remember"
                + " this\"}]"));
    JsonNode progressed = service.activity(owner, id(first), UUID.randomUUID(), activity).data();
    assertThat(progressed.path("progress").path("completedContentIds").get(0).asText())
        .isEqualTo("fixture-a");
    assertThat(progressed.path("progress").path("notes").path("fixture-a").asText())
        .isEqualTo("remember this");
    // One activity item per mutation (rename at revision 2, this activity at revision 3), the
    // latter holding both events.
    var activityItems =
        StudyPlanStoreLocalFixture.items(client, DynamoItems.plan(owner, id(first)), "ACTIVITY#");
    assertThat(activityItems).hasSize(2);
    assertThat(activityItems.get(1).get("sk").s()).startsWith("ACTIVITY#0000000003#");
    assertThat(activityItems.get(1).get("events").l()).hasSize(2);

    var version =
        template
            .deepCopy()
            .put("expectedRevision", progressed.path("revision").asLong())
            .put("reason", "update-plan");
    var recovery =
        mapper
            .createObjectNode()
            .put("strategy", "none")
            .put("elapsedDays", 0)
            .put("deadlineDays", 7);
    recovery.putArray("deferredContentIds");
    version.set("recovery", recovery);
    JsonNode saved = service.saveVersion(owner, id(first), UUID.randomUUID(), version).data();
    assertThat(saved.path("versionId").asText())
        .isNotEqualTo(progressed.path("versionId").asText());
    assertThat(saved.path("revision").asLong()).isEqualTo(4);
    assertThat(
            service
                .get(owner, id(first), UUID.fromString(progressed.path("versionId").asText()))
                .path("versionId")
                .asText())
        .isEqualTo(progressed.path("versionId").asText());
    assertThat(
            StudyPlanStoreLocalFixture.items(
                client, DynamoItems.plan(owner, id(first)), "VERSION#"))
        .hasSize(2);

    var activate =
        body(saved)
            .put("state", "active")
            .put("startDate", LocalDate.now(ZoneOffset.UTC).toString());
    JsonNode active = service.lifecycle(owner, id(first), UUID.randomUUID(), activate).data();
    assertThat(active.path("lifecycle").path("state").asText()).isEqualTo("active");
    assertThat(active.path("lifecycle").path("startDate").asText())
        .isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
    JsonNode archived =
        service
            .lifecycle(owner, id(first), UUID.randomUUID(), body(active).put("state", "archived"))
            .data();
    assertThat(archived.path("lifecycle").path("state").asText()).isEqualTo("archived");
    assertThat(archived.path("progress")).isEqualTo(progressed.path("progress"));
    // With the first plan archived its course is free again: the second plan lists first
    // (created_at DESC).
    JsonNode second = service.create(owner, UUID.randomUUID(), dated(30), false).data();
    // created_at DESC, id DESC with limit/offset through the list projection.
    JsonNode page = service.list(owner, 1, null);
    assertThat(page.path("plans")).hasSize(1);
    assertThat(page.path("plans").get(0).path("planId").asText())
        .isEqualTo(second.path("planId").asText());
    assertThat(page.path("nextPlanNumber").asLong()).isEqualTo(3);
    JsonNode next = service.list(owner, 1, page.path("nextCursor").asText());
    assertThat(next.path("plans").get(0).path("planId").asText())
        .isEqualTo(first.path("planId").asText());
    assertThat(next.path("nextCursor").isNull()).isTrue();

    UUID deleteKey = UUID.randomUUID();
    assertThat(
            service
                .delete(owner, id(first), deleteKey, archived.path("revision").asLong())
                .outcome())
        .isEqualTo(StudyPlanService.Outcome.DELETED);
    assertThat(
            service
                .delete(owner, id(first), deleteKey, archived.path("revision").asLong())
                .outcome())
        .isEqualTo(StudyPlanService.Outcome.DELETED);
    failure("PLAN_NOT_FOUND", () -> service.get(owner, id(first), null));
    failure(
        "PLAN_DELETED",
        () ->
            service.rename(
                owner, id(first), renameKey, body(first).put("name", "  Interview practice  ")));
    assertThat(service.list(owner, 10, null).path("plans")).hasSize(1);
    assertThat(
            StudyPlanStoreLocalFixture.items(
                client, DynamoItems.account(owner), "LIFECYCLE#" + id(first)))
        .isEmpty();
    var tombstone =
        StudyPlanStoreLocalFixture.item(
            client, ACCOUNTS, DynamoItems.account(owner), "PLAN#" + id(first));
    assertThat(DynamoItems.flag(tombstone, "deleted")).isTrue();
    assertThat(tombstone.get("progress").nul()).isTrue();
    // Physical purge: history and receipt projections go, the tombstone and the receipt answers
    // stay.
    var purge =
        new StudyPlanPurgeStore(client, StudyPlanStoreLocalFixture.TABLES, Clock.systemUTC());
    var queuedPurges = purge.queued(25);
    assertThat(queuedPurges).hasSize(1);
    purge.purge(queuedPurges.getFirst());
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.plan(owner, id(first)), null))
        .isEmpty();
    assertThat(StudyPlanStoreLocalFixture.items(client, StudyPlanStore.PURGE_PARTITION, null))
        .isEmpty();
    failure(
        "PLAN_DELETED",
        () ->
            service.rename(
                owner, id(first), renameKey, body(first).put("name", "  Interview practice  ")));
    assertThat(
            service
                .delete(owner, id(first), deleteKey, archived.path("revision").asLong())
                .outcome())
        .isEqualTo(StudyPlanService.Outcome.DELETED);
    assertThat(
            DynamoItems.flag(
                StudyPlanStoreLocalFixture.item(
                    client, ACCOUNTS, DynamoItems.account(owner), "MUTATION#" + renameKey),
                "deleted"))
        .isTrue();
  }

  @Test
  void concurrentCreatesOfOneOwnerSerializeOnTheMetaRevision() throws Exception {
    var start = new CountDownLatch(1);
    IntFunction<Callable<Object>> create =
        days ->
            () -> {
              start.await();
              try {
                return undated
                    .create(owner, UUID.randomUUID(), template.deepCopy(), false)
                    .outcome();
              } catch (RuntimeException failure) {
                return failure;
              }
            };
    List<Object> outcomes;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(create.apply(0));
      var b = pool.submit(create.apply(30));
      start.countDown();
      outcomes = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
    long created =
        outcomes.stream().filter(outcome -> StudyPlanService.Outcome.CREATED == outcome).count();
    assertThat(created).isBetween(1L, 2L);
    for (var outcome : outcomes)
      if (!(outcome instanceof StudyPlanService.Outcome))
        assertThat(outcome).isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "PLAN#"))
        .hasSize((int) created);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "MUTATION#"))
        .hasSize((int) created);
    assertThat(meta("lastPlanNumber")).isEqualTo(created);
  }

  @Test
  void freePlanSlotsDeletionWindowAndLifetimeCounterFollowTheSqlRules() {
    when(accounts.findTopicGrants(owner)).thenReturn(Set.of("content:fixture-a"));
    JsonNode first = undated.create(owner, UUID.randomUUID(), template.deepCopy(), false).data();
    undated.create(owner, UUID.randomUUID(), template.deepCopy(), false);
    assertThat(
            undated.list(owner, 10, null).path("plans").get(0).path("freeContentPlan").asBoolean())
        .isTrue();
    failure(
        "FREE_ACTIVE_PLAN_LIMIT",
        () -> undated.create(owner, UUID.randomUUID(), template.deepCopy(), false));
    UUID key = UUID.randomUUID();
    undated.delete(owner, id(first), key, 1);
    assertThat(undated.delete(owner, id(first), key, 1).outcome())
        .isEqualTo(StudyPlanService.Outcome.DELETED);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "FREEDELETE#"))
        .hasSize(1);
    JsonNode third = undated.create(owner, UUID.randomUUID(), template.deepCopy(), false).data();
    undated.delete(owner, id(third), UUID.randomUUID(), 1);
    JsonNode fourth = undated.create(owner, UUID.randomUUID(), template.deepCopy(), false).data();
    undated.delete(owner, id(fourth), UUID.randomUUID(), 1);
    JsonNode fifth = undated.create(owner, UUID.randomUUID(), template.deepCopy(), false).data();
    failure("FREE_DELETE_LIMIT", () -> undated.delete(owner, id(fifth), UUID.randomUUID(), 1));
    assertThat(undated.get(owner, id(fifth), null).path("planId").asText())
        .isEqualTo(id(fifth).toString());
    assertThat(meta("freePlanCreations")).isEqualTo(5);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "FREECREATE#"))
        .hasSize(5);
    client.updateItem(b ->
        b.tableName(ACCOUNTS)
            .key(DynamoItems.key(DynamoItems.account(owner), "META"))
            .updateExpression("SET freePlanCreations = :n")
            .expressionAttributeValues(Map.of(":n", DynamoItems.n(15))));
    // Like the SQL test's raw DELETE of the fifth plan: with one active slot free, the lifetime
    // counter is what refuses.
    client.updateItem(b ->
        b.tableName(ACCOUNTS)
            .key(DynamoItems.key(DynamoItems.account(owner), "PLAN#" + id(fifth)))
            .updateExpression("SET deleted = :t")
            .expressionAttributeValues(Map.of(":t", DynamoItems.bool(true))));
    failure(
        "FREE_LIFETIME_PLAN_LIMIT",
        () -> undated.create(owner, UUID.randomUUID(), template.deepCopy(), false));
    // Account removal keeps the ledgers and the lifetime counter (the retired PostgreSQL store kept
    // free_plan_*); the
    // test-account erasure deletes them and resets the counter; both keep the plan-number sequence.
    var erasure = new StudyPlanErasureStore(client, StudyPlanStoreLocalFixture.TABLES, mapper);
    erasure.eraseAccount(owner);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "PLAN#"))
        .isEmpty();
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "MUTATION#"))
        .isEmpty();
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "FREECREATE#"))
        .hasSize(5);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "FREEDELETE#"))
        .hasSize(3);
    assertThat(meta("freePlanCreations")).isEqualTo(15);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.plan(owner, id(fifth)), null))
        .isEmpty();
    erasure.erase(owner);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), null))
        .extracting(item -> item.get("sk").s())
        .containsExactly("META");
    assertThat(meta("freePlanCreations")).isZero();
    assertThat(meta("lastPlanNumber")).isEqualTo(5);
  }

  @Test
  void largeVersionsAndResponsesArePublishedAsDocumentsAndRetiredByThePurge() {
    var transactions = new TransactionTemplate(manager);
    UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), key = UUID.randomUUID();
    ObjectNode big = (ObjectNode) template.path("snapshot").deepCopy();
    big.put("filler", "x".repeat(StudyPlanStore.INLINE_BYTES + 1024));
    var membership = mapper.createObjectNode().put("smoke-session-fixture-a", "fixture-a");
    transactions.executeWithoutResult(status -> {
      repository.lockMutation(owner, key);
      repository.createPlan(
          owner,
          plan,
          version,
          "Big",
          mapper.createObjectNode(),
          "Big plan",
          repository.allocatePlanNumber(owner));
      repository.insertVersion(
          owner,
          plan,
          version,
          null,
          big,
          json.digest(json.canonical(big)),
          template.path("provenance"),
          mapper.createObjectNode(),
          "create",
          membership);
      // Read-your-writes inside the unit, before anything is committed.
      assertThat(
              repository
                  .findVersion(owner, plan, version)
                  .orElseThrow()
                  .snapshot()
                  .path("filler")
                  .asText())
          .hasSize(StudyPlanStore.INLINE_BYTES + 1024);
      assertThat(repository.containsHistoricalContent(owner, plan, "fixture-a")).isTrue();
      repository.insertReceipt(owner, key, "sha256:big", plan, big, 201);
    });
    assertThat(
            repository
                .findVersion(owner, plan, version)
                .orElseThrow()
                .snapshot()
                .path("filler")
                .asText())
        .hasSize(StudyPlanStore.INLINE_BYTES + 1024);
    var receipt = repository.findReceipt(owner, key).orElseThrow();
    assertThat(receipt.deleted()).isFalse();
    assertThat(receipt.response().path("filler").asText())
        .hasSize(StudyPlanStore.INLINE_BYTES + 1024);
    assertThat(repository.containsHistoricalContent(owner, plan, "fixture-b")).isFalse();
    var versionItem =
        StudyPlanStoreLocalFixture.item(
            client, ACCOUNTS, DynamoItems.plan(owner, plan), "VERSION#" + version);
    assertThat(versionItem.get("document")).isNull();
    String manifestPk = "DOC#" + owner + "#" + version;
    assertThat(
            StudyPlanStoreLocalFixture.item(client, STUDY, manifestPk, "MANIFEST")
                .get("lifecycle")
                .s())
        .isEqualTo("COMMITTED");
    assertThat(OwnerTransactionReads.queryAll(client, STUDY, manifestPk, "CHUNK#")).isNotEmpty();
    // Deleting the plan tombstones it; the purge retires both documents and marks the old receipt
    // deleted without bytes.
    transactions.executeWithoutResult(status -> {
      repository.findPlan(owner, plan, true);
      repository.deletePlan(owner, plan);
    });
    assertThat(repository.findReceipt(owner, key).orElseThrow().deleted()).isTrue();
    new StudyPlanPurgeStore(client, StudyPlanStoreLocalFixture.TABLES, Clock.systemUTC())
        .purgePlan(owner, plan);
    assertThat(
            StudyPlanStoreLocalFixture.item(client, STUDY, manifestPk, "MANIFEST")
                .get("lifecycle")
                .s())
        .isEqualTo("RETIRED");
    assertThat(OwnerTransactionReads.queryAll(client, STUDY, manifestPk, "CHUNK#")).isEmpty();
    var stored =
        StudyPlanStoreLocalFixture.item(
            client, ACCOUNTS, DynamoItems.account(owner), "MUTATION#" + key);
    assertThat(DynamoItems.flag(stored, "deleted")).isTrue();
    assertThat(stored.get("documentId").nul()).isTrue();
    assertThat(repository.findReceipt(owner, key).orElseThrow().deleted()).isTrue();
    assertThat(repository.findVersion(owner, plan, version)).isEmpty();
    // Purging again is a no-op; erasing the owner afterwards drops the pointers and receipts of
    // this lane only.
    new StudyPlanPurgeStore(client, StudyPlanStoreLocalFixture.TABLES, Clock.systemUTC())
        .purgePlan(owner, plan);
    new StudyPlanErasureStore(client, StudyPlanStoreLocalFixture.TABLES, mapper).erase(owner);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), null))
        .extracting(item -> item.get("sk").s())
        .containsExactly("META");
  }

  /**
   * This lane's document pointers under the account: one per published version or receipt document.
   */
  List<String> pointers() {
    return StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "RECORD#v1#")
        .stream()
        .map(item -> DynamoItems.decode(item.get("sk").s().substring("RECORD#".length())))
        .filter(record -> record.startsWith("plan-"))
        .toList();
  }

  /**
   * A create whose version exceeds the inline budget: 300 blocked items with 1,000-character titles
   * pass the validator.
   */
  ObjectNode bigDated(int daysFromNow) {
    var body = dated(daysFromNow);
    var blocked = ((ObjectNode) body.path("snapshot")).putArray("blockedItems");
    for (int i = 0; i < 300; i++) {
      var item = blocked.addObject().put("id", "fixture-a").put("title", "x".repeat(1000));
      item.putArray("prerequisiteIds");
    }
    return body;
  }

  /**
   * DLV-970 review Q2: a lifecycle refusal is decided before insertVersion publishes anything; an
   * accepted big create does publish.
   */
  @Test
  void aLifecycleRefusalComesBeforeTheVersionDocumentIsPublished() {
    service.create(owner, UUID.randomUUID(), dated(0), false);
    failure(
        "PLAN_SUBJECT_CONFLICT",
        () -> service.create(owner, UUID.randomUUID(), bigDated(3), false));
    assertThat(pointers()).isEmpty();
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "PLAN#"))
        .hasSize(1);
    assertThat(meta("revision")).isEqualTo(2);
    // The same snapshot without a lifecycle to refuse it is accepted and does go through the
    // document store.
    JsonNode accepted = undated.create(owner, UUID.randomUUID(), bigDated(30), false).data();
    var version =
        StudyPlanStoreLocalFixture.item(
            client,
            ACCOUNTS,
            DynamoItems.plan(owner, id(accepted)),
            "VERSION#" + accepted.path("versionId").asText());
    assertThat(version.get("document"))
        .as("above the inline budget: published as a document")
        .isNull();
    assertThat(version.get("documentId")).isNotNull();
    assertThat(pointers())
        .hasSize(2)
        .anyMatch(record -> record.startsWith("plan-version:"))
        .anyMatch(record -> record.startsWith("plan-receipt:"));
    assertThat(undated.get(owner, id(accepted), null).path("snapshot").path("blockedItems"))
        .hasSize(300);
  }

  /**
   * DLV-970 review Q8: one mutation key on two threads gives one plan and one answer; the loser's
   * retry replays it.
   */
  @Test
  void twoThreadsOnOneMutationKeyYieldOnePlanAndOneAnswer() throws Exception {
    UUID key = UUID.randomUUID();
    var start = new CountDownLatch(1);
    Callable<Object> create =
        () -> {
          start.await();
          try {
            return undated.create(owner, key, template.deepCopy(), false);
          } catch (RuntimeException failure) {
            return failure;
          }
        };
    List<Object> outcomes;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(create);
      var b = pool.submit(create);
      start.countDown();
      outcomes = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
    var answers = new ArrayList<JsonNode>();
    for (var outcome : outcomes) {
      if (outcome instanceof StudyPlanService.Mutation mutation) {
        assertThat(mutation.outcome()).isEqualTo(StudyPlanService.Outcome.CREATED);
        answers.add(mutation.data());
      } else {
        assertThat(outcome).isInstanceOf(DynamoWrites.Conflict.class);
        var replayed = undated.create(owner, key, template.deepCopy(), false);
        assertThat(replayed.outcome()).isEqualTo(StudyPlanService.Outcome.CREATED);
        answers.add(replayed.data());
      }
    }
    assertThat(json.canonical(answers.get(0))).isEqualTo(json.canonical(answers.get(1)));
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "PLAN#"))
        .hasSize(1);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "MUTATION#"))
        .hasSize(1);
    assertThat(meta("lastPlanNumber")).isEqualTo(1);
  }

  /**
   * DLV-970 review Q8: a support reservation and a plan mutation of one owner serialize on the META
   * fence; the later commit loses and retries.
   */
  @Test
  void aSupportReservationRacingAPlanMutationSerializesOnTheOwnerFence() throws Exception {
    var receipts = new SupportReceiptStore(client, StudyPlanStoreLocalFixture.TABLES);
    var transactions = new TransactionTemplate(manager);
    UUID plan = UUID.randomUUID(), version = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(1)) {
      assertThatThrownBy(() ->
              transactions.executeWithoutResult(status -> {
                repository.lockMutation(owner, UUID.randomUUID());
                repository.createPlan(
                    owner,
                    plan,
                    version,
                    "Goal",
                    mapper.createObjectNode(),
                    "Raced",
                    repository.allocatePlanNumber(owner));
                // Another thread (no bound unit there) reserves and commits while this unit holds
                // its fence at revision 1.
                var reservation =
                    pool.submit(() ->
                        receipts.reserve(
                            owner.toString(),
                            "key-1234567890123456",
                            "a".repeat(64),
                            SupportFeedbackRules.PER_WINDOW,
                            SupportFeedbackRules.WINDOW));
                try {
                  assertThat(reservation.get(20, TimeUnit.SECONDS).dispatch()).isTrue();
                } catch (Exception interrupted) {
                  throw new IllegalStateException(interrupted);
                }
              }))
          .isInstanceOf(DynamoWrites.Conflict.class);
    }
    assertThat(meta("revision")).isEqualTo(2);
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.account(owner), "SUPPORT#"))
        .hasSize(1);
    assertThat(repository.findPlan(owner, plan, false)).isEmpty();
    assertThat(meta("lastPlanNumber")).isZero();
    // The client's retry of the mutation reads the moved fence and lands.
    transactions.executeWithoutResult(status -> {
      repository.lockMutation(owner, UUID.randomUUID());
      repository.createPlan(
          owner,
          plan,
          version,
          "Goal",
          mapper.createObjectNode(),
          "Raced",
          repository.allocatePlanNumber(owner));
    });
    assertThat(repository.findPlan(owner, plan, false)).isPresent();
    assertThat(meta("revision")).isEqualTo(3);
    assertThat(meta("lastPlanNumber")).isEqualTo(1);
  }

  /**
   * DLV-970 review Q4/Q8: more than 100 plans list through the BatchGetItem limit; a plan whose
   * item is gone is dropped.
   */
  @Test
  void listPagesMoreThanOneHundredPlansAndDropsAPlanWhoseItemIsGone() {
    var ids = new ArrayList<UUID>();
    for (int i = 1; i <= 101; i++) {
      UUID id = UUID.randomUUID();
      repository.createPlan(
          owner, id, UUID.randomUUID(), "Goal", mapper.createObjectNode(), "Plan " + i, i);
      ids.add(id);
    }
    var page = repository.list(owner, 150, 0);
    assertThat(page).hasSize(101);
    assertThat(page.getFirst().id()).as("created_at DESC").isEqualTo(ids.getLast());
    assertThat(page).extracting(record -> record.id()).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(repository.list(owner, 50, 60)).hasSize(41);
    assertThat(repository.list(owner, 50, 101)).isEmpty();
    client.deleteItem(b ->
        b.tableName(ACCOUNTS)
            .key(DynamoItems.key(DynamoItems.account(owner), "PLAN#" + ids.get(0))));
    assertThat(repository.list(owner, 150, 0))
        .hasSize(100)
        .extracting(record -> record.id())
        .doesNotContain(ids.get(0));
  }

  @Test
  void writesOutsideATransactionAreEachTheirOwnTransactionAndMissingMetaIsRefused() {
    UUID plan = UUID.randomUUID(), version = UUID.randomUUID();
    long number = repository.allocatePlanNumber(owner);
    assertThat(number).isEqualTo(1);
    assertThat(repository.nextPlanNumber(owner)).isEqualTo(2);
    repository.createPlan(owner, plan, version, "Goal", mapper.createObjectNode(), "Name", number);
    repository.rename(owner, plan, "Renamed", 2);
    assertThat(repository.findPlan(owner, plan, false).orElseThrow().name()).isEqualTo("Renamed");
    // A wrong revision is the server's own fault (StudyPlanService derives it from the locked
    // plan), never 422 INVALID_ACCOUNT_PAYLOAD.
    assertThatThrownBy(() -> repository.updateProgress(owner, plan, mapper.createObjectNode(), 7))
        .isInstanceOf(IllegalStateException.class);
    repository.insertActivity(owner, plan, version, 2, "rename", mapper.createObjectNode());
    assertThat(StudyPlanStoreLocalFixture.items(client, DynamoItems.plan(owner, plan), "ACTIVITY#"))
        .hasSize(1);
    assertThat(meta("revision")).isEqualTo(5);
    failure(
        "AUTHENTICATION_REQUIRED",
        () -> repository.lockMutation(UUID.randomUUID(), UUID.randomUUID()));
    assertThat(repository.findReceipt(owner, UUID.randomUUID())).isEmpty();
    assertThat(repository.findPlan(owner, UUID.randomUUID(), true)).isEmpty();
  }
}
