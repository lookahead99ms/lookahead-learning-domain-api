package com.lookahead.domain.db.store.review;

import static com.lookahead.domain.db.store.dynamodb.DynamoItems.*;
import static com.lookahead.domain.model.AuthorReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * DLV-970: the author review transaction on a mocked client; AuthorReviewStoreDatabaseTest runs the
 * same contract on DynamoDB Local.
 */
class AuthorReviewStoreTest {
  static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
  static final DynamoTableNames TABLES = DynamoTableNames.local();
  final DynamoDbClient client =
      mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
  final Map<String, Map<String, AttributeValue>> stored = new HashMap<>();
  final Deque<RuntimeException> outcomes = new ArrayDeque<>();
  final List<ExecuteTransactionRequest> committed = new ArrayList<>();
  final AuthorReviewStore store =
      new AuthorReviewStore(client, TABLES, Clock.fixed(NOW, ZoneOffset.UTC));
  final UUID actor = UUID.randomUUID();
  final Artifact artifact =
      new Artifact("study-plan-review", "dlv-704/review-2026-09-19.1", "a".repeat(64), "DLV-704");

  static String at(String table, String pk, String sk) {
    return table + "|" + pk + "|" + sk;
  }

  void put(String table, String pk, String sk, int schema, Map<String, AttributeValue> attributes) {
    stored.put(at(table, pk, sk), item(pk, sk, schema, attributes));
  }

  {
    when(client.getItem(any(GetItemRequest.class)))
        .thenAnswer(invocation -> {
          GetItemRequest request = invocation.getArgument(0);
          assertThat(request.consistentRead()).isTrue();
          return GetItemResponse.builder()
              .item(
                  stored.getOrDefault(
                      at(
                          request.tableName(),
                          request.key().get("pk").s(),
                          request.key().get("sk").s()),
                      Map.of()))
              .build();
        });
    when(client.executeTransaction(any(ExecuteTransactionRequest.class)))
        .thenAnswer(invocation -> {
          committed.add(invocation.getArgument(0));
          RuntimeException outcome = outcomes.poll();
          if (outcome != null) throw outcome;
          return ExecuteTransactionResponse.builder().build();
        });
  }

  static TransactionCanceledException cancelled(String... codes) {
    return TransactionCanceledException.builder()
        .cancellationReasons(
            Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList())
        .build();
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

  List<String> statements(ExecuteTransactionRequest request) {
    return request.transactStatements().stream().map(ParameterizedStatement::statement).toList();
  }

  @Test
  void theFirstEventInsertsHeadReceiptEventAndLookupBehindTheActorCheck() {
    var key = UUID.randomUUID();
    var receipt =
        store.submit(
            actor,
            "study-plan-review",
            key,
            "h".repeat(64),
            artifact,
            submission(Decision.APPROVE, "", null));
    assertThat(receipt.replayed()).isFalse();
    var event = receipt.event();
    assertThat(event.actorId()).isEqualTo(actor);
    assertThat(event.recordedAt()).isEqualTo(NOW);
    assertThat(event.idempotencyKey()).isEqualTo(key);
    assertThat(event.supersedesEventId()).isNull();
    assertThat(event.ticketId()).isEqualTo("DLV-704");
    assertThat(committed).hasSize(1);
    var texts = statements(committed.getFirst());
    assertThat(texts).hasSize(5);
    assertThat(texts.get(0))
        .isEqualTo(
            "UPDATE \""
                + TABLES.userAccountDetailsTableName()
                + "\" SET schemaVersion=? WHERE pk=? AND sk=? AND schemaVersion=?");
    assertThat(texts.get(1))
        .startsWith("INSERT INTO \"" + TABLES.userAccountDetailsTableName() + "\" VALUE {")
        .contains("'latestEventId': ?", "'revision': ?", "'sequence': ?");
    assertThat(texts.get(2))
        .startsWith("INSERT INTO \"" + TABLES.userAccountDetailsTableName() + "\" VALUE {")
        .contains("'requestHash': ?");
    assertThat(texts.get(3))
        .startsWith("INSERT INTO \"" + TABLES.administratorAuditEventsTableName() + "\" VALUE {")
        .contains("'decision': ?", "'actor': ?", "'action': ?", "'occurredAt': ?")
        .doesNotContain("supersedesEventId", "recordedAt");
    assertThat(texts.get(4))
        .startsWith("INSERT INTO \"" + TABLES.administratorAuditEventsTableName() + "\" VALUE {")
        .contains("'sequence': ?");
    var parameters = committed.getFirst().transactStatements();
    assertThat(parameters.get(0).parameters().get(1).s()).isEqualTo("ACCOUNT#" + actor);
    assertThat(parameters.get(0).parameters().get(2).s()).isEqualTo("META");
    // Keys: the head and receipt under the actor, the event and lookup in the audit partition with
    // sequence 1.
    var eventParameters = parameters.get(3).parameters();
    var eventKeys =
        parameters
            .get(3)
            .statement()
            .replace(
                "INSERT INTO \"" + TABLES.administratorAuditEventsTableName() + "\" VALUE {", "")
            .split(", ");
    var byName = new HashMap<String, AttributeValue>();
    for (int i = 0; i < eventKeys.length; i++)
      byName.put(eventKeys[i].substring(1, eventKeys[i].indexOf("'", 1)), eventParameters.get(i));
    assertThat(byName.get("pk").s())
        .isEqualTo("REVIEW#" + actor + "#" + encode("study-plan-review"));
    assertThat(byName.get("sk").s()).isEqualTo("EVENT#0000000001#" + event.eventId());
    assertThat(byName.get("comment").s()).isEmpty();
    assertThat(byName.get("schemaVersion").n()).isEqualTo("1");
  }

  @Test
  void aLaterEventUpdatesTheHeadAtItsRevisionAndCarriesTheNextSequence() {
    UUID latest = UUID.randomUUID();
    put(
        TABLES.userAccountDetailsTableName(),
        account(actor),
        "REVIEW#" + encode("study-plan-review"),
        1,
        Map.of("revision", n(2), "sequence", n(7), "latestEventId", s(latest.toString())));
    var receipt =
        store.submit(
            actor,
            "study-plan-review",
            UUID.randomUUID(),
            "h".repeat(64),
            artifact,
            submission(Decision.NEED_MORE, "Evidence", latest));
    assertThat(receipt.event().supersedesEventId()).isEqualTo(latest);
    var statements = committed.getFirst().transactStatements();
    assertThat(statements.get(1).statement())
        .isEqualTo(
            "UPDATE \""
                + TABLES.userAccountDetailsTableName()
                + "\" SET \"latestEventId\"=? SET \"revision\"=? SET \"sequence\"=? SET"
                + " \"updatedAt\"=? WHERE pk=? AND sk=? AND revision=?");
    assertThat(statements.get(1).parameters().get(1).n()).isEqualTo("3");
    assertThat(statements.get(1).parameters().get(2).n()).isEqualTo("8");
    assertThat(statements.get(1).parameters().get(6).n()).isEqualTo("2");
    assertThat(
            statements.get(3).parameters().stream()
                .map(AttributeValue::s)
                .filter(Objects::nonNull)
                .toList())
        .contains("EVENT#0000000008#" + receipt.event().eventId(), latest.toString());
  }

  @Test
  void supersessionIsDecidedFromTheHeadBeforeAnyWrite() {
    UUID latest = UUID.randomUUID();
    put(
        TABLES.userAccountDetailsTableName(),
        account(actor),
        "REVIEW#" + encode("study-plan-review"),
        1,
        Map.of("revision", n(0), "sequence", n(1), "latestEventId", s(latest.toString())));
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                UUID.randomUUID(),
                "h".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", null)))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.SUPERSESSION_CONFLICT);
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                UUID.randomUUID(),
                "h".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", UUID.randomUUID())))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.SUPERSESSION_CONFLICT);
    stored.clear();
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                UUID.randomUUID(),
                "h".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", latest)))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.SUPERSESSION_CONFLICT);
    assertThat(committed).isEmpty();
  }

  @Test
  void theSameKeyReplaysTheStoredEventAndADifferentPayloadIsRefused() {
    var key = UUID.randomUUID();
    var eventId = UUID.randomUUID();
    put(
        TABLES.userAccountDetailsTableName(),
        account(actor),
        "REVIEWKEY#" + key,
        1,
        Map.of(
            "requestHash",
            s("h".repeat(64)),
            "artifactId",
            s("study-plan-review"),
            "eventId",
            s(eventId.toString()),
            "sequence",
            n(3)));
    var attributes =
        new HashMap<String, AttributeValue>(
            Map.of(
                "eventId",
                s(eventId.toString()),
                "artifactId",
                s("study-plan-review"),
                "artifactVersion",
                s("v"),
                "contentHash",
                s("c"),
                "ticketId",
                s("DLV-704"),
                "decision",
                s("DECLINE"),
                "comment",
                s("Fix"),
                "actorId",
                s(actor.toString()),
                "occurredAt",
                s(NOW.toString()),
                "idempotencyKey",
                s(key.toString())));
    put(
        TABLES.administratorAuditEventsTableName(),
        "REVIEW#" + actor + "#" + encode("study-plan-review"),
        "EVENT#0000000003#" + eventId,
        1,
        attributes);
    var replay =
        store.submit(
            actor,
            "study-plan-review",
            key,
            "h".repeat(64),
            artifact,
            submission(Decision.APPROVE, "", null));
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.event().eventId()).isEqualTo(eventId);
    assertThat(replay.event().decision()).isEqualTo(Decision.DECLINE);
    assertThat(replay.event().supersedesEventId()).isNull();
    assertThat(replay.reconciliationStatus()).isEqualTo("PENDING_MAIN_RECONCILIATION");
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                key,
                "x".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", null)))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.IDEMPOTENCY_CONFLICT);
    assertThat(committed).isEmpty();
    // A receipt whose event is missing is a damaged store, never a silent new event.
    stored.remove(
        at(
            TABLES.administratorAuditEventsTableName(),
            "REVIEW#" + actor + "#" + encode("study-plan-review"),
            "EVENT#0000000003#" + eventId));
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                key,
                "h".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", null)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anUnknownActorIs401AndALostRaceIsAnsweredFromWhatIsStored() {
    outcomes.add(cancelled("ConditionalCheckFailed", "None", "None", "None", "None"));
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                UUID.randomUUID(),
                "h".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", null)))
        .isInstanceOf(AuthorReview.NotRecorded.class)
        .extracting("reason")
        .isEqualTo(AuthorReview.NotRecorded.Reason.ACTOR_UNKNOWN);
    committed.clear();
    // The key receipt lost to a concurrent identical request: the re-read replays that request's
    // event.
    var key = UUID.randomUUID();
    var eventId = UUID.randomUUID();
    outcomes.add(cancelled("None", "None", "DuplicateItem", "None", "None"));
    when(client.executeTransaction(any(ExecuteTransactionRequest.class)))
        .thenAnswer(invocation -> {
          committed.add(invocation.getArgument(0));
          put(
              TABLES.userAccountDetailsTableName(),
              account(actor),
              "REVIEWKEY#" + key,
              1,
              Map.of(
                  "requestHash",
                  s("h".repeat(64)),
                  "artifactId",
                  s("study-plan-review"),
                  "eventId",
                  s(eventId.toString()),
                  "sequence",
                  n(1)));
          put(
              TABLES.administratorAuditEventsTableName(),
              "REVIEW#" + actor + "#" + encode("study-plan-review"),
              "EVENT#0000000001#" + eventId,
              1,
              Map.of(
                  "eventId",
                  s(eventId.toString()),
                  "artifactId",
                  s("study-plan-review"),
                  "artifactVersion",
                  s("v"),
                  "contentHash",
                  s("c"),
                  "ticketId",
                  s("DLV-704"),
                  "decision",
                  s("APPROVE"),
                  "comment",
                  s(""),
                  "actorId",
                  s(actor.toString()),
                  "occurredAt",
                  s(NOW.toString()),
                  "idempotencyKey",
                  s(key.toString())));
          throw outcomes.poll();
        });
    var replay =
        store.submit(
            actor,
            "study-plan-review",
            key,
            "h".repeat(64),
            artifact,
            submission(Decision.APPROVE, "", null));
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.event().eventId()).isEqualTo(eventId);
    assertThat(committed).hasSize(1);
  }

  @Test
  void aRaceThatNeverSettlesSurfacesTheConflictAfterThreeAttempts() {
    for (int i = 0; i < 3; i++)
      outcomes.add(cancelled("None", "ConditionalCheckFailed", "None", "None", "None"));
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                UUID.randomUUID(),
                "h".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", null)))
        .isInstanceOf(DynamoWrites.Conflict.class);
    assertThat(committed).hasSize(3);
    // Any other failure is the store's Unavailable, untouched.
    outcomes.add(SdkClientException.builder().message("socket").build());
    assertThatThrownBy(() ->
            store.submit(
                actor,
                "study-plan-review",
                UUID.randomUUID(),
                "h".repeat(64),
                artifact,
                submission(Decision.APPROVE, "", null)))
        .isInstanceOf(DynamoWrites.Unavailable.class);
  }

  @Test
  void historyIsOneStrongQueryNewestFirstAndTheCursorIsResolvedThroughTheLookupItem() {
    var captor = ArgumentCaptor.forClass(QueryRequest.class);
    var eventId = UUID.randomUUID();
    var attributes =
        new HashMap<String, AttributeValue>(
            Map.of(
                "eventId",
                s(eventId.toString()),
                "artifactId",
                s("study-plan-review"),
                "artifactVersion",
                s("v"),
                "contentHash",
                s("c"),
                "ticketId",
                s("DLV-704"),
                "decision",
                s("APPROVE"),
                "comment",
                s(""),
                "actorId",
                s(actor.toString()),
                "occurredAt",
                s(NOW.toString()),
                "idempotencyKey",
                s(UUID.randomUUID().toString())));
    attributes.put("supersedesEventId", s(UUID.randomUUID().toString()));
    var row =
        item(
            "REVIEW#" + actor + "#" + encode("study-plan-review"),
            "EVENT#0000000002#" + eventId,
            1,
            attributes);
    when(client.query(any(QueryRequest.class)))
        .thenReturn(QueryResponse.builder().items(List.of(row)).build());
    var latest = store.latest(actor, "study-plan-review", 5);
    assertThat(latest).hasSize(1);
    assertThat(latest.getFirst().eventId()).isEqualTo(eventId);
    assertThat(latest.getFirst().supersedesEventId()).isNotNull();
    verify(client).query(captor.capture());
    var request = captor.getValue();
    assertThat(request.tableName()).isEqualTo(TABLES.administratorAuditEventsTableName());
    assertThat(request.consistentRead()).isTrue();
    assertThat(request.scanIndexForward()).isFalse();
    assertThat(request.limit()).isEqualTo(5);
    assertThat(request.keyConditionExpression()).isEqualTo("pk=:pk AND begins_with(sk, :from)");
    assertThat(request.expressionAttributeValues().get(":pk").s())
        .isEqualTo("REVIEW#" + actor + "#" + encode("study-plan-review"));
    assertThat(store.before(actor, "study-plan-review", eventId, 5))
        .as("no lookup item: not this actor's event")
        .isEmpty();
    put(
        TABLES.administratorAuditEventsTableName(),
        "REVIEW#" + actor + "#" + encode("study-plan-review"),
        "ID#" + eventId,
        1,
        Map.of("sequence", n(2), "eventId", s(eventId.toString())));
    assertThat(store.before(actor, "study-plan-review", eventId, 5)).contains(latest);
    verify(client, times(2)).query(captor.capture());
    assertThat(captor.getValue().keyConditionExpression())
        .isEqualTo("pk=:pk AND sk BETWEEN :from AND :to");
    assertThat(captor.getValue().expressionAttributeValues().get(":to").s())
        .isEqualTo("EVENT#0000000002");
    assertThat(captor.getValue().expressionAttributeValues().get(":from").s()).isEqualTo("EVENT#");
    assertThatThrownBy(() -> store.latest(actor, "study-plan-review", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storedItemsFailClosed() {
    var damaged =
        new HashMap<>(item("p", "s", 2, Map.of("eventId", s(UUID.randomUUID().toString()))));
    assertThatThrownBy(() -> AuthorReviewStore.read(damaged))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("schema");
    var incomplete = item("p", "s", 1, Map.of("eventId", s(UUID.randomUUID().toString())));
    assertThatThrownBy(() -> AuthorReviewStore.read(incomplete))
        .isInstanceOf(IllegalStateException.class);
    // Cursor lookups and receipts with a foreign schema are refused the same way.
    UUID cursor = UUID.randomUUID();
    put(
        TABLES.administratorAuditEventsTableName(),
        "REVIEW#" + actor + "#" + encode("study-plan-review"),
        "ID#" + cursor,
        9,
        Map.of("sequence", n(2)));
    assertThatThrownBy(() -> store.before(actor, "study-plan-review", cursor, 5))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("schema");
    assertThatThrownBy(() -> new AuthorReviewStore(null, TABLES))
        .isInstanceOf(NullPointerException.class);
    assertThat(
            DynamoItems.decode(
                AuthorReviewStore.headSk("study-plan-review").substring("REVIEW#".length())))
        .isEqualTo("study-plan-review");
  }
}
