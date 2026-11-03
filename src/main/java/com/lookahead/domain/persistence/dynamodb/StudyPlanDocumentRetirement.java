package com.lookahead.domain.persistence.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** One published snapshot's audited retirement and bounded cleanup; not a whole-plan deletion API. */
public final class StudyPlanDocumentRetirement {
    private static final String PARTITION = "WORK#RETIRED-DOCUMENT#00";
    public static final int MAX_JOBS = 25;
    public enum Outcome { CLEANED, RETAINED, FAILED }
    public record JobResult(String key, Outcome outcome) {}
    public record Page(List<JobResult> jobs, String afterKey) {
        public Page { jobs = List.copyOf(jobs); }
    }

    /** Only the validated retirement path can mint the additional transaction statements. */
    static final class VerifiedRetirement {
        private final StudyPlanDocumentStore.Stage stage;
        private final UUID publicationId;
        private final UUID retirementId;
        private final String recordId;
        private final long expectedRevision;
        private final boolean replayOnly;

        private VerifiedRetirement(StudyPlanDocumentStore.Stage stage, UUID publicationId,
                UUID retirementId, String recordId, long expectedRevision, boolean replayOnly) {
            this.stage = stage;
            this.publicationId = publicationId;
            this.retirementId = retirementId;
            this.recordId = recordId;
            this.expectedRevision = expectedRevision;
            this.replayOnly = replayOnly;
        }

        boolean replayOnly() { return replayOnly; }

        String fingerprintScope() {
            return "document-retirement/v1|" + publicationId + "|" + stage.manifest().sha256()
                    + "|" + stage.manifest().byteLength() + "|" + stage.manifest().chunkCount()
                    + "|" + stage.abandonAfter().getEpochSecond();
        }

        List<ParameterizedStatement> statements(DynamoTableNames tables) {
            return List.of(statement("UPDATE \"" + tables.userStudyPlanDetailsTableName()
                    + "\" SET lifecycle=? SET retirementId=? SET retirementRecordId=? SET retirementExpectedRevision=?"
                    + " WHERE pk=? AND sk=? AND schemaVersion=1 AND lifecycle=? AND publicationId=?"
                    + " AND sha256=? AND byteLength=? AND chunkCount=? AND abandonAfter=?",
                    s("RETIRED"), s(retirementId.toString()), s(recordId), n(expectedRevision),
                    s(stage.id().pk()), s("MANIFEST"), s("COMMITTED"), s(publicationId.toString()),
                    s(stage.manifest().sha256()), n(stage.manifest().byteLength()), n(stage.manifest().chunkCount()),
                    n(stage.abandonAfter().getEpochSecond())),
                    statement("INSERT INTO \"" + tables.userAccountDetailsTableName()
                    + "\" VALUE {'pk': ?, 'sk': ?, 'schemaVersion': 1, 'documentPk': ?, 'retirementId': ?}",
                    s(PARTITION), s(stage.id().pk()), s(stage.id().pk()), s(retirementId.toString())));
        }
    }

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final AuditedAccountStore accounts;

    public StudyPlanDocumentRetirement(DynamoDbClient client, DynamoTableNames tables) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.accounts = new AuditedAccountStore(client, tables);
    }

    /** Caller authorizes the owner/plan. Retire only the exact currently referenced document/revision. */
    public AuditedAccountStore.CommitResult retire(StudyPlanDocumentStore.Stage stage, String recordId,
            UUID operationId, long expectedRevision, AuditedAccountStore.AuditEvent audit) {
        Objects.requireNonNull(stage, "stage");
        if (expectedRevision < 1) throw new IllegalArgumentException("Retirement requires an existing revision");
        var mutation = new AuditedAccountStore.Mutation(stage.id().ownerId(), recordId, operationId,
                expectedRevision, tombstone(stage.id()), audit);
        var item = manifest(stage.id());
        if (!stage.equals(StudyPlanDocumentStore.parse(stage.id(), item))) {
            throw new IllegalStateException("Retirement document does not match its manifest");
        }
        String state = StudyPlanDocumentStore.lifecycle(item);
        UUID publication = uuid(item, "publicationId");
        boolean replayOnly = "RETIRED".equals(state);
        if (replayOnly) {
            requireSameRetirement(item, operationId, recordId, expectedRevision);
        } else {
            if (!"COMMITTED".equals(state)) throw new IllegalStateException("Only published documents can be retired");
            var record = accounts.read(stage.id().ownerId(), recordId)
                    .orElseThrow(() -> new IllegalStateException("Document reference is missing"));
            if (record.revision() != expectedRevision || !record.payload().equals(StudyPlanDocumentStore.pointer(stage))) {
                // Another identical caller may have retired between the manifest and account reads.
                var recovered = manifest(stage.id());
                if (!stage.equals(StudyPlanDocumentStore.parse(stage.id(), recovered))
                        || !"RETIRED".equals(StudyPlanDocumentStore.lifecycle(recovered))
                        || !publication.equals(uuid(recovered, "publicationId"))) {
                    throw new IllegalStateException("Document is not the current account reference at this revision");
                }
                requireSameRetirement(recovered, operationId, recordId, expectedRevision);
                replayOnly = true;
            }
        }
        return accounts.retireDocument(mutation,
                new VerifiedRetirement(stage, publication, operationId, recordId, expectedRevision, replayOnly));
    }

    private static void requireSameRetirement(Map<String, AttributeValue> item, UUID operationId,
            String recordId, long expectedRevision) {
        if (!operationId.equals(uuid(item, "retirementId")) || !s(recordId).equals(item.get("retirementRecordId"))
                || !n(expectedRevision).equals(item.get("retirementExpectedRevision"))) {
            throw new IllegalStateException("Document was retired by a different operation");
        }
    }

    /** Deletes at most 35 chunks. The RETIRED manifest and account tombstone are retained. */
    public boolean cleanRetired(StudyPlanDocumentStore.DocumentId id) {
        var item = manifest(id);
        if (item.isEmpty()) return false;
        var stage = StudyPlanDocumentStore.parse(id, item);
        if (!"RETIRED".equals(StudyPlanDocumentStore.lifecycle(item))) return false;
        UUID retirement = uuid(item, "retirementId");
        for (int i = 0; i < stage.manifest().chunkCount(); i++) {
            String chunkKey = String.format(java.util.Locale.ROOT, "CHUNK#%05d", i);
            client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(fence(stage, retirement),
                    TransactWriteItem.builder().delete(Delete.builder().tableName(tables.userStudyPlanDetailsTableName())
                            .key(key(id.pk(), chunkKey)).build()).build()).build());
        }
        client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(fence(stage, retirement),
                TransactWriteItem.builder().delete(Delete.builder().tableName(tables.userAccountDetailsTableName())
                        .key(key(PARTITION, id.pk())).conditionExpression("attribute_not_exists(pk) OR (schemaVersion=:schema AND retirementId=:retirement)")
                        .expressionAttributeValues(Map.of(":schema", n(1), ":retirement", s(retirement.toString()))).build()).build()).build());
        return true;
    }

    /** One strong Query page, no scan or scheduler. Start a fresh sweep to revisit jobs behind the cursor. */
    public Page cleanPage(int limit, String afterKey) {
        if (limit < 1 || limit > MAX_JOBS) throw new IllegalArgumentException("Cleanup limit must be 1–25");
        validateCursor(afterKey);
        var request = QueryRequest.builder().tableName(tables.userAccountDetailsTableName())
                .keyConditionExpression("pk=:pk").expressionAttributeValues(Map.of(":pk", s(PARTITION)))
                .consistentRead(true).scanIndexForward(true).limit(limit);
        if (afterKey != null) request.exclusiveStartKey(key(PARTITION, afterKey));
        var response = client.query(request.build());
        if (response.items().size() > limit) throw new IllegalStateException("Cleanup page exceeded its bound");
        String next = null;
        if (!response.lastEvaluatedKey().isEmpty()) {
            var last = response.lastEvaluatedKey();
            next = string(last, "sk");
            validateCursor(next);
            if (last.size() != 2 || !s(PARTITION).equals(last.get("pk"))
                    || (afterKey != null && next.compareTo(afterKey) <= 0)) {
                throw new IllegalStateException("Invalid cleanup continuation");
            }
        }
        var results = new ArrayList<JobResult>();
        for (var job : response.items()) {
            String label = job.get("sk") == null ? "invalid-job" : job.get("sk").s();
            try {
                String documentPk = string(job, "documentPk");
                if (!s(PARTITION).equals(job.get("pk")) || !n(1).equals(job.get("schemaVersion"))
                        || !s(documentPk).equals(job.get("sk"))) throw new IllegalStateException("Invalid cleanup job");
                var id = documentId(documentPk);
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

    private TransactWriteItem fence(StudyPlanDocumentStore.Stage stage, UUID retirement) {
        return TransactWriteItem.builder().conditionCheck(ConditionCheck.builder()
                .tableName(tables.userStudyPlanDetailsTableName()).key(key(stage.id().pk(), "MANIFEST"))
                .conditionExpression("schemaVersion=:schema AND lifecycle=:state AND retirementId=:retirement"
                        + " AND sha256=:sha AND byteLength=:length AND chunkCount=:chunks AND abandonAfter=:deadline")
                .expressionAttributeValues(Map.of(":schema", n(1), ":state", s("RETIRED"), ":retirement", s(retirement.toString()),
                        ":sha", s(stage.manifest().sha256()), ":length", n(stage.manifest().byteLength()),
                        ":chunks", n(stage.manifest().chunkCount()), ":deadline", n(stage.abandonAfter().getEpochSecond()))).build()).build();
    }

    private Map<String, AttributeValue> manifest(StudyPlanDocumentStore.DocumentId id) {
        return client.getItem(GetItemRequest.builder().tableName(tables.userStudyPlanDetailsTableName())
                .key(key(id.pk(), "MANIFEST")).consistentRead(true).build()).item();
    }
    private static SdkBytes tombstone(StudyPlanDocumentStore.DocumentId id) {
        return SdkBytes.fromUtf8String("{\"schemaVersion\":1,\"kind\":\"retired-study-plan-document\",\"ownerId\":\""
                + id.ownerId() + "\",\"documentId\":\"" + id.documentId() + "\"}");
    }
    private static void validateCursor(String value) {
        if (value != null && (value.isBlank() || value.length() > 1024)) throw new IllegalArgumentException("Invalid cleanup cursor");
    }
    private static StudyPlanDocumentStore.DocumentId documentId(String value) {
        String[] parts = value.split("#", -1);
        if (parts.length != 3 || !parts[0].equals("DOC")) throw new IllegalStateException("Invalid document key");
        var id = new StudyPlanDocumentStore.DocumentId(UUID.fromString(parts[1]), UUID.fromString(parts[2]));
        if (!id.pk().equals(value)) throw new IllegalStateException("Noncanonical document key");
        return id;
    }
    private static UUID uuid(Map<String, AttributeValue> item, String field) {
        String value = string(item, field);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalStateException("Noncanonical operation identity");
        return id;
    }
    private static String string(Map<String, AttributeValue> item, String field) {
        var value = item.get(field);
        if (value == null || value.s() == null) throw new IllegalStateException("Missing " + field);
        return value.s();
    }
    private static Map<String, AttributeValue> key(String pk, String sk) { return Map.of("pk", s(pk), "sk", s(sk)); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static ParameterizedStatement statement(String sql, AttributeValue... values) {
        return ParameterizedStatement.builder().statement(sql).parameters(values).build();
    }
}
