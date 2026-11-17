package com.lookahead.domain.review.platform;


/** DLV-971: the next record number for a kind (review records and support requests), unique and increasing. */
@FunctionalInterface
public interface ReviewRecordNumbers {
    long next(String kind);

    /**
     * DLV-970: the two counters on DynamoDB, {@code SEQUENCE#review-record / COUNTER} and
     * {@code SEQUENCE#support-request / COUNTER} in User Account Details, created at 0 by migration step 4. Like
     * {@code nextval}, unique and increasing is promised, gapless is not. The runtime role writes PartiQL only, so
     * each number is a strong read of {@code lastAssigned} followed by {@code UPDATE … SET "lastAssigned"=? WHERE
     * "lastAssigned"=?}; a lost race re-reads and retries a few times before the conflict surfaces (409). A missing
     * counter is inserted at 1 (the step is replayable, so a store that was never migrated still numbers from 1).
     * Step 4 seeds 0; the DEV import (step 5, 2026-11-17) raised both to the values the retired PostgreSQL sequences had reached.
     */
    final class Dynamo implements ReviewRecordNumbers {
        static final String REVIEW = "SEQUENCE#review-record", REQUEST = "SEQUENCE#support-request", COUNTER = "COUNTER";
        static final int ATTEMPTS = 8;
        private final software.amazon.awssdk.services.dynamodb.DynamoDbClient client;
        private final com.lookahead.domain.persistence.dynamodb.DynamoTableNames tables;
        private final java.util.function.IntConsumer retryWait;
        public Dynamo(software.amazon.awssdk.services.dynamodb.DynamoDbClient client, com.lookahead.domain.persistence.dynamodb.DynamoTableNames tables) {
            this(client, tables, Dynamo::backoff);
        }
        Dynamo(software.amazon.awssdk.services.dynamodb.DynamoDbClient client, com.lookahead.domain.persistence.dynamodb.DynamoTableNames tables, java.util.function.IntConsumer retryWait) {
            this.client = java.util.Objects.requireNonNull(client, "client"); this.tables = java.util.Objects.requireNonNull(tables, "tables");
            this.retryWait = java.util.Objects.requireNonNull(retryWait, "retryWait");
        }
        /** {@code request} is the support-request counter, anything else the review one. */
        static String partition(String kind) { return PlatformReviewModels.KIND_REQUEST.equals(kind) ? REQUEST : REVIEW; }
        @Override public long next(String kind) {
            var key = com.lookahead.domain.persistence.dynamodb.DynamoItems.key(partition(kind), COUNTER);
            String table = tables.userAccountDetailsTableName();
            com.lookahead.domain.persistence.dynamodb.DynamoWrites.Conflict lost = null;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                if (attempt > 0) retryWait.accept(attempt);
                var counter = client.getItem(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
                        .tableName(table).key(key).consistentRead(true).build()).item();
                var tx = com.lookahead.domain.persistence.dynamodb.DynamoWrites.transaction(client);
                long next;
                if (counter.isEmpty()) {
                    next = 1;
                    tx.insertOnly(table, com.lookahead.domain.persistence.dynamodb.DynamoItems.item(partition(kind), COUNTER, 1,
                            java.util.Map.of("lastAssigned", com.lookahead.domain.persistence.dynamodb.DynamoItems.n(next))));
                } else {
                    com.lookahead.domain.persistence.dynamodb.DynamoItems.requireSchema(counter, 1);
                    long last = com.lookahead.domain.persistence.dynamodb.DynamoItems.number(counter, "lastAssigned");
                    if (last < 0) throw new IllegalStateException("Stored record counter is negative");
                    next = Math.addExact(last, 1);
                    tx.update(table, key, java.util.Map.of("lastAssigned", com.lookahead.domain.persistence.dynamodb.DynamoItems.n(next)),
                            "\"lastAssigned\"=?", com.lookahead.domain.persistence.dynamodb.DynamoItems.n(last));
                }
                try { tx.commit(); return next; }
                catch (com.lookahead.domain.persistence.dynamodb.DynamoWrites.Conflict raced) { lost = raced; }
            }
            throw lost;
        }
        private static void backoff(int attempt) {
            try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 2L << (attempt + 2))); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while assigning a record number", interrupted); }
        }
    }

    /** For tests and the Local fixture: counts in memory from 1. */
    final class InMemory implements ReviewRecordNumbers {
        private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> counters = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public long next(String kind) { return counters.computeIfAbsent(kind, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet(); }
    }
}
