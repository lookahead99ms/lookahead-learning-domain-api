package com.lookahead.domain.db.store.access;

import static com.lookahead.domain.db.store.dynamodb.DynamoItems.n;
import static com.lookahead.domain.db.store.dynamodb.DynamoItems.s;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Permanent account+role numbers, independent of the caller's transaction: one PartiQL transaction
 * advances the role's {@code STAFF_ROLE#role / COUNTER} under its exact value and revision and
 * inserts the two immutable audit claims. {@code db.store.access.StaffAliasStore} is the Spring
 * adapter over it; this class is not a role grant or identity projection. A counter race is retried
 * with a re-read; a lost condition that remains is {@link DynamoWrites.Conflict}, any other SDK
 * failure {@link DynamoWrites.Unavailable}. The counters are created by migration step 2, never
 * here.
 */
public final class StaffAliasNumberStore {
  private static final List<String> ROLES =
      List.of("co-author", "admin", "reviewer", "tester", "learner");
  private static final int ATTEMPTS = 5;
  private static final Instant LAST_TIME = Instant.parse("9999-12-31T23:59:59Z");

  public record Assignment(UUID accountId, String role, int number, Instant assignedAt) {}

  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final Clock clock;
  private final IntConsumer retryWait;

  public StaffAliasNumberStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
    this(client, tables, clock, StaffAliasNumberStore::backoff);
  }

  StaffAliasNumberStore(
      DynamoDbClient client, DynamoTableNames tables, Clock clock, IntConsumer retryWait) {
    this.client = Objects.requireNonNull(client, "client");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.retryWait = Objects.requireNonNull(retryWait, "retryWait");
  }

  public Assignment assign(UUID accountId, String role) {
    validate(accountId, role);
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      var known = read(accountId, role);
      if (known.isPresent()) return known.get();
      var counter = get(tables.userAccountDetailsTableName(), roleKey(role), "COUNTER");
      requireSchema(counter);
      long last = integer(counter, "lastAssigned", 0, Integer.MAX_VALUE - 1);
      long revision = integer(counter, "revision", 0, Long.MAX_VALUE - 1);
      String initializationId = uuid(counter, "initializationId").toString();
      var initialized =
          get(tables.administratorAuditEventsTableName(), roleKey(role), "INITIALIZED");
      requireSchema(initialized);
      if (!s(initializationId).equals(initialized.get("initializationId"))) {
        throw new IllegalStateException(
            "Role counter does not match immutable initialization marker");
      }
      int next = Math.toIntExact(last + 1);
      Instant assignedAt = clock.instant();
      requireTime(assignedAt);
      try {
        client.executeTransaction(
            request(accountId, role, next, revision, initializationId, assignedAt));
        // The immutable assignment, not an SDK token cache, is the durable result.
      } catch (SdkException failure) {
        try {
          var recovered = read(accountId, role);
          if (recovered.isPresent()) return recovered.get();
        } catch (RuntimeException reconciliationFailure) {
          RuntimeException classified = DynamoWrites.classify(failure);
          classified.addSuppressed(reconciliationFailure);
          throw classified;
        }
        if (!retryable(failure) || attempt == ATTEMPTS - 1) throw DynamoWrites.classify(failure);
        retryWait.accept(attempt);
        continue;
      }
      return read(accountId, role)
          .orElseThrow(
              () -> new IllegalStateException("Successful allocation has no durable assignment"));
    }
    throw new IllegalStateException("Role allocation retry budget exhausted");
  }

  public Optional<Assignment> read(UUID accountId, String role) {
    validate(accountId, role);
    var item =
        get(tables.administratorAuditEventsTableName(), "STAFF_ALIAS#" + accountId, "ROLE#" + role);
    if (item.isEmpty()) return Optional.empty();
    requireSchema(item);
    int number = Math.toIntExact(integer(item, "number", 1, Integer.MAX_VALUE));
    Instant assignedAt;
    try {
      assignedAt = Instant.parse(item.get("assignedAt").s());
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Invalid alias assignment time", invalid);
    }
    requireTime(assignedAt);
    var claim = get(tables.administratorAuditEventsTableName(), roleKey(role), numberKey(number));
    requireSchema(claim);
    if (!s(accountId.toString()).equals(claim.get("accountId"))
        || !item.get("assignedAt").equals(claim.get("assignedAt"))) {
      throw new IllegalStateException("Alias assignment does not match its immutable number claim");
    }
    return Optional.of(new Assignment(accountId, role, number, assignedAt));
  }

  private ExecuteTransactionRequest request(
      UUID accountId, String role, int next, long revision, String initializationId, Instant time) {
    String accountTable = '"' + tables.userAccountDetailsTableName() + '"';
    String auditTable = '"' + tables.administratorAuditEventsTableName() + '"';
    return ExecuteTransactionRequest.builder()
        .transactStatements(
            statement(
                "UPDATE "
                    + accountTable
                    + " SET lastAssigned=? SET revision=? WHERE pk=? AND sk=? AND schemaVersion=1"
                    + " AND lastAssigned=? AND revision=? AND initializationId=?",
                n(next),
                n(revision + 1),
                s(roleKey(role)),
                s("COUNTER"),
                n(next - 1),
                n(revision),
                s(initializationId)),
            statement(
                "INSERT INTO "
                    + auditTable
                    + " VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'number': ?, 'assignedAt': ?}",
                s("STAFF_ALIAS#" + accountId),
                s("ROLE#" + role),
                n(next),
                s(time.toString())),
            statement(
                "INSERT INTO "
                    + auditTable
                    + " VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'accountId': ?, 'assignedAt':"
                    + " ?}",
                s(roleKey(role)),
                s(numberKey(next)),
                s(accountId.toString()),
                s(time.toString())))
        .build(); // No reusable SDK token: immutable keys provide durable idempotency across
    // attempts.
  }

  private static boolean retryable(SdkException failure) {
    if (!(failure instanceof TransactionCanceledException canceled)) return false;
    // Only a counter race or same-account insert race is automatically retried. Permission,
    // validation, capacity and ambiguous transport failures retain the caller's retry boundary.
    var reasons = canceled.cancellationReasons();
    if (reasons.isEmpty()) return false;
    boolean ordinaryConflict =
        reasons.stream()
            .allMatch(reason ->
                List.of("None", "ConditionalCheckFailed", "DuplicateItem", "TransactionConflict")
                    .contains(reason.code()));
    // PartiQL can classify a duplicate INSERT as ValidationError. Retry that combination
    // only when this transaction's counter condition also lost a race. A lone validation
    // error (including a retained number claim with a rolled-back counter) fails closed.
    boolean counterAndClaimRace =
        reasons.size() == 3
            && "ConditionalCheckFailed".equals(reasons.getFirst().code())
            && reasons.subList(1, 3).stream()
                .allMatch(reason -> List.of("None", "ValidationError").contains(reason.code()));
    return ordinaryConflict || counterAndClaimRace;
  }

  private Map<String, AttributeValue> get(String table, String pk, String sk) {
    return client
        .getItem(
            GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("pk", s(pk), "sk", s(sk)))
                .consistentRead(true)
                .build())
        .item();
  }

  private static void validate(UUID id, String role) {
    Objects.requireNonNull(id, "accountId");
    if (role == null || !ROLES.contains(role))
      throw new IllegalArgumentException("Unsupported staff alias role");
  }

  private static void requireSchema(Map<String, AttributeValue> item) {
    if (!n(1).equals(item.get("schemaVersion")))
      throw new IllegalStateException("Missing or invalid staff alias schema");
  }

  private static long integer(Map<String, AttributeValue> item, String name, long min, long max) {
    try {
      long value = Long.parseLong(item.get(name).n());
      if (value < min || value > max) throw new IllegalArgumentException("Out of range");
      return value;
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Invalid staff alias " + name, invalid);
    }
  }

  private static UUID uuid(Map<String, AttributeValue> item, String name) {
    try {
      String value = item.get(name).s();
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw new IllegalArgumentException("Non-canonical UUID");
      return id;
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Invalid staff alias " + name, invalid);
    }
  }

  private static void requireTime(Instant time) {
    if (time.isBefore(Instant.EPOCH) || time.isAfter(LAST_TIME))
      throw new IllegalStateException("Invalid alias time range");
  }

  private static void backoff(int attempt) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, 2L << (attempt + 3)));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while assigning staff alias", interrupted);
    }
  }

  private static String roleKey(String role) {
    return "STAFF_ROLE#" + role;
  }

  private static String numberKey(int number) {
    return "NUMBER#" + String.format(Locale.ROOT, "%010d", number);
  }

  private static ParameterizedStatement statement(String sql, AttributeValue... parameters) {
    return ParameterizedStatement.builder().statement(sql).parameters(parameters).build();
  }
}
