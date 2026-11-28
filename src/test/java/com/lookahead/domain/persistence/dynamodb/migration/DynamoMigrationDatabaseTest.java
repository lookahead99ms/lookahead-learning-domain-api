package com.lookahead.domain.persistence.dynamodb.migration;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoReadiness;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.membership.MembershipItems;
import com.lookahead.domain.health.DynamoReadinessIndicator;
import com.lookahead.domain.log.LogCapture;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Real DynamoDB Local: ledger, lock and runner behaviour. Local raw writes play the roles of
 * crashes and damage.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoMigrationDatabaseTest {
  private static DynamoDbClient client;
  private static final DynamoTableNames TABLES = DynamoTableNames.local();
  private static final Instant NOW = Instant.parse("2026-11-10T06:00:00Z");
  private final Set<String> created = new HashSet<>();
  private final MutableClock clock = new MutableClock(NOW);

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

  /** A catalog step that counts and can be told to fail or to pretend verification fails. */
  static class CountingStep implements DynamoMigrationStep {
    final int version;
    final String name;
    final AtomicInteger applied = new AtomicInteger(), verified = new AtomicInteger();
    volatile boolean failApply, failVerify;

    CountingStep(int version, String name) {
      this.version = version;
      this.name = name;
    }

    @Override
    public int version() {
      return version;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String definition() {
      return "test step " + name;
    }

    @Override
    public void apply(DynamoDbClient client, DynamoTableNames tables) {
      applied.incrementAndGet();
      if (failApply) throw new IllegalStateException("apply failed");
    }

    @Override
    public void verify(DynamoDbClient client, DynamoTableNames tables) {
      verified.incrementAndGet();
      if (failVerify) throw new IllegalStateException("verify failed");
    }
  }

  @BeforeAll
  static void connect() {
    URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
    if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()))
      throw new IllegalArgumentException("Loopback fixture required");
    client =
        DynamoDbClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("synthetic", "synthetic")))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
  }

  /**
   * Earlier test classes may leave the dev-named tables behind; make sure they exist, then clear
   * what these tests touch.
   */
  @BeforeEach
  void prepareTables() {
    for (String name : TABLES.all()) createTable(name);
    purge(TABLES.protectedOwnerIdentityTableName(), "DATA_VERSION#DOMAIN");
    purge(TABLES.userAccountDetailsTableName(), "STAFF_ROLE#");
    purge(TABLES.administratorAuditEventsTableName(), "STAFF_ROLE#");
    purge(TABLES.administratorAuditEventsTableName(), "STAFF_ALIAS#");
    purge(TABLES.userAccountDetailsTableName(), "CATALOG");
    purge(TABLES.userAccountDetailsTableName(), "SEQUENCE#");
    purge(TABLES.userAccountDetailsTableName(), "IMPORT#");
  }

  private static void purge(String table, String prefix) {
    var scan =
        client.scan(
            ScanRequest.builder()
                .tableName(table)
                .consistentRead(true)
                .filterExpression("begins_with(pk, :p)")
                .expressionAttributeValues(Map.of(":p", s(prefix)))
                .build());
    for (var item : scan.items())
      client.deleteItem(
          DeleteItemRequest.builder()
              .tableName(table)
              .key(Map.of("pk", item.get("pk"), "sk", item.get("sk")))
              .build());
  }

  private void createTable(String name) {
    try {
      client.describeTable(DescribeTableRequest.builder().tableName(name).build());
      return;
    } catch (ResourceNotFoundException absent) {
      /* create it */
    }
    client.createTable(
        CreateTableRequest.builder()
            .tableName(name)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .keySchema(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
            .attributeDefinitions(
                AttributeDefinition.builder()
                    .attributeName("pk")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("sk")
                    .attributeType(ScalarAttributeType.S)
                    .build())
            .build());
    created.add(name);
  }

  @AfterEach
  void cleanup() {
    for (String name : TABLES.all()) createTable(name);
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
  }

  private DynamoMigrationRunner runner(DynamoMigrationCatalog catalog) {
    return new DynamoMigrationRunner(client, TABLES, catalog, clock);
  }

  private static Map<String, AttributeValue> item(String table, String sk) {
    return client
        .getItem(
            GetItemRequest.builder()
                .tableName(table)
                .consistentRead(true)
                .key(Map.of("pk", s(DynamoMigrationLedger.PK), "sk", s(sk)))
                .build())
        .item();
  }

  /**
   * The owner binding ({@code OWNER / ROOT}); other test classes seed it in the shared local store
   * and never remove it.
   */
  private static Map<String, AttributeValue> owner() {
    return client
        .getItem(
            GetItemRequest.builder()
                .tableName(TABLES.protectedOwnerIdentityTableName())
                .consistentRead(true)
                .key(Map.of("pk", s("OWNER"), "sk", s("ROOT")))
                .build())
        .item();
  }

  private static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private static AttributeValue n(String value) {
    return AttributeValue.builder().n(value).build();
  }

  @Test
  void theRealCatalogMigratesAnEmptyStoreThenReplaysAndVerifiesAndReadinessFollowsTheWatermark() {
    var ownerBefore = owner();
    var readiness =
        new DynamoReadinessIndicator(
            new DynamoReadiness(client, TABLES, DomainDynamoCatalog.catalog().requirement()),
            clock);
    assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
    var runner = runner(DomainDynamoCatalog.catalog());
    try (var logs = LogCapture.start("lookahead.migration")) {
      var outcome = runner.migrate("github-1");
      assertThat(outcome.before()).isZero();
      assertThat(outcome.after()).isEqualTo(5);
      assertThat(outcome.applied())
          .containsExactly(
              "1 base-tables",
              "2 staff-alias-counters",
              "3 membership-catalog",
              "4 review-record-counters",
              "5 account-access-import");
      assertThat(outcome.ahead()).isFalse();
      assertThat(logs.messages())
          .contains(
              "Applying data step 1 base-tables",
              "Recorded data step 1 base-tables; CURRENT is now 1",
              "Recorded data step 2 staff-alias-counters; CURRENT is now 2",
              "Recorded data step 4 review-record-counters; CURRENT is now 4",
              "Recorded data step 5 account-access-import; CURRENT is now 5",
              "Migration complete: the data is at 5; this build knows 5 step(s)");
    }
    assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    var ledger = new DynamoMigrationLedger(client, TABLES);
    assertThat(ledger.current()).isEqualTo(5);
    var history = ledger.history();
    assertThat(history)
        .extracting(DynamoMigrationLedger.AppliedStep::version)
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(history)
        .extracting(DynamoMigrationLedger.AppliedStep::runId)
        .containsOnly("github-1");
    assertThat(history.get(1).checksum()).isEqualTo(DomainDynamoCatalog.catalog().checksum(2));
    assertThat(history.get(1).appliedAt()).isEqualTo(NOW);
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "CURRENT").get("version"))
        .isEqualTo(n("5"));
    // Steps 3 and 4 seeded the catalog (ten plans and META at revision 1) and the two record
    // counters at 0.
    var plans =
        MembershipItems.query(client, TABLES.userAccountDetailsTableName(), "CATALOG", "PLAN#");
    assertThat(plans).hasSize(10);
    assertThat(plans)
        .allSatisfy(plan -> {
          assertThat(plan.get("schemaVersion").n()).isEqualTo("1");
          assertThat(plan.get("revision").n()).isEqualTo("1");
        });
    var learn =
        MembershipItems.get(client, TABLES.userAccountDetailsTableName(), "CATALOG", "PLAN#learn");
    assertThat(learn.get("priceCents").n()).isEqualTo("6999");
    assertThat(learn.get("definition").m().get("scope").m().get("catalogs").l())
        .extracting(AttributeValue::s)
        .containsExactly("learn");
    assertThat(
            MembershipItems.get(client, TABLES.userAccountDetailsTableName(), "CATALOG", "META")
                .get("revision")
                .n())
        .isEqualTo("1");
    for (String partition : List.of("SEQUENCE#review-record", "SEQUENCE#support-request")) {
      var counter =
          client
              .getItem(
                  GetItemRequest.builder()
                      .tableName(TABLES.userAccountDetailsTableName())
                      .consistentRead(true)
                      .key(Map.of("pk", s(partition), "sk", s("COUNTER")))
                      .build())
              .item();
      assertThat(counter.get("schemaVersion").n()).isEqualTo("1");
      assertThat(counter.get("lastAssigned").n()).isEqualTo("0");
    }
    // The lock was released: expiry 0, fence kept.
    var lock = item(TABLES.protectedOwnerIdentityTableName(), "LOCK");
    assertThat(lock.get("expiresAt")).isEqualTo(n("0"));
    assertThat(lock.get("fence")).isEqualTo(n("1"));
    // Counters exist exactly once; a second migrate changes nothing and verifies the same two
    // steps.
    assertThat(item(TABLES.userAccountDetailsTableName(), "x")).isEmpty();
    assertThat(
            client
                .getItem(
                    GetItemRequest.builder()
                        .tableName(TABLES.userAccountDetailsTableName())
                        .consistentRead(true)
                        .key(Map.of("pk", s("STAFF_ROLE#learner"), "sk", s("COUNTER")))
                        .build())
                .item())
        .isNotEmpty();
    var again = runner.migrate("github-2");
    assertThat(again.applied()).isEmpty();
    assertThat(again.before()).isEqualTo(5);
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "LOCK").get("fence"))
        .isEqualTo(n("2"));
    var verified = runner.verify();
    assertThat(verified.verified())
        .containsExactly(
            "1 base-tables",
            "2 staff-alias-counters",
            "3 membership-catalog",
            "4 review-record-counters",
            "5 account-access-import");
    assertThat(ledger.history()).hasSize(5);
    // The owner binding is never touched by migration: whatever an earlier class seeded (or
    // nothing) is still there, unchanged.
    assertThat(owner()).isEqualTo(ownerBefore);
  }

  @Test
  void theCatalogAndCounterStepsReplayAcceptExistingItemsAndRefuseADifferentCatalog() {
    String table = TABLES.userAccountDetailsTableName();
    var catalog = new DomainDynamoCatalog.MembershipCatalog();
    var counters = new DomainDynamoCatalog.ReviewRecordCounters();
    // Verify before apply fails closed on every missing item.
    assertThatThrownBy(() -> catalog.verify(client, TABLES))
        .hasMessage("Membership catalog plan free is missing");
    assertThatThrownBy(() -> counters.verify(client, TABLES))
        .hasMessage("Counter SEQUENCE#review-record is missing");
    // A price changed by the owner and a counter already in use survive a replay untouched.
    catalog.apply(client, TABLES);
    counters.apply(client, TABLES);
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                DynamoItems.item(
                    "SEQUENCE#review-record", "COUNTER", 1, Map.of("lastAssigned", n("41"))))
            .build());
    var learn = new HashMap<>(MembershipItems.get(client, table, "CATALOG", "PLAN#learn"));
    learn.put("priceCents", n("7999"));
    learn.put("revision", n("2"));
    client.putItem(PutItemRequest.builder().tableName(table).item(learn).build());
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(table)
            .key(Map.of("pk", s("CATALOG"), "sk", s("PLAN#grow")))
            .build());
    catalog.apply(client, TABLES);
    counters.apply(client, TABLES);
    catalog.verify(client, TABLES);
    counters.verify(client, TABLES);
    assertThat(MembershipItems.get(client, table, "CATALOG", "PLAN#learn").get("priceCents").n())
        .isEqualTo("7999");
    assertThat(MembershipItems.get(client, table, "CATALOG", "PLAN#grow").get("revision").n())
        .isEqualTo("1");
    assertThat(
            client
                .getItem(
                    GetItemRequest.builder()
                        .tableName(table)
                        .consistentRead(true)
                        .key(Map.of("pk", s("SEQUENCE#review-record"), "sk", s("COUNTER")))
                        .build())
                .item()
                .get("lastAssigned")
                .n())
        .isEqualTo("41");
    // A plan outside the seed is a different catalog: apply refuses it and changes nothing.
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                Map.of(
                    "pk",
                    s("CATALOG"),
                    "sk",
                    s("PLAN#unknown-plan"),
                    "schemaVersion",
                    n("1"),
                    "revision",
                    n("1")))
            .build());
    assertThatThrownBy(() -> catalog.apply(client, TABLES))
        .hasMessage("The store holds a different membership catalog (1 unknown plan id(s))");
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(table)
            .key(Map.of("pk", s("CATALOG"), "sk", s("PLAN#unknown-plan")))
            .build());
    // A damaged item fails verification without being repaired.
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(table)
            .key(Map.of("pk", s("CATALOG"), "sk", s("META")))
            .build());
    assertThatThrownBy(() -> catalog.verify(client, TABLES))
        .hasMessage("Membership catalog META is missing");
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                Map.of(
                    "pk",
                    s("SEQUENCE#support-request"),
                    "sk",
                    s("COUNTER"),
                    "schemaVersion",
                    n("1"),
                    "lastAssigned",
                    n("-1")))
            .build());
    assertThatThrownBy(() -> counters.verify(client, TABLES))
        .hasMessage("Counter SEQUENCE#support-request is negative");
  }

  @Test
  void aMissingTableFailsStepOneBeforeAnythingIsRecorded() {
    client.deleteTable(
        DeleteTableRequest.builder().tableName(TABLES.userStudyPlanDetailsTableName()).build());
    created.remove(TABLES.userStudyPlanDetailsTableName());
    var runner = runner(DomainDynamoCatalog.catalog());
    try (var logs = LogCapture.start("lookahead.migration")) {
      assertThatThrownBy(() -> runner.migrate("github-3"))
          .isInstanceOf(ResourceNotFoundException.class);
      assertThat(logs.messages())
          .anyMatch(message ->
              message.startsWith(
                  "Data step 1 base-tables failed before it was recorded; CURRENT stays at 0"));
    }
    assertThat(new DynamoMigrationLedger(client, TABLES).history()).isEmpty();
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "CURRENT")).isEmpty();
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "LOCK").get("expiresAt"))
        .isEqualTo(n("0"));
  }

  @Test
  void aFailedApplyOrVerifyRecordsNothingAndARerunFinishesTheSameStep() {
    var one = new CountingStep(1, "first");
    var two = new CountingStep(2, "second");
    var three = new CountingStep(3, "third");
    var catalog = new DynamoMigrationCatalog(List.of(one, two, three));
    two.failApply = true;
    assertThatThrownBy(() -> runner(catalog).migrate("run-a")).hasMessage("apply failed");
    assertThat(new DynamoMigrationLedger(client, TABLES).current()).isEqualTo(1);
    two.failApply = false;
    two.failVerify = true;
    assertThatThrownBy(() -> runner(catalog).migrate("run-b")).hasMessage("verify failed");
    assertThat(new DynamoMigrationLedger(client, TABLES).current()).isEqualTo(1);
    assertThat(three.applied.get()).isZero();
    two.failVerify = false;
    var outcome = runner(catalog).migrate("run-c");
    assertThat(outcome.before()).isEqualTo(1);
    assertThat(outcome.after()).isEqualTo(3);
    assertThat(outcome.applied()).containsExactly("2 second", "3 third");
    assertThat(one.applied.get()).isEqualTo(1);
    assertThat(two.applied.get()).isEqualTo(3);
    assertThat(new DynamoMigrationLedger(client, TABLES).history())
        .extracting(DynamoMigrationLedger.AppliedStep::runId)
        .containsExactly("run-a", "run-c", "run-c");
  }

  @Test
  void aChangedDefinitionOrNameUnderAnAppliedNumberIsRefusedByMigrateAndVerify() {
    var catalog =
        new DynamoMigrationCatalog(
            List.of(new CountingStep(1, "first"), new CountingStep(2, "second")));
    runner(catalog).migrate("run-a");
    var edited =
        new DynamoMigrationCatalog(
            List.of(
                new CountingStep(1, "first") {
                  @Override
                  public String definition() {
                    return "edited";
                  }
                },
                new CountingStep(2, "second")));
    assertThatThrownBy(() -> runner(edited).verify())
        .isInstanceOf(DynamoMigrationLedger.Conflict.class)
        .hasMessage("Applied step 1 has a different definition from this build's catalog");
    assertThatThrownBy(() -> runner(edited).migrate("run-b"))
        .isInstanceOf(DynamoMigrationLedger.Conflict.class);
    var renamed =
        new DynamoMigrationCatalog(
            List.of(new CountingStep(1, "first"), new CountingStep(2, "renamed")));
    assertThatThrownBy(() -> runner(renamed).verify())
        .hasMessage("Applied step 2 is named differently from this build's catalog");
    // The store is untouched by refused runs.
    assertThat(new DynamoMigrationLedger(client, TABLES).current()).isEqualTo(2);
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "LOCK").get("expiresAt"))
        .isEqualTo(n("0"));
  }

  @Test
  void dataAheadOfTheBuildIsReadyAndVerifiedOnlyForKnownSteps() {
    var newer =
        new DynamoMigrationCatalog(
            List.of(
                new CountingStep(1, "first"),
                new CountingStep(2, "second"),
                new CountingStep(3, "third")));
    runner(newer).migrate("run-new");
    var older =
        new DynamoMigrationCatalog(
            List.of(new CountingStep(1, "first"), new CountingStep(2, "second")));
    var verified = runner(older).verify();
    assertThat(verified.ahead()).isTrue();
    assertThat(verified.verified()).containsExactly("1 first", "2 second");
    var migrated = runner(older).migrate("run-old");
    assertThat(migrated.applied()).isEmpty();
    assertThat(migrated.ahead()).isTrue();
    assertThat(
            new DynamoReadinessIndicator(
                    new DynamoReadiness(client, TABLES, older.requirement()), clock)
                .health()
                .getStatus())
        .isEqualTo(Status.UP);
  }

  @Test
  void damagedHistoryFailsClosedWithoutQuotingTheRecords() {
    var catalog =
        new DynamoMigrationCatalog(
            List.of(new CountingStep(1, "first"), new CountingStep(2, "second")));
    runner(catalog).migrate("run-a");
    String table = TABLES.protectedOwnerIdentityTableName();
    // CURRENT says 2 but step 2 is gone.
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(table)
            .key(Map.of("pk", s(DynamoMigrationLedger.PK), "sk", s("STEP#0000000002")))
            .build());
    assertThatThrownBy(() -> runner(catalog).verify())
        .hasMessage("Migration history has 1 step(s) but CURRENT is 2");
    // A step record with a wrong format is malformed, not version 0.
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                Map.of(
                    "pk",
                    s(DynamoMigrationLedger.PK),
                    "sk",
                    s("STEP#0000000002"),
                    "ledgerFormatVersion",
                    n("7"),
                    "version",
                    n("2"),
                    "name",
                    s("secret-name"),
                    "checksum",
                    s("x"),
                    "appliedAt",
                    s("bad"),
                    "runId",
                    s("r")))
            .build());
    assertThatThrownBy(() -> runner(catalog).verify())
        .hasMessage("Malformed migration history record");
    // A gap: steps 1 and 3 under CURRENT 2.
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(table)
            .key(Map.of("pk", s(DynamoMigrationLedger.PK), "sk", s("STEP#0000000002")))
            .build());
    client.putItem(
        PutItemRequest.builder()
            .tableName(table)
            .item(
                Map.of(
                    "pk",
                    s(DynamoMigrationLedger.PK),
                    "sk",
                    s("STEP#0000000003"),
                    "ledgerFormatVersion",
                    n("1"),
                    "version",
                    n("3"),
                    "name",
                    s("third"),
                    "checksum",
                    s("a".repeat(64)),
                    "appliedAt",
                    s(NOW.toString()),
                    "runId",
                    s("r")))
            .build());
    assertThatThrownBy(() -> runner(catalog).verify())
        .hasMessage("Migration history is missing step 2");
    // Nothing was repaired or recorded by these refusals.
    assertThat(item(table, "CURRENT").get("version")).isEqualTo(n("2"));
  }

  @Test
  void theLockIsExclusiveFencedAndTakenOverOnlyAfterExpiry() {
    var lock = new DynamoMigrationLock(client, TABLES);
    var first = lock.acquire("run-a", NOW, Duration.ofMinutes(15));
    assertThat(first.fence()).isEqualTo(1);
    assertThatThrownBy(
            () -> lock.acquire("run-b", NOW.plus(Duration.ofMinutes(14)), Duration.ofMinutes(15)))
        .isInstanceOf(DynamoMigrationLock.Locked.class);
    // The same run may re-acquire (restart with the same ID); the fence advances.
    assertThat(lock.acquire("run-a", NOW, Duration.ofMinutes(15)).fence()).isEqualTo(2);
    var renewed =
        lock.renew(
            new DynamoMigrationLock.Lease("run-a", 2, NOW),
            NOW.plus(Duration.ofMinutes(10)),
            Duration.ofMinutes(15));
    assertThat(renewed.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(25)));
    assertThatThrownBy(() -> lock.renew(first, NOW, Duration.ofMinutes(1)))
        .isInstanceOf(DynamoMigrationLock.Locked.class);
    // Expired: another run takes over with a higher fence; the old lease can no longer record.
    var taken = lock.acquire("run-b", NOW.plus(Duration.ofMinutes(26)), Duration.ofMinutes(15));
    assertThat(taken.fence()).isEqualTo(3);
    var ledger = new DynamoMigrationLedger(client, TABLES);
    var step = new DynamoMigrationLedger.AppliedStep(1, "first", "a".repeat(64), NOW, "run-a");
    assertThatThrownBy(() -> ledger.record(step, renewed))
        .isInstanceOf(DynamoMigrationLedger.Conflict.class);
    assertThat(ledger.history()).isEmpty();
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "CURRENT")).isEmpty();
    ledger.record(
        new DynamoMigrationLedger.AppliedStep(1, "first", "a".repeat(64), NOW, "run-b"), taken);
    assertThat(ledger.current()).isEqualTo(1);
    // Releasing a taken-over lease changes nothing; releasing the owner's lease ends it.
    lock.release(renewed);
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "LOCK").get("runId"))
        .isEqualTo(s("run-b"));
    assertThat(
            DynamoMigrationLock.number(
                item(TABLES.protectedOwnerIdentityTableName(), "LOCK").get("expiresAt")))
        .isPositive();
    lock.release(taken);
    assertThat(item(TABLES.protectedOwnerIdentityTableName(), "LOCK").get("expiresAt"))
        .isEqualTo(n("0"));
    assertThatThrownBy(() -> lock.acquire("bad id!", NOW, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> lock.acquire("run-c", NOW, Duration.ofHours(7)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aLostRecordReplyIsReconciledAndADifferentRecordingIsAConflict() {
    var lock = new DynamoMigrationLock(client, TABLES);
    var lease = lock.acquire("run-a", NOW, Duration.ofMinutes(15));
    var ledger = new DynamoMigrationLedger(client, TABLES);
    var step = new DynamoMigrationLedger.AppliedStep(1, "first", "a".repeat(64), NOW, "run-a");
    ledger.record(step, lease);
    // The same recording again (lost reply) succeeds through the durable record.
    ledger.record(step, lease);
    assertThat(ledger.history()).hasSize(1);
    // Another run cannot re-record step 1, and step 3 cannot be recorded over CURRENT 1.
    var other = lock.acquire("run-a", NOW, Duration.ofMinutes(15));
    assertThatThrownBy(() ->
            ledger.record(
                new DynamoMigrationLedger.AppliedStep(1, "first", "a".repeat(64), NOW, "run-z"),
                other))
        .isInstanceOf(DynamoMigrationLedger.Conflict.class);
    assertThatThrownBy(() ->
            ledger.record(
                new DynamoMigrationLedger.AppliedStep(3, "third", "c".repeat(64), NOW, "run-a"),
                other))
        .isInstanceOf(DynamoMigrationLedger.Conflict.class);
    assertThat(ledger.current()).isEqualTo(1);
    assertThat(ledger.step(1)).isPresent();
    assertThat(ledger.step(3)).isEmpty();
    assertThatThrownBy(
            () -> new DynamoMigrationLedger.AppliedStep(0, "x", "a".repeat(64), NOW, "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DynamoMigrationLedger.AppliedStep(1, "x", "short", NOW, "r"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theEntrypointRunsAgainstTheLocalFixtureAndReportsJson() {
    var out = new ByteArrayOutputStream();
    var variables =
        Map.of(
            "LOOKAHEAD_ENVIRONMENT",
            "local",
            "LOOKAHEAD_DYNAMODB_ENDPOINT",
            System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
    assertThat(
            DomainDynamoMigration.run(
                new String[] {"status"}, variables, new PrintStream(out), clock))
        .isZero();
    assertThat(out.toString())
        .contains("\"command\":\"status\"")
        .contains("\"before\":0")
        .contains("\"required\":5");
    out.reset();
    assertThat(
            DomainDynamoMigration.run(
                new String[] {"migrate"}, variables, new PrintStream(out), clock))
        .isZero();
    assertThat(out.toString())
        .contains("\"after\":5")
        .contains(
            "\"applied\":[\"1 base-tables\",\"2 staff-alias-counters\",\"3 membership-catalog\",\"4"
                + " review-record-counters\",\"5 account-access-import\"]")
        .contains("\"runId\":\"local-");
    out.reset();
    assertThat(
            DomainDynamoMigration.run(
                new String[] {"verify"},
                Map.of(
                    "LOOKAHEAD_ENVIRONMENT",
                    "local",
                    "LOOKAHEAD_DYNAMODB_ENDPOINT",
                    variables.get("LOOKAHEAD_DYNAMODB_ENDPOINT"),
                    "GITHUB_RUN_ID",
                    "41"),
                new PrintStream(out),
                clock))
        .isZero();
    assertThat(out.toString())
        .contains(
            "\"verified\":[\"1 base-tables\",\"2 staff-alias-counters\",\"3"
                + " membership-catalog\",\"4 review-record-counters\",\"5 account-access-import\"]")
        .contains("\"runId\":\"github-41\"");
    out.reset();
    assertThat(
            DomainDynamoMigration.run(
                new String[] {"drop"}, variables, new PrintStream(out), clock))
        .isEqualTo(2);
    assertThat(DomainDynamoMigration.run(new String[] {}, variables, new PrintStream(out), clock))
        .isEqualTo(2);
    // Held lock: exit 1 with a LOCKED reason and no endpoint in the output.
    new DynamoMigrationLock(client, TABLES)
        .acquire("other", clock.instant(), Duration.ofMinutes(15));
    out.reset();
    try (var logs = LogCapture.start("lookahead.migration")) {
      assertThat(
              DomainDynamoMigration.run(
                  new String[] {"migrate"}, variables, new PrintStream(out), clock))
          .isEqualTo(1);
      assertThat(logs.messages())
          .anyMatch(message ->
              message.equals("Migration migrate failed: Another migration run holds the lock"));
    }
    assertThat(out.toString()).contains("\"failed\":true").doesNotContain("127.0.0.1");
    // Bad configuration is also exit 1, never a stack trace to stdout.
    out.reset();
    assertThat(
            DomainDynamoMigration.run(
                new String[] {"verify"},
                Map.of("LOOKAHEAD_ENVIRONMENT", "local"),
                new PrintStream(out),
                clock))
        .isEqualTo(1);
    assertThat(out.toString()).contains("\"error\":\"IllegalStateException\"");
  }
}
