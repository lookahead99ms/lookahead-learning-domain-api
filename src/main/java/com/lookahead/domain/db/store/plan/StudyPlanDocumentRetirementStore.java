package com.lookahead.domain.db.store.plan;

import static com.lookahead.domain.db.store.dynamodb.DynamoItems.key;
import static com.lookahead.domain.db.store.dynamodb.DynamoItems.n;
import static com.lookahead.domain.db.store.dynamodb.DynamoItems.s;
import static com.lookahead.domain.model.StudyPlanDocumentJobModels.MAX_JOBS;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.model.StudyPlanDocumentJobModels.JobResult;
import com.lookahead.domain.model.StudyPlanDocumentJobModels.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ParameterizedStatement;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * One published snapshot's audited retirement (the account pointer becomes a tombstone, the
 * manifest RETIRED, a cleanup job is queued, all in one {@link AccountAuditedStore} transaction)
 * and the bounded chunk cleanup behind it, driven by {@code job.StudyPlanDocumentRetirementJob} and
 * the plan purge. Not a whole-plan deletion API. Every write is PartiQL through {@link
 * DynamoWrites}; a lost fence is {@link DynamoWrites.Conflict}.
 */
public final class StudyPlanDocumentRetirementStore {
  private static final String PARTITION = "WORK#RETIRED-DOCUMENT#00";

  public record Page(List<JobResult> jobs, String afterKey) {
    public Page {
      jobs = List.copyOf(jobs);
    }
  }

  /** Only the validated retirement path can mint the additional transaction statements. */
  static final class VerifiedRetirement {
    private final StudyPlanDocumentStore.Stage stage;
    private final UUID publicationId;
    private final UUID retirementId;
    private final String recordId;
    private final long expectedRevision;
    private final Instant retiredAt;
    private final boolean replayOnly;

    private VerifiedRetirement(
        StudyPlanDocumentStore.Stage stage,
        UUID publicationId,
        UUID retirementId,
        String recordId,
        long expectedRevision,
        Instant retiredAt,
        boolean replayOnly) {
      this.stage = stage;
      this.publicationId = publicationId;
      this.retirementId = retirementId;
      this.recordId = recordId;
      this.expectedRevision = expectedRevision;
      this.retiredAt = retiredAt;
      this.replayOnly = replayOnly;
    }

    boolean replayOnly() {
      return replayOnly;
    }

    String fingerprintScope() {
      return "document-retirement/v1|"
          + publicationId
          + "|"
          + stage.manifest().sha256()
          + "|"
          + stage.manifest().byteLength()
          + "|"
          + stage.manifest().chunkCount()
          + "|"
          + stage.abandonAfter().getEpochSecond();
    }

    List<ParameterizedStatement> statements(DynamoTableNames tables) {
      return List.of(
          statement(
              "UPDATE \""
                  + tables.userStudyPlanDetailsTableName()
                  + "\" SET lifecycle=? SET retirementId=? SET retirementRecordId=? SET"
                  + " retirementExpectedRevision=? SET retiredAt=? WHERE pk=? AND sk=? AND"
                  + " schemaVersion=1 AND lifecycle=? AND publicationId=? AND sha256=? AND"
                  + " byteLength=? AND chunkCount=? AND abandonAfter=?",
              s("RETIRED"),
              s(retirementId.toString()),
              s(recordId),
              n(expectedRevision),
              s(retiredAt.toString()),
              s(stage.id().pk()),
              s("MANIFEST"),
              s("COMMITTED"),
              s(publicationId.toString()),
              s(stage.manifest().sha256()),
              n(stage.manifest().byteLength()),
              n(stage.manifest().chunkCount()),
              n(stage.abandonAfter().getEpochSecond())),
          statement(
              "INSERT INTO \""
                  + tables.userAccountDetailsTableName()
                  + "\" VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'documentPk': ?,"
                  + " 'retirementId': ?}",
              s(PARTITION),
              s(stage.id().pk()),
              s(stage.id().pk()),
              s(retirementId.toString())));
    }
  }

  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final AccountAuditedStore accounts;

  public StudyPlanDocumentRetirementStore(DynamoDbClient client, DynamoTableNames tables) {
    this.client = Objects.requireNonNull(client, "client");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.accounts = new AccountAuditedStore(client, tables);
  }

  /**
   * Caller authorizes the owner/plan. Retire only the exact currently referenced document/revision.
   * A document already RETIRED by this operation is a replay answered from the durable receipt,
   * looked up under the audit time of the first attempt (stored on the manifest), whatever a later
   * retry carries.
   */
  public AccountAuditedStore.CommitResult retire(
      StudyPlanDocumentStore.Stage stage,
      String recordId,
      UUID operationId,
      long expectedRevision,
      AccountAuditedStore.AuditEvent audit) {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(audit, "audit");
    if (expectedRevision < 1)
      throw new IllegalArgumentException("Retirement requires an existing revision");
    var mutation =
        new AccountAuditedStore.Mutation(
            stage.id().ownerId(),
            recordId,
            operationId,
            expectedRevision,
            tombstone(stage.id()),
            audit);
    var item = manifest(stage.id());
    if (!stage.equals(StudyPlanDocumentStore.parse(stage.id(), item))) {
      throw new IllegalStateException("Retirement document does not match its manifest");
    }
    String state = StudyPlanDocumentStore.lifecycle(item);
    UUID publication = uuid(item, "publicationId");
    boolean replayOnly = "RETIRED".equals(state);
    if (replayOnly) {
      requireSameRetirement(item, operationId, recordId, expectedRevision);
      audit = StudyPlanDocumentStore.audited(audit, item, "retiredAt");
    } else {
      if (!"COMMITTED".equals(state))
        throw new IllegalStateException("Only published documents can be retired");
      var record =
          accounts
              .read(stage.id().ownerId(), recordId)
              .orElseThrow(() -> new IllegalStateException("Document reference is missing"));
      if (record.revision() != expectedRevision
          || !record.payload().equals(StudyPlanDocumentStore.pointer(stage))) {
        // Another identical caller may have retired between the manifest and account reads.
        var recovered = manifest(stage.id());
        if (!stage.equals(StudyPlanDocumentStore.parse(stage.id(), recovered))
            || !"RETIRED".equals(StudyPlanDocumentStore.lifecycle(recovered))
            || !publication.equals(uuid(recovered, "publicationId"))) {
          throw new IllegalStateException(
              "Document is not the current account reference at this revision");
        }
        requireSameRetirement(recovered, operationId, recordId, expectedRevision);
        audit = StudyPlanDocumentStore.audited(audit, recovered, "retiredAt");
        replayOnly = true;
      }
    }
    if (replayOnly)
      mutation =
          new AccountAuditedStore.Mutation(
              stage.id().ownerId(),
              recordId,
              operationId,
              expectedRevision,
              tombstone(stage.id()),
              audit);
    return accounts.retireDocument(
        mutation,
        new VerifiedRetirement(
            stage,
            publication,
            operationId,
            recordId,
            expectedRevision,
            audit.occurredAt(),
            replayOnly));
  }

  private static void requireSameRetirement(
      Map<String, AttributeValue> item, UUID operationId, String recordId, long expectedRevision) {
    if (!operationId.equals(uuid(item, "retirementId"))
        || !s(recordId).equals(item.get("retirementRecordId"))
        || !n(expectedRevision).equals(item.get("retirementExpectedRevision"))) {
      throw new IllegalStateException("Document was retired by a different operation");
    }
  }

  /**
   * Deletes at most 35 chunks, each under the RETIRED manifest fence. The RETIRED manifest and
   * account tombstone are retained.
   */
  public boolean cleanRetired(StudyPlanDocumentStore.DocumentId id) {
    var item = manifest(id);
    if (item.isEmpty()) return false;
    var stage = StudyPlanDocumentStore.parse(id, item);
    if (!"RETIRED".equals(StudyPlanDocumentStore.lifecycle(item))) return false;
    UUID retirement = uuid(item, "retirementId");
    for (int i = 0; i < stage.manifest().chunkCount(); i++) {
      fence(DynamoWrites.transaction(client), stage, retirement)
          .delete(
              tables.userStudyPlanDetailsTableName(),
              key(id.pk(), StudyPlanDocumentStore.chunkKey(i)),
              null)
          .commit();
    }
    // A job of another retirement generation is left alone (the WHERE fences an existing item; a
    // missing one is a no-op).
    fence(DynamoWrites.transaction(client), stage, retirement)
        .delete(
            tables.userAccountDetailsTableName(),
            key(PARTITION, id.pk()),
            "schemaVersion=? AND retirementId=?",
            n(1),
            s(retirement.toString()))
        .commit();
    return true;
  }

  /**
   * One strong Query page, no scan or scheduler. Start a fresh sweep to revisit jobs behind the
   * cursor.
   */
  public Page cleanPage(int limit, String afterKey) {
    if (limit < 1 || limit > MAX_JOBS)
      throw new IllegalArgumentException("Cleanup limit must be 1–25");
    validateCursor(afterKey);
    var request =
        QueryRequest.builder()
            .tableName(tables.userAccountDetailsTableName())
            .keyConditionExpression("pk=:pk")
            .expressionAttributeValues(Map.of(":pk", s(PARTITION)))
            .consistentRead(true)
            .scanIndexForward(true)
            .limit(limit);
    if (afterKey != null) request.exclusiveStartKey(key(PARTITION, afterKey));
    var response = client.query(request.build());
    if (response.items().size() > limit)
      throw new IllegalStateException("Cleanup page exceeded its bound");
    String next = null;
    if (!response.lastEvaluatedKey().isEmpty()) {
      var last = response.lastEvaluatedKey();
      next = string(last, "sk");
      validateCursor(next);
      if (last.size() != 2
          || !s(PARTITION).equals(last.get("pk"))
          || (afterKey != null && next.compareTo(afterKey) <= 0)) {
        throw new IllegalStateException("Invalid cleanup continuation");
      }
    }
    var results = new ArrayList<JobResult>();
    for (var job : response.items()) {
      String label = job.get("sk") == null ? "invalid-job" : job.get("sk").s();
      try {
        String documentPk = string(job, "documentPk");
        if (!s(PARTITION).equals(job.get("pk"))
            || !n(1).equals(job.get("schemaVersion"))
            || !s(documentPk).equals(job.get("sk")))
          throw new IllegalStateException("Invalid cleanup job");
        var id = StudyPlanDocumentStore.DocumentId.parse(documentPk);
        var item = manifest(id);
        // A corrupt/stale queue item must never authorize another document generation's deletion.
        if (item.isEmpty() || !"RETIRED".equals(StudyPlanDocumentStore.lifecycle(item))) {
          results.add(new JobResult(label, Outcome.RETAINED));
        } else {
          if (!uuid(job, "retirementId").equals(uuid(item, "retirementId"))) {
            throw new IllegalStateException("Cleanup job does not match retirement");
          }
          results.add(new JobResult(label, cleanRetired(id) ? Outcome.CLEANED : Outcome.RETAINED));
        }
      } catch (RuntimeException failure) {
        results.add(new JobResult(label, Outcome.FAILED));
      }
    }
    return new Page(results, next);
  }

  private DynamoWrites fence(DynamoWrites tx, StudyPlanDocumentStore.Stage stage, UUID retirement) {
    return tx.check(
        tables.userStudyPlanDetailsTableName(),
        key(stage.id().pk(), "MANIFEST"),
        1,
        "lifecycle=? AND retirementId=? AND sha256=? AND byteLength=? AND chunkCount=? AND"
            + " abandonAfter=?",
        s("RETIRED"),
        s(retirement.toString()),
        s(stage.manifest().sha256()),
        n(stage.manifest().byteLength()),
        n(stage.manifest().chunkCount()),
        n(stage.abandonAfter().getEpochSecond()));
  }

  private Map<String, AttributeValue> manifest(StudyPlanDocumentStore.DocumentId id) {
    return DynamoItems.strongGet(
        client, tables.userStudyPlanDetailsTableName(), id.pk(), "MANIFEST");
  }

  private static SdkBytes tombstone(StudyPlanDocumentStore.DocumentId id) {
    return SdkBytes.fromUtf8String(
        "{\"schemaVersion\":1,\"kind\":\"retired-study-plan-document\",\"ownerId\":\""
            + id.ownerId()
            + "\",\"documentId\":\""
            + id.documentId()
            + "\"}");
  }

  private static void validateCursor(String value) {
    if (value != null && (value.isBlank() || value.length() > 1024))
      throw new IllegalArgumentException("Invalid cleanup cursor");
  }

  private static UUID uuid(Map<String, AttributeValue> item, String field) {
    String value = string(item, field);
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value))
      throw new IllegalStateException("Noncanonical operation identity");
    return id;
  }

  private static String string(Map<String, AttributeValue> item, String field) {
    var value = item.get(field);
    if (value == null || value.s() == null) throw new IllegalStateException("Missing " + field);
    return value.s();
  }

  private static ParameterizedStatement statement(String sql, AttributeValue... values) {
    return ParameterizedStatement.builder().statement(sql).parameters(values).build();
  }
}
