package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

/**
 * DLV-970: exclusive migration ownership with a fence. One {@code DATA_VERSION#DOMAIN / LOCK} item
 * in Protected Owner Identity holds the current runner, a monotonically increasing fence number and
 * an expiry. A runner takes the lock only when it is free or expired, by a conditional update on
 * the fence it observed; every ledger write then checks that the lock still carries this runner and
 * fence, so a runner that stalled past its lease cannot record a step after a newer runner took
 * over. Releasing clears the expiry and keeps the fence, so numbers never go backwards. Every write
 * is PartiQL through {@link DynamoWrites}, fenced on the item's schema version as well, so the
 * migration-task role needs the same PartiQL permissions as the runtime.
 */
public final class DynamoMigrationLock {
  public static final String PK = "DATA_VERSION#DOMAIN", SK = "LOCK";
  static final Map<String, AttributeValue> KEY = Map.of("pk", s(PK), "sk", s(SK));

  public record Lease(String runId, long fence, Instant expiresAt) {}

  /** Another runner holds an unexpired lease. */
  public static final class Locked extends IllegalStateException {
    Locked() {
      super("Another migration run holds the lock");
    }
  }

  private final DynamoDbClient client;
  private final String table;

  public DynamoMigrationLock(DynamoDbClient client, DynamoTableNames tables) {
    this.client = Objects.requireNonNull(client, "client");
    this.table = Objects.requireNonNull(tables, "tables").protectedOwnerIdentityTableName();
  }

  public Lease acquire(String runId, Instant now, Duration lease) {
    requireRunId(runId);
    if (lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofHours(6)) > 0)
      throw new IllegalArgumentException("Lease must be 1 second through 6 hours");
    Instant expiresAt = now.plus(lease);
    var current =
        client
            .getItem(
                GetItemRequest.builder().tableName(table).key(KEY).consistentRead(true).build())
            .item();
    if (current.isEmpty()) {
      try {
        DynamoWrites.transaction(client)
            .insertOnly(
                table,
                Map.of(
                    "pk",
                    s(PK),
                    "sk",
                    s(SK),
                    "schemaVersion",
                    n("1"),
                    "runId",
                    s(runId),
                    "fence",
                    n("1"),
                    "expiresAt",
                    n(Long.toString(expiresAt.getEpochSecond())),
                    "acquiredAt",
                    s(now.toString())))
            .commit();
      } catch (DynamoWrites.Conflict raced) {
        throw new Locked();
      }
      return new Lease(runId, 1, expiresAt);
    }
    if (!n("1").equals(current.get("schemaVersion")))
      throw new IllegalStateException("Malformed migration lock");
    long fence = number(current.get("fence"));
    long expiry = number(current.get("expiresAt"));
    String holder = current.get("runId") == null ? "" : current.get("runId").s();
    if (expiry > now.getEpochSecond() && !runId.equals(holder)) throw new Locked();
    long next = fence + 1;
    try {
      DynamoWrites.transaction(client)
          .update(
              table,
              KEY,
              Map.of(
                  "runId",
                  s(runId),
                  "fence",
                  n(Long.toString(next)),
                  "expiresAt",
                  n(Long.toString(expiresAt.getEpochSecond())),
                  "acquiredAt",
                  s(now.toString())),
              "fence=? AND schemaVersion=?",
              n(Long.toString(fence)),
              n("1"))
          .commit();
    } catch (DynamoWrites.Conflict raced) {
      throw new Locked();
    }
    return new Lease(runId, next, expiresAt);
  }

  /** Extends an owned lease; a lease that was taken over cannot be renewed. */
  public Lease renew(Lease lease, Instant now, Duration extension) {
    Instant expiresAt = now.plus(extension);
    try {
      DynamoWrites.transaction(client)
          .update(
              table,
              KEY,
              Map.of("expiresAt", n(Long.toString(expiresAt.getEpochSecond()))),
              "runId=? AND fence=? AND schemaVersion=?",
              s(lease.runId()),
              n(Long.toString(lease.fence())),
              n("1"))
          .commit();
    } catch (DynamoWrites.Conflict lost) {
      throw new Locked();
    }
    return new Lease(lease.runId(), lease.fence(), expiresAt);
  }

  /** Ends an owned lease now; the fence stays. A lease already taken over is left alone. */
  public void release(Lease lease) {
    try {
      DynamoWrites.transaction(client)
          .update(
              table,
              KEY,
              Map.of("expiresAt", n("0")),
              "runId=? AND fence=? AND schemaVersion=?",
              s(lease.runId()),
              n(Long.toString(lease.fence())),
              n("1"))
          .commit();
    } catch (DynamoWrites.Conflict takenOver) {
      // Nothing to release: a newer runner owns the lock.
    }
  }

  static void requireRunId(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
      throw new IllegalArgumentException("Invalid migration run ID");
  }

  static long number(AttributeValue value) {
    if (value == null || value.n() == null || !value.n().matches("(0|[1-9][0-9]{0,18})"))
      throw new IllegalStateException("Malformed migration lock");
    return Long.parseLong(value.n());
  }

  static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  static AttributeValue n(String value) {
    return AttributeValue.builder().n(value).build();
  }
}
