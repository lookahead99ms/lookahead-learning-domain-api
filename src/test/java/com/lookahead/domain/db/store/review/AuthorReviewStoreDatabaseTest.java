package com.lookahead.domain.db.store.review;

import static com.lookahead.domain.model.AuthorReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.content.AuthorPreviewAccessService;
import com.lookahead.domain.service.review.AuthorReviewArtifactService;
import com.lookahead.domain.service.review.AuthorReviewService;
import com.lookahead.domain.validator.AuthorReviewRequestValidator;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockMakers;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970: the author review contract that the retired PostgreSQL test checked, on DynamoDB Local.
 * Every actor is a fresh ACCOUNT#id / META item.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class AuthorReviewStoreDatabaseTest {
  private static DynamoDbClient client;
  private static final DynamoTableNames TABLES = DynamoTableNames.local();
  private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
  private final MutableClock clock = new MutableClock(NOW);
  private final Artifact artifact =
      new Artifact("study-plan-review", "dlv-704/review-2026-09-19.1", "a".repeat(64), "DLV-704");

  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant at) {
      now = at;
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

  @BeforeEach
  void prepareTables() {
    for (String name : TABLES.all()) AuthorReviewStoreDatabaseTestSupport.createTable(client, name);
    AuthorReviewStoreDatabaseTestSupport.purge(
        client, TABLES.administratorAuditEventsTableName(), "REVIEW#");
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
  }

  AuthorReviewStore store() {
    return new AuthorReviewStore(client, TABLES, clock);
  }

  /**
   * A new actor: its META item (lane A's account record, schema 1) is what the submit transaction
   * checks.
   */
  UUID actor() {
    UUID id = UUID.randomUUID();
    client.putItem(
        PutItemRequest.builder()
            .tableName(TABLES.userAccountDetailsTableName())
            .item(
                DynamoItems.item(
                    DynamoItems.account(id), "META", 1, Map.of("revision", DynamoItems.n(0))))
            .build());
    return id;
  }

  Submission submission(Decision decision, String comment, UUID prior) {
    return new Submission(
        artifact.artifactVersion(),
        artifact.contentHash(),
        artifact.ticketId(),
        decision,
        comment,
        prior);
  }

  Receipt submit(UUID actor, UUID key, Submission submission) {
    return store().submit(actor, "study-plan-review", key, hash(submission), artifact, submission);
  }

  static String hash(Submission submission) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(submission.toString().getBytes()));
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException(missing);
    }
  }

  long audits(UUID actor) {
    return client
        .query(
            QueryRequest.builder()
                .tableName(TABLES.administratorAuditEventsTableName())
                .consistentRead(true)
                .keyConditionExpression("pk=:pk")
                .expressionAttributeValues(
                    Map.of(
                        ":pk",
                        DynamoItems.s(AuthorReviewStore.eventsPk(actor, "study-plan-review"))))
                .build())
        .count();
  }

  @Test
  void durableReceiptSurvivesStoreReconstructionAndSameRetryDoesNotAppend() {
    var actor = actor();
    var key = UUID.randomUUID();
    var request = submission(Decision.APPROVE, "Reviewed", null);
    var first = submit(actor, key, request);
    clock.set(NOW.plusSeconds(60));
    var second = submit(actor, key, request);
    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).isTrue();
    assertThat(second.event()).isEqualTo(first.event());
    assertThat(first.event().actorId()).isEqualTo(actor);
    assertThat(first.event().recordedAt()).isEqualTo(NOW);
    assertThat(second.reconciliationStatus()).isEqualTo("PENDING_MAIN_RECONCILIATION");
    assertThat(store().latest(actor, "study-plan-review", 50)).containsExactly(first.event());
    assertThat(audits(actor)).as("one event and its lookup").isEqualTo(2);
    var head =
        client
            .getItem(
                GetItemRequest.builder()
                    .tableName(TABLES.userAccountDetailsTableName())
                    .consistentRead(true)
                    .key(
                        DynamoItems.key(
                            DynamoItems.account(actor),
                            AuthorReviewStore.headSk("study-plan-review")))
                    .build())
            .item();
    assertThat(DynamoItems.number(head, "revision")).isZero();
    assertThat(DynamoItems.number(head, "sequence")).isEqualTo(1);
    assertThat(DynamoItems.uuid(head, "latestEventId")).isEqualTo(first.event().eventId());
  }

  @Test
  void changedPayloadWithSameKeyIsRejectedAndNothingIsAppended() {
    var actor = actor();
    var key = UUID.randomUUID();
    submit(actor, key, submission(Decision.APPROVE, "", null));
    assertThatThrownBy(
            () -> submit(actor, key, submission(Decision.DECLINE, "Change required", null)))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.IDEMPOTENCY_CONFLICT);
    assertThat(store().latest(actor, "study-plan-review", 50)).hasSize(1);
  }

  @Test
  void supersedingDecisionPreservesHistoryAndConcurrentStaleChangeFails() {
    var actor = actor();
    var first =
        submit(actor, UUID.randomUUID(), submission(Decision.DECLINE, "Explain recovery", null));
    var second =
        submit(
            actor,
            UUID.randomUUID(),
            submission(Decision.APPROVE, "Now clear", first.event().eventId()));
    assertThat(second.event().supersedesEventId()).isEqualTo(first.event().eventId());
    assertThatThrownBy(() ->
            submit(
                actor,
                UUID.randomUUID(),
                submission(Decision.NEED_MORE, "Need evidence", first.event().eventId())))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.SUPERSESSION_CONFLICT);
    var page = store().latest(actor, "study-plan-review", 1);
    assertThat(page).containsExactly(second.event());
    assertThat(store().before(actor, "study-plan-review", second.event().eventId(), 1))
        .contains(List.of(first.event()));
    assertThat(store().before(actor, "study-plan-review", first.event().eventId(), 1))
        .contains(List.of());
    var head =
        client
            .getItem(
                GetItemRequest.builder()
                    .tableName(TABLES.userAccountDetailsTableName())
                    .consistentRead(true)
                    .key(
                        DynamoItems.key(
                            DynamoItems.account(actor),
                            AuthorReviewStore.headSk("study-plan-review")))
                    .build())
            .item();
    assertThat(DynamoItems.number(head, "revision")).isEqualTo(1);
    assertThat(DynamoItems.number(head, "sequence")).isEqualTo(2);
  }

  @Test
  void crossAccountHistoryCursorAndSupersedesAreDeniedAndAnUnknownActorIs401() {
    var a = actor();
    var b = actor();
    var first = submit(a, UUID.randomUUID(), submission(Decision.APPROVE, "", null));
    assertThat(store().latest(b, "study-plan-review", 50)).isEmpty();
    assertThat(store().before(b, "study-plan-review", first.event().eventId(), 50)).isEmpty();
    assertThatThrownBy(() ->
            submit(b, UUID.randomUUID(), submission(Decision.APPROVE, "", first.event().eventId())))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.SUPERSESSION_CONFLICT);
    var stranger = UUID.randomUUID();
    assertThatThrownBy(
            () -> submit(stranger, UUID.randomUUID(), submission(Decision.APPROVE, "", null)))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.ACTOR_UNKNOWN);
    assertThat(audits(stranger)).isZero();
    assertThat(
            client
                .getItem(
                    GetItemRequest.builder()
                        .tableName(TABLES.userAccountDetailsTableName())
                        .consistentRead(true)
                        .key(
                            DynamoItems.key(
                                DynamoItems.account(stranger),
                                AuthorReviewStore.headSk("study-plan-review")))
                        .build())
                .item())
        .isEmpty();
  }

  @RepeatedTest(10)
  void concurrentDuplicateReturnsOneEventAcrossStoreInstances() throws Exception {
    var actor = actor();
    var key = UUID.randomUUID();
    var request = submission(Decision.APPROVE, "", null);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var calls = new ArrayList<Future<Receipt>>();
      for (int n = 0; n < 2; n++)
        calls.add(
            executor.submit(() -> {
              start.await();
              return submit(actor, key, request);
            }));
      start.countDown();
      var a = calls.get(0).get(20, TimeUnit.SECONDS);
      var b = calls.get(1).get(20, TimeUnit.SECONDS);
      assertThat(a.event().eventId()).isEqualTo(b.event().eventId());
      assertThat(a.replayed()).isNotEqualTo(b.replayed());
    }
    assertThat(store().latest(actor, "study-plan-review", 50)).hasSize(1);
    assertThat(audits(actor)).isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void commitBetweenReceiptAndHeadReadsPreservesIdempotency(
      boolean successor, boolean changedPayload) {
    var actor = actor();
    var key = UUID.randomUUID();
    UUID prior =
        successor
            ? submit(
                    actor,
                    UUID.randomUUID(),
                    submission(Decision.DECLINE, "Explain recovery", null))
                .event()
                .eventId()
            : null;
    var original = submission(Decision.APPROVE, "Reviewed", prior);
    var retry =
        changedPayload ? submission(Decision.NEED_MORE, "Different payload", prior) : original;
    var injectCommit = new AtomicBoolean(true);
    var winner = new AtomicReference<Receipt>();
    var racingClient =
        mock(
            DynamoDbClient.class,
            withSettings().mockMaker(MockMakers.PROXY).defaultAnswer(delegatesTo(client)));
    doAnswer(
            invocation -> {
              GetItemRequest read = invocation.getArgument(0);
              var observed = client.getItem(read);
              if (read.tableName().equals(TABLES.userAccountDetailsTableName())
                  && read.key()
                      .equals(
                          DynamoItems.key(
                              DynamoItems.account(actor), AuthorReviewStore.receiptSk(key)))
                  && injectCommit.compareAndSet(true, false)) {
                assertThat(read.consistentRead()).isTrue();
                assertThat(observed.item()).isEmpty();
                // Commit the other store's real transaction after this read observed absence,
                // but before the losing request can read the now-advanced artifact head.
                winner.set(submit(actor, key, original));
              }
              return observed;
            })
        .when(racingClient)
        .getItem(any(GetItemRequest.class));
    var losingStore = new AuthorReviewStore(racingClient, TABLES, clock);
    if (changedPayload) {
      assertThatThrownBy(() ->
              losingStore.submit(actor, artifact.artifactId(), key, hash(retry), artifact, retry))
          .isInstanceOf(AuthorReview.NotRecorded.class)
          .extracting("reason")
          .isEqualTo(AuthorReview.NotRecorded.Reason.IDEMPOTENCY_CONFLICT);
    } else {
      var replay =
          losingStore.submit(actor, artifact.artifactId(), key, hash(retry), artifact, retry);
      assertThat(replay.replayed()).isTrue();
      assertThat(replay.event()).isEqualTo(winner.get().event());
    }
    assertThat(winner.get()).isNotNull();
    assertThat(winner.get().replayed()).isFalse();
    assertThat(store().latest(actor, artifact.artifactId(), 1))
        .containsExactly(winner.get().event());
    assertThat(store().latest(actor, artifact.artifactId(), 50)).hasSize(successor ? 2 : 1);
    assertThat(audits(actor)).isEqualTo(successor ? 4 : 2);
    verify(racingClient, never()).executeTransaction(any(ExecuteTransactionRequest.class));
  }

  @Test
  void concurrentDifferentFirstDecisionsAllowOnlyOneInitialEvent() throws Exception {
    var actor = actor();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var calls = new ArrayList<Future<Boolean>>();
      for (int n = 0; n < 2; n++)
        calls.add(
            executor.submit(() -> {
              start.await();
              try {
                submit(actor, UUID.randomUUID(), submission(Decision.APPROVE, "", null));
                return true;
              } catch (AuthorReview.NotRecorded failure) {
                assertThat(failure.reason())
                    .isEqualTo(AuthorReview.NotRecorded.Reason.SUPERSESSION_CONFLICT);
                return false;
              }
            }));
      start.countDown();
      int accepted = 0;
      for (var call : calls) if (call.get(20, TimeUnit.SECONDS)) accepted++;
      assertThat(accepted).isEqualTo(1);
      assertThat(store().latest(actor, "study-plan-review", 50)).hasSize(1);
    }
  }

  @Test
  void clockRegressionDoesNotReverseHistoryOrSupersession() {
    var actor = actor();
    var first = submit(actor, UUID.randomUUID(), submission(Decision.APPROVE, "", null));
    clock.set(Instant.parse("2000-01-01T00:00:00Z"));
    var second =
        submit(
            actor,
            UUID.randomUUID(),
            submission(Decision.NEED_MORE, "Evidence", first.event().eventId()));
    var third =
        submit(
            actor,
            UUID.randomUUID(),
            submission(Decision.APPROVE, "Fixed", second.event().eventId()));
    assertThat(second.event().recordedAt()).isBefore(first.event().recordedAt());
    assertThat(third.event().recordedAt()).isEqualTo(second.event().recordedAt());
    assertThat(store().latest(actor, "study-plan-review", 1)).containsExactly(third.event());
    assertThat(store().before(actor, "study-plan-review", third.event().eventId(), 50))
        .contains(List.of(second.event(), first.event()));
    assertThat(store().latest(actor, "study-plan-review", 50))
        .containsExactly(third.event(), second.event(), first.event());
  }

  @Test
  void storedEventsCarryTheAuditConventionsAndTheStoreNeverUpdatesOrDeletesThem() {
    var actor = actor();
    var first = submit(actor, UUID.randomUUID(), submission(Decision.DECLINE, "Explain", null));
    var items =
        client
            .query(
                QueryRequest.builder()
                    .tableName(TABLES.administratorAuditEventsTableName())
                    .consistentRead(true)
                    .keyConditionExpression("pk=:pk")
                    .expressionAttributeValues(
                        Map.of(
                            ":pk",
                            DynamoItems.s(AuthorReviewStore.eventsPk(actor, "study-plan-review"))))
                    .build())
            .items();
    var event =
        items.stream()
            .filter(item -> item.get("sk").s().startsWith("EVENT#"))
            .findFirst()
            .orElseThrow();
    assertThat(DynamoItems.string(event, "actor")).isEqualTo(actor.toString());
    assertThat(DynamoItems.string(event, "action")).isEqualTo("review.decided");
    assertThat(DynamoItems.instant(event, "occurredAt")).isEqualTo(NOW);
    assertThat(event.get("sk").s()).isEqualTo("EVENT#0000000001#" + first.event().eventId());
    var lookup =
        items.stream()
            .filter(item -> item.get("sk").s().startsWith("ID#"))
            .findFirst()
            .orElseThrow();
    assertThat(DynamoItems.number(lookup, "sequence")).isEqualTo(1);
    submit(actor, UUID.randomUUID(), submission(Decision.APPROVE, "Done", first.event().eventId()));
    var again =
        client
            .query(
                QueryRequest.builder()
                    .tableName(TABLES.administratorAuditEventsTableName())
                    .consistentRead(true)
                    .keyConditionExpression("pk=:pk AND sk=:sk")
                    .expressionAttributeValues(
                        Map.of(
                            ":pk",
                            DynamoItems.s(AuthorReviewStore.eventsPk(actor, "study-plan-review")),
                            ":sk",
                            event.get("sk")))
                    .build())
            .items();
    assertThat(again).as("the first event is unchanged after its successor").containsExactly(event);
    assertThat(audits(actor)).isEqualTo(4);
    assertThat(Duration.between(NOW, clock.instant())).isZero();
  }

  @Test
  void anUnknownOrForeignHistoryCursorIsReviewEventNotFoundThroughTheService() {
    var actor = actor();
    var first = submit(actor, UUID.randomUUID(), submission(Decision.APPROVE, "", null));
    var second =
        submit(
            actor,
            UUID.randomUUID(),
            submission(Decision.NEED_MORE, "More", first.event().eventId()));
    var access =
        Mockito.mock(
            AuthorPreviewAccessService.class,
            Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    var catalog =
        Mockito.mock(
            AuthorReviewArtifactService.class,
            Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    Mockito.when(catalog.require("study-plan-review")).thenReturn(artifact);
    var service =
        new AuthorReviewService(
            access, catalog, store(), new ObjectMapper(), new AuthorReviewRequestValidator());
    var principal = new AccountPrincipal(actor, "synthetic", "Synthetic", true);
    var page = service.history(principal, "study-plan-review", 1, null);
    assertThat(page.entries()).containsExactly(second.event());
    assertThat(page.nextCursor()).isEqualTo(second.event().eventId());
    var rest = service.history(principal, "study-plan-review", 1, second.event().eventId());
    assertThat(rest.entries()).containsExactly(first.event());
    assertThat(rest.nextCursor()).isNull();
    assertThatThrownBy(() -> service.history(principal, "study-plan-review", 1, UUID.randomUUID()))
        .isInstanceOfSatisfying(
            AccountFailure.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(404);
              assertThat(failure.code()).isEqualTo("REVIEW_EVENT_NOT_FOUND");
            });
    var other = new AccountPrincipal(actor(), "other", "Other", true);
    assertThatThrownBy(
            () -> service.history(other, "study-plan-review", 1, first.event().eventId()))
        .as("another actor's event is as unknown as a random id")
        .isInstanceOfSatisfying(
            AccountFailure.class,
            failure -> assertThat(failure.code()).isEqualTo("REVIEW_EVENT_NOT_FOUND"));
    assertThat(service.history(other, "study-plan-review", 50, null).entries()).isEmpty();
  }
}
