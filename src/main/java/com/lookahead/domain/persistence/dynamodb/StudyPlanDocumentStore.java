package com.lookahead.domain.persistence.dynamodb;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Immutable document staging; no Spring registration or automatic maintenance execution. */
public final class StudyPlanDocumentStore {
    public record DocumentId(UUID ownerId, UUID documentId) {
        public DocumentId {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(documentId, "documentId");
        }
        String pk() { return "DOC#" + ownerId + "#" + documentId; }
    }

    public record Stage(DocumentId id, StudyPlanDocumentCodec.Manifest manifest, Instant abandonAfter) {
        public Stage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(abandonAfter, "abandonAfter");
            if (abandonAfter.isBefore(Instant.EPOCH) || abandonAfter.getEpochSecond() > 253402300799L
                    || abandonAfter.getNano() != 0) {
                throw new IllegalArgumentException("Stage deadline must be whole seconds in supported UTC range");
            }
        }
        String jobKey() { return "DUE#" + String.format(java.util.Locale.ROOT, "%012d", abandonAfter.getEpochSecond()) + "#" + id.pk(); }
    }

    /** Only obtained after reading and verifying every immutable chunk. */
    static final class VerifiedDocument {
        private final Stage stage;
        private final UUID operationId;
        private final boolean replayOnly;
        private VerifiedDocument(Stage stage, UUID operationId, boolean replayOnly) {
            this.stage = stage; this.operationId = operationId; this.replayOnly = replayOnly;
        }

        boolean replayOnly() { return replayOnly; }

        String fingerprintScope() { return "document-publication/v1|" + stage.abandonAfter().getEpochSecond(); }

        List<ParameterizedStatement> publicationStatements(DynamoTableNames tables) {
            return List.of(statement("UPDATE \"" + tables.userStudyPlanDetailsTableName()
                    + "\" SET lifecycle=? SET publicationId=? WHERE pk=? AND sk=? AND lifecycle=?"
                    + " AND schemaVersion=1 AND sha256=? AND byteLength=? AND chunkCount=? AND abandonAfter=?",
                    s("COMMITTED"), s(operationId.toString()), s(stage.id().pk()), s("MANIFEST"), s("STAGING"),
                    s(stage.manifest().sha256()), n(stage.manifest().byteLength()), n(stage.manifest().chunkCount()),
                    n(stage.abandonAfter().getEpochSecond())),
                    statement("DELETE FROM \"" + tables.userAccountDetailsTableName()
                            + "\" WHERE pk=? AND sk=? AND documentPk=? AND schemaVersion=1",
                            s("WORK#DOCUMENT#00"), s(stage.jobKey()), s(stage.id().pk())));
        }
    }

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;
    private final StudyPlanDocumentCodec codec = new StudyPlanDocumentCodec();

    public StudyPlanDocumentStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Stage stage(DocumentId id, byte[] document, Instant abandonAfter) {
        var encoded = codec.encode(document);
        var stage = new Stage(id, encoded.manifest(), abandonAfter);
        begin(stage);
        // A committed retry does not rewrite chunks. Publication replay checks its durable receipt.
        if ("COMMITTED".equals(lifecycle(manifest(id)))) return stage;
        for (int i = 0; i < encoded.chunks().size(); i++) writeChunk(stage, i, encoded.chunks().get(i));
        return stage;
    }

    /** Manifest and abandonment job are created atomically, including for interrupted uploads. */
    public void begin(Stage stage) {
        var existing = manifest(stage.id());
        if (!existing.isEmpty()) { validateReusable(stage, existing); return; }
        requireUnexpired(stage);
        var item = new java.util.HashMap<>(key(stage.id().pk(), "MANIFEST"));
        item.putAll(Map.of("schemaVersion", n(1), "lifecycle", s("STAGING"),
                "byteLength", n(stage.manifest().byteLength()), "chunkCount", n(stage.manifest().chunkCount()),
                "sha256", s(stage.manifest().sha256()), "abandonAfter", n(stage.abandonAfter().getEpochSecond())));
        var job = new java.util.HashMap<>(key("WORK#DOCUMENT#00", stage.jobKey()));
        job.putAll(Map.of("schemaVersion", n(1), "documentPk", s(stage.id().pk())));
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                    TransactWriteItem.builder().put(Put.builder().tableName(tables.userStudyPlanDetailsTableName())
                            .item(item).conditionExpression("attribute_not_exists(pk)").build()).build(),
                    TransactWriteItem.builder().put(Put.builder().tableName(tables.userAccountDetailsTableName())
                            .item(job).conditionExpression("attribute_not_exists(pk)").build()).build()).build());
        } catch (SdkException failure) {
            try {
                var recovered = manifest(stage.id());
                if (!recovered.isEmpty()) { validateReusable(stage, recovered); return; }
            } catch (RuntimeException readFailure) { failure.addSuppressed(readFailure); }
            throw failure;
        }
    }

    public void writeChunk(Stage stage, int index, SdkBytes payload) {
        requireUnexpired(stage);
        if (index < 0 || index >= stage.manifest().chunkCount()) throw new IllegalArgumentException("Invalid chunk index");
        byte[] bytes = Objects.requireNonNull(payload, "payload").asByteArray();
        int length = Math.min(StudyPlanDocumentCodec.CHUNK_BYTES,
                stage.manifest().byteLength() - index * StudyPlanDocumentCodec.CHUNK_BYTES);
        if (bytes.length != length) throw new IllegalArgumentException("Invalid chunk length");
        var item = new java.util.HashMap<>(key(stage.id().pk(), chunkKey(index)));
        var binary = AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build();
        item.putAll(Map.of("schemaVersion", n(1), "payload", binary));
        client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                fence(stage, "STAGING"), TransactWriteItem.builder().put(Put.builder()
                        .tableName(tables.userStudyPlanDetailsTableName()).item(item)
                        .conditionExpression("attribute_not_exists(pk) OR (schemaVersion = :version AND payload = :payload)")
                        .expressionAttributeValues(Map.of(":version", n(1), ":payload", binary)).build()).build()).build());
    }

    /** Publishes a typed pointer, audit, receipt, manifest and job removal in one transaction. */
    public AuditedAccountStore.CommitResult publish(Stage stage, String recordId, UUID operationId,
            long expectedRevision, AuditedAccountStore.AuditEvent audit) {
        Objects.requireNonNull(operationId, "operationId");
        var current = manifest(stage.id());
        validateReusable(stage, current);
        if ("COMMITTED".equals(lifecycle(current)) && !s(operationId.toString()).equals(current.get("publicationId"))) {
            throw new IllegalStateException("Document already belongs to another publication");
        }
        readChunks(stage); // All chunks must match the immutable manifest before pointer publication.
        if ("STAGING".equals(lifecycle(current))) requireUnexpired(stage);
        var mutation = new AuditedAccountStore.Mutation(stage.id().ownerId(), recordId, operationId,
                expectedRevision, pointer(stage), audit);
        return new AuditedAccountStore(client, tables).commitDocument(mutation, new VerifiedDocument(stage, operationId, "COMMITTED".equals(lifecycle(current))));
    }

    static SdkBytes pointer(Stage stage) {
        return SdkBytes.fromUtf8String("{\"schemaVersion\":1,\"kind\":\"study-plan-document\",\"ownerId\":\""
                + stage.id().ownerId() + "\",\"documentId\":\"" + stage.id().documentId()
                + "\",\"byteLength\":" + stage.manifest().byteLength() + ",\"sha256\":\""
                + stage.manifest().sha256() + "\"}");
    }

    /** Caller must first authorize the plan and check its current lifecycle/deletion fence. */
    public byte[] readPublished(DocumentId id) {
        var item = manifest(id);
        if (!"COMMITTED".equals(lifecycle(item))) throw new IllegalStateException("Document is not published");
        return readChunks(parse(id, item));
    }

    /** Bounded, resumable cleanup of uncommitted expired data only; retains a resurrection fence. */
    public boolean cleanAbandoned(DocumentId id) {
        var item = manifest(id);
        if (item.isEmpty()) return false;
        Stage stage = parse(id, item);
        String state = lifecycle(item);
        if ("COMMITTED".equals(state) || "RETIRED".equals(state)) return false;
        if ("STAGING".equals(state)) {
            if (clock.instant().isBefore(stage.abandonAfter())) return false;
            client.updateItem(UpdateItemRequest.builder().tableName(tables.userStudyPlanDetailsTableName())
                    .key(key(id.pk(), "MANIFEST")).updateExpression("SET lifecycle = :deleting")
                    .conditionExpression("lifecycle = :staging AND abandonAfter = :deadline AND schemaVersion = :version")
                    .expressionAttributeValues(Map.of(":deleting", s("DELETING"), ":staging", s("STAGING"),
                            ":deadline", n(stage.abandonAfter().getEpochSecond()), ":version", n(1))).build());
        }
        // At most 35 chunks, one fenced transaction per chunk: maximum 240 KiB deleted per transaction.
        // Restarting at zero is safe; no cursor/lease or unbounded scan is needed for this primitive.
        for (int i = 0; i < stage.manifest().chunkCount(); i++) {
            client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(fence(stage, "DELETING"),
                    TransactWriteItem.builder().delete(Delete.builder().tableName(tables.userStudyPlanDetailsTableName())
                            .key(key(id.pk(), chunkKey(i))).build()).build()).build());
        }
        client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(fence(stage, "DELETING"),
                TransactWriteItem.builder().delete(Delete.builder().tableName(tables.userAccountDetailsTableName())
                        .key(key("WORK#DOCUMENT#00", stage.jobKey())).build()).build()).build());
        return true;
    }

    private byte[] readChunks(Stage stage) {
        var chunks = new ArrayList<SdkBytes>();
        for (int i = 0; i < stage.manifest().chunkCount(); i++) {
            var item = get(tables.userStudyPlanDetailsTableName(), stage.id().pk(), chunkKey(i));
            var payload = item.get("payload");
            if (!n(1).equals(item.get("schemaVersion")) || payload == null || payload.b() == null) {
                throw new IllegalStateException("Missing or malformed document chunk");
            }
            chunks.add(payload.b());
        }
        return codec.decode(stage.manifest(), chunks);
    }

    private void validateReusable(Stage expected, Map<String, AttributeValue> item) {
        if (!expected.equals(parse(expected.id(), item))) throw new IllegalStateException("Document ID reused with different content or deadline");
        String state = lifecycle(item);
        if ("DELETING".equals(state) || "RETIRED".equals(state)) throw new IllegalStateException("Document is being deleted");
        if ("STAGING".equals(state)) requireUnexpired(expected);
    }

    static Stage parse(DocumentId id, Map<String, AttributeValue> item) {
        lifecycle(item);
        try {
            return new Stage(id, new StudyPlanDocumentCodec.Manifest(1,
                    Integer.parseInt(item.get("byteLength").n()), Integer.parseInt(item.get("chunkCount").n()),
                    item.get("sha256").s()), Instant.ofEpochSecond(Long.parseLong(item.get("abandonAfter").n())));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new IllegalStateException("Invalid document manifest", malformed);
        }
    }

    static String lifecycle(Map<String, AttributeValue> item) {
        if (!n(1).equals(item.get("schemaVersion")) || !item.containsKey("lifecycle")) {
            throw new IllegalStateException("Missing or unsupported document manifest");
        }
        String state = item.get("lifecycle").s();
        if (state == null || !List.of("STAGING", "COMMITTED", "DELETING", "RETIRED").contains(state)) {
            throw new IllegalStateException("Invalid document lifecycle");
        }
        return state;
    }

    private void requireUnexpired(Stage stage) {
        if (!clock.instant().isBefore(stage.abandonAfter())) throw new IllegalStateException("Document staging deadline expired");
    }

    private TransactWriteItem fence(Stage stage, String state) {
        return TransactWriteItem.builder().conditionCheck(ConditionCheck.builder()
                .tableName(tables.userStudyPlanDetailsTableName()).key(key(stage.id().pk(), "MANIFEST"))
                .conditionExpression("lifecycle = :state AND schemaVersion = :version AND sha256 = :hash"
                        + " AND abandonAfter = :deadline AND byteLength = :bytes AND chunkCount = :chunks")
                .expressionAttributeValues(Map.of(":state", s(state), ":version", n(1), ":hash", s(stage.manifest().sha256()),
                        ":deadline", n(stage.abandonAfter().getEpochSecond()), ":bytes", n(stage.manifest().byteLength()),
                        ":chunks", n(stage.manifest().chunkCount()))).build()).build();
    }
    private Map<String, AttributeValue> manifest(DocumentId id) { return get(tables.userStudyPlanDetailsTableName(), id.pk(), "MANIFEST"); }
    private Map<String, AttributeValue> get(String table, String pk, String sk) {
        return client.getItem(GetItemRequest.builder().tableName(table).key(key(pk, sk)).consistentRead(true).build()).item();
    }
    private static String chunkKey(int index) { return String.format(java.util.Locale.ROOT, "CHUNK#%05d", index); }
    private static Map<String, AttributeValue> key(String pk, String sk) { return Map.of("pk", s(pk), "sk", s(sk)); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static ParameterizedStatement statement(String sql, AttributeValue... values) {
        return ParameterizedStatement.builder().statement(sql).parameters(values).build();
    }
}
