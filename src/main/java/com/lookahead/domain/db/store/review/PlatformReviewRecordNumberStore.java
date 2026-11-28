package com.lookahead.domain.db.store.review;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.model.PlatformReviewModels;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the two counters on DynamoDB, {@code SEQUENCE#review-record / COUNTER} and {@code
 * SEQUENCE#support-request / COUNTER} in User Account Details, created at 0 by migration step 4.
 * Like {@code nextval}, unique and increasing is promised, gapless is not. The runtime role writes
 * PartiQL only, so each number is a strong read of {@code lastAssigned} followed by {@code UPDATE …
 * SET "lastAssigned"=? WHERE "lastAssigned"=?}; a lost race re-reads and retries a few times before
 * the conflict surfaces (409). A missing counter is inserted at 1 (the step is replayable, so a
 * store that was never migrated still numbers from 1). Step 4 seeds 0; the DEV import (step 5,
 * 2026-11-17) raised both to the values the retired PostgreSQL sequences had reached.
 */
public final class PlatformReviewRecordNumberStore implements PlatformReviewRecordNumber {
  static final String REVIEW = "SEQUENCE#review-record",
      REQUEST = "SEQUENCE#support-request",
      COUNTER = "COUNTER";
  static final int ATTEMPTS = 8;
  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final IntConsumer retryWait;

  public PlatformReviewRecordNumberStore(DynamoDbClient client, DynamoTableNames tables) {
    this(client, tables, PlatformReviewRecordNumberStore::backoff);
  }

  PlatformReviewRecordNumberStore(
      DynamoDbClient client, DynamoTableNames tables, IntConsumer retryWait) {
    this.client = Objects.requireNonNull(client, "client");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.retryWait = Objects.requireNonNull(retryWait, "retryWait");
  }

  /** {@code request} is the support-request counter, anything else the review one. */
  static String partition(String kind) {
    return PlatformReviewModels.KIND_REQUEST.equals(kind) ? REQUEST : REVIEW;
  }

  @Override
  public long next(String kind) {
    var key = DynamoItems.key(partition(kind), COUNTER);
    String table = tables.userAccountDetailsTableName();
    DynamoWrites.Conflict lost = null;
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      if (attempt > 0) retryWait.accept(attempt);
      var counter = DynamoItems.strongGet(client, table, partition(kind), COUNTER);
      var tx = DynamoWrites.transaction(client);
      long next;
      if (counter.isEmpty()) {
        next = 1;
        tx.insertOnly(
            table,
            DynamoItems.item(
                partition(kind), COUNTER, 1, Map.of("lastAssigned", DynamoItems.n(next))));
      } else {
        DynamoItems.requireSchema(counter, 1);
        long last = DynamoItems.number(counter, "lastAssigned");
        if (last < 0) throw new IllegalStateException("Stored record counter is negative");
        next = Math.addExact(last, 1);
        tx.update(
            table,
            key,
            Map.of("lastAssigned", DynamoItems.n(next)),
            "\"lastAssigned\"=?",
            DynamoItems.n(last));
      }
      try {
        tx.commit();
        return next;
      } catch (DynamoWrites.Conflict raced) {
        lost = raced;
      }
    }
    throw lost;
  }

  private static void backoff(int attempt) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, 2L << (attempt + 2)));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while assigning a record number", interrupted);
    }
  }
}
