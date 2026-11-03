package com.lookahead.domain.persistence.dynamodb;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/** One bounded maintenance page per call. No scheduler, Spring registration or table scan. */
public final class StudyPlanDocumentMaintenance {
    public static final int MAX_JOBS = 25;
    private static final String PARTITION = "WORK#DOCUMENT#00";
    private static final long MAX_SECOND = 253402300799L;

    /** Internal continuation, not a client token. Reuse its fixed cutoff until this sweep ends. */
    public record Cursor(Instant cutoff, String afterKey) {
        public Cursor {
            validateTime(cutoff);
            Objects.requireNonNull(afterKey, "afterKey");
            if (afterKey.isEmpty() || afterKey.length() > 1024 || afterKey.compareTo(upperKey(cutoff)) > 0) {
                throw new IllegalArgumentException("Invalid maintenance continuation");
            }
        }
    }
    public enum Outcome { CLEANED, RETAINED, FAILED }
    public record JobResult(String key, Outcome outcome) {}
    public record Batch(Instant cutoff, List<JobResult> jobs, Cursor next) {
        public Batch { jobs = List.copyOf(jobs); }
    }

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;
    private final StudyPlanDocumentStore documents;

    public StudyPlanDocumentMaintenance(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.documents = new StudyPlanDocumentStore(client, tables, clock);
    }

    /** Failures retain their jobs; continue the page, then revisit them in the next sweep. */
    public Batch cleanDue(int limit, Cursor cursor) {
        if (limit < 1 || limit > MAX_JOBS) throw new IllegalArgumentException("Maintenance limit must be 1–25");
        Instant now = clock.instant();
        validateTime(now);
        Instant cutoff = cursor == null ? now : cursor.cutoff();
        if (cutoff.isAfter(now)) throw new IllegalArgumentException("Maintenance cutoff cannot be in the future");
        var request = QueryRequest.builder().tableName(tables.userAccountDetailsTableName())
                .consistentRead(true).scanIndexForward(true).limit(limit)
                .keyConditionExpression("pk = :pk AND sk <= :due")
                .expressionAttributeValues(Map.of(":pk", s(PARTITION), ":due", s(upperKey(cutoff))));
        if (cursor != null) request.exclusiveStartKey(key(cursor.afterKey()));
        var response = client.query(request.build());
        if (response.items().size() > limit) throw new IllegalStateException("Maintenance page exceeded requested bound");
        Cursor next = null;
        if (!response.lastEvaluatedKey().isEmpty()) {
            var last = response.lastEvaluatedKey();
            if (!s(PARTITION).equals(last.get("pk")) || last.size() != 2) {
                throw new IllegalStateException("Invalid maintenance continuation partition");
            }
            next = new Cursor(cutoff, string(last, "sk"));
            if (cursor != null && next.afterKey().compareTo(cursor.afterKey()) <= 0) {
                throw new IllegalStateException("Maintenance continuation did not advance");
            }
        }
        var results = new ArrayList<JobResult>();
        for (var item : response.items()) {
            String jobKey = string(item, "sk");
            try {
                var id = parseJob(item, cutoff);
                results.add(new JobResult(jobKey, documents.cleanAbandoned(id) ? Outcome.CLEANED : Outcome.RETAINED));
            } catch (RuntimeException failure) {
                // Do not remove or rewrite a failed job, expose payloads, or retry indefinitely in one call.
                results.add(new JobResult(jobKey, Outcome.FAILED));
            }
        }
        return new Batch(cutoff, results, next);
    }

    private static StudyPlanDocumentStore.DocumentId parseJob(Map<String, AttributeValue> item, Instant cutoff) {
        if (!s(PARTITION).equals(item.get("pk")) || !AttributeValue.builder().n("1").build().equals(item.get("schemaVersion"))) {
            throw new IllegalStateException("Invalid maintenance job schema");
        }
        String documentPk = string(item, "documentPk");
        String[] parts = documentPk.split("#", -1);
        if (parts.length != 3 || !parts[0].equals("DOC")) throw new IllegalStateException("Invalid document job identity");
        var id = new StudyPlanDocumentStore.DocumentId(UUID.fromString(parts[1]), UUID.fromString(parts[2]));
        if (!id.pk().equals(documentPk)) throw new IllegalStateException("Noncanonical document job identity");
        String sk = string(item, "sk");
        if (sk.length() < 18 || !sk.startsWith("DUE#") || sk.charAt(16) != '#'
                || !sk.substring(4, 16).matches("[0-9]{12}") || !sk.substring(17).equals(documentPk)) {
            throw new IllegalStateException("Invalid document job key");
        }
        long deadline = Long.parseLong(sk.substring(4, 16));
        if (deadline > cutoff.getEpochSecond()) throw new IllegalStateException("Job is not due");
        return id;
    }

    private static void validateTime(Instant time) {
        Objects.requireNonNull(time, "time");
        if (time.isBefore(Instant.EPOCH) || time.getEpochSecond() > MAX_SECOND) throw new IllegalArgumentException("Unsupported maintenance time");
    }
    private static String upperKey(Instant time) {
        return "DUE#" + String.format(Locale.ROOT, "%012d", time.getEpochSecond()) + "#\uffff";
    }
    private static String string(Map<String, AttributeValue> item, String name) {
        var value = item.get(name);
        if (value == null || value.s() == null) throw new IllegalStateException("Missing maintenance key");
        return value.s();
    }
    private static Map<String, AttributeValue> key(String sk) { return Map.of("pk", s(PARTITION), "sk", s(sk)); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
}
