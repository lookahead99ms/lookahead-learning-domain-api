package com.lookahead.domain.db.store.plan;

import static com.lookahead.domain.db.store.dynamodb.DynamoItems.s;
import static com.lookahead.domain.model.StudyPlanDocumentJobModels.MAX_JOBS;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.model.StudyPlanDocumentJobModels.JobResult;
import com.lookahead.domain.model.StudyPlanDocumentJobModels.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * One bounded page of abandoned-upload cleanup per call: a strong Query of the due {@code
 * WORK#DOCUMENT#00} jobs, then {@link StudyPlanDocumentStore#cleanAbandoned} per job. {@code
 * config.StudyPlanDocumentJobConfiguration} builds it and {@code job.StudyPlanDocumentCleanupJob}
 * drives it from the sweep endpoint; there is no table scan and no scheduler of its own.
 */
public final class StudyPlanDocumentCleanupStore {
  private static final String PARTITION = "WORK#DOCUMENT#00";
  private static final long MAX_SECOND = 253402300799L;

  /** Internal continuation, not a client token. Reuse its fixed cutoff until this sweep ends. */
  public record Cursor(Instant cutoff, String afterKey) {
    public Cursor {
      validateTime(cutoff);
      Objects.requireNonNull(afterKey, "afterKey");
      if (afterKey.isEmpty()
          || afterKey.length() > 1024
          || afterKey.compareTo(upperKey(cutoff)) > 0) {
        throw new IllegalArgumentException("Invalid maintenance continuation");
      }
    }
  }

  public record Batch(Instant cutoff, List<JobResult> jobs, Cursor next) {
    public Batch {
      jobs = List.copyOf(jobs);
    }
  }

  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final Clock clock;
  private final StudyPlanDocumentStore documents;

  public StudyPlanDocumentCleanupStore(
      DynamoDbClient client, DynamoTableNames tables, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.documents = new StudyPlanDocumentStore(client, tables, clock);
  }

  /** Failures retain their jobs; continue the page, then revisit them in the next sweep. */
  public Batch cleanDue(int limit, Cursor cursor) {
    if (limit < 1 || limit > MAX_JOBS)
      throw new IllegalArgumentException("Maintenance limit must be 1–25");
    Instant now = clock.instant();
    validateTime(now);
    Instant cutoff = cursor == null ? now : cursor.cutoff();
    if (cutoff.isAfter(now))
      throw new IllegalArgumentException("Maintenance cutoff cannot be in the future");
    var request =
        QueryRequest.builder()
            .tableName(tables.userAccountDetailsTableName())
            .consistentRead(true)
            .scanIndexForward(true)
            .limit(limit)
            .keyConditionExpression("pk = :pk AND sk <= :due")
            .expressionAttributeValues(Map.of(":pk", s(PARTITION), ":due", s(upperKey(cutoff))));
    if (cursor != null) request.exclusiveStartKey(key(cursor.afterKey()));
    var response = client.query(request.build());
    if (response.items().size() > limit)
      throw new IllegalStateException("Maintenance page exceeded requested bound");
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
        results.add(
            new JobResult(
                jobKey, documents.cleanAbandoned(id) ? Outcome.CLEANED : Outcome.RETAINED));
      } catch (RuntimeException failure) {
        // Do not remove or rewrite a failed job, expose payloads, or retry indefinitely in one
        // call.
        results.add(new JobResult(jobKey, Outcome.FAILED));
      }
    }
    return new Batch(cutoff, results, next);
  }

  private static StudyPlanDocumentStore.DocumentId parseJob(
      Map<String, AttributeValue> item, Instant cutoff) {
    if (!s(PARTITION).equals(item.get("pk"))
        || !AttributeValue.builder().n("1").build().equals(item.get("schemaVersion"))) {
      throw new IllegalStateException("Invalid maintenance job schema");
    }
    String documentPk = string(item, "documentPk");
    var id = StudyPlanDocumentStore.DocumentId.parse(documentPk);
    String sk = string(item, "sk");
    if (sk.length() < 18
        || !sk.startsWith("DUE#")
        || sk.charAt(16) != '#'
        || !sk.substring(4, 16).matches("[0-9]{12}")
        || !sk.substring(17).equals(documentPk)) {
      throw new IllegalStateException("Invalid document job key");
    }
    long deadline = Long.parseLong(sk.substring(4, 16));
    if (deadline > cutoff.getEpochSecond()) throw new IllegalStateException("Job is not due");
    return id;
  }

  private static void validateTime(Instant time) {
    Objects.requireNonNull(time, "time");
    if (time.isBefore(Instant.EPOCH) || time.getEpochSecond() > MAX_SECOND)
      throw new IllegalArgumentException("Unsupported maintenance time");
  }

  private static String upperKey(Instant time) {
    return "DUE#" + String.format(Locale.ROOT, "%012d", time.getEpochSecond()) + "#\uffff";
  }

  private static String string(Map<String, AttributeValue> item, String name) {
    var value = item.get(name);
    if (value == null || value.s() == null)
      throw new IllegalStateException("Missing maintenance key");
    return value.s();
  }

  private static Map<String, AttributeValue> key(String sk) {
    return Map.of("pk", s(PARTITION), "sk", s(sk));
  }
}
