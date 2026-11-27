package com.lookahead.domain.db.store.plan;

import static com.lookahead.domain.db.store.dynamodb.DynamoItems.s;
import static com.lookahead.domain.db.store.dynamodb.DynamoItems.n;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ParameterizedStatement;

/**
 * Chunked study-plan documents in User Study Plan Details: a {@code DOC#owner#id / MANIFEST} item, up to 35
 * immutable {@code CHUNK#n} items of 240 KiB and, while staging, an abandonment job in User Account Details.
 * {@code StudyPlanDocumentPublishStore} stages and publishes every plan payload above the inline budget through it; the
 * publication itself is one {@link AccountAuditedStore} transaction that also flips the manifest to COMMITTED
 * and removes the job. Every write is PartiQL through {@link DynamoWrites} (the runtime role has no native
 * write permission), so a lost condition is {@link DynamoWrites.Conflict} and an unknown outcome
 * {@link DynamoWrites.Unavailable}. Cleanup of abandoned uploads is driven by {@link StudyPlanDocumentCleanupStore}.
 */
public final class StudyPlanDocumentStore {
    public record DocumentId(UUID ownerId, UUID documentId) {
        public DocumentId {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(documentId, "documentId");
        }
        String pk() { return pk(ownerId, documentId); }
        /** The one place the partition key of a document's items is written (DLV-1016). */
        static String pk(UUID ownerId, UUID documentId) { return "DOC#" + ownerId + "#" + documentId; }
        /** The identity a stored partition key names; a key that would not be written back the same way is refused. */
        static DocumentId parse(String pk) {
            String[] parts = pk.split("#", -1);
            if (parts.length != 3 || !parts[0].equals("DOC")) throw new IllegalStateException("Invalid document key");
            var id = new DocumentId(UUID.fromString(parts[1]), UUID.fromString(parts[2]));
            if (!id.pk().equals(pk)) throw new IllegalStateException("Noncanonical document key");
            return id;
        }
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
        String jobKey() { return "DUE#" + String.format(Locale.ROOT, "%012d", abandonAfter.getEpochSecond()) + "#" + id.pk(); }
    }

    /** Only obtained after reading and verifying every immutable chunk. */
    static final class VerifiedDocument {
        private final Stage stage;
        private final UUID operationId;
        private final Instant publishedAt;
        private final boolean replayOnly;
        private VerifiedDocument(Stage stage, UUID operationId, Instant publishedAt, boolean replayOnly) {
            this.stage = stage; this.operationId = operationId; this.publishedAt = publishedAt; this.replayOnly = replayOnly;
        }

        boolean replayOnly() { return replayOnly; }

        String fingerprintScope() { return "document-publication/v1|" + stage.abandonAfter().getEpochSecond(); }

        List<ParameterizedStatement> publicationStatements(DynamoTableNames tables) {
            return List.of(statement("UPDATE \"" + tables.userStudyPlanDetailsTableName()
                    + "\" SET lifecycle=? SET publicationId=? SET publishedAt=? WHERE pk=? AND sk=? AND lifecycle=?"
                    + " AND schemaVersion=1 AND sha256=? AND byteLength=? AND chunkCount=? AND abandonAfter=?",
                    s("COMMITTED"), s(operationId.toString()), s(publishedAt.toString()), s(stage.id().pk()), s("MANIFEST"), s("STAGING"),
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

    /**
     * Stages the document and answers the stage to publish with. For a document already COMMITTED with the same
     * content that is the stored stage (its original deadline), whatever deadline this call carried: a retried
     * mutation whose document was published before its own transaction failed replays the publication instead of
     * being refused for ever.
     */
    public Stage stage(DocumentId id, byte[] document, Instant abandonAfter) {
        var encoded = codec.encode(document);
        var stage = begin(new Stage(id, encoded.manifest(), abandonAfter));
        // A committed retry does not rewrite chunks. Publication replay checks its durable receipt.
        if ("COMMITTED".equals(lifecycle(manifest(id)))) return stage;
        for (int i = 0; i < encoded.chunks().size(); i++) writeChunk(stage, i, encoded.chunks().get(i));
        return stage;
    }

    /**
     * Manifest and abandonment job are created atomically, including for interrupted uploads. Answers the stage to
     * continue with: the given one, or the stored one for a committed document with the same content.
     */
    public Stage begin(Stage stage) {
        var existing = manifest(stage.id());
        if (!existing.isEmpty()) return reusable(stage, existing);
        requireUnexpired(stage);
        var manifest = DynamoItems.item(stage.id().pk(), "MANIFEST", 1, Map.of("lifecycle", s("STAGING"),
                "byteLength", n(stage.manifest().byteLength()), "chunkCount", n(stage.manifest().chunkCount()),
                "sha256", s(stage.manifest().sha256()), "abandonAfter", n(stage.abandonAfter().getEpochSecond())));
        var job = DynamoItems.item("WORK#DOCUMENT#00", stage.jobKey(), 1, Map.of("documentPk", s(stage.id().pk())));
        try {
            DynamoWrites.transaction(client).insertOnly(tables.userStudyPlanDetailsTableName(), manifest)
                    .insertOnly(tables.userAccountDetailsTableName(), job).commit();
        } catch (DynamoWrites.Conflict | DynamoWrites.Unavailable failure) {
            // A duplicate key means another identical attempt (or this one, with a lost reply) created it first.
            try {
                var recovered = manifest(stage.id());
                if (!recovered.isEmpty()) return reusable(stage, recovered);
            } catch (RuntimeException readFailure) { failure.addSuppressed(readFailure); }
            throw failure;
        }
        return stage;
    }

    /**
     * Writes one chunk under the STAGING manifest fence. A chunk already stored with the same bytes is accepted
     * (the fence alone is checked); different bytes under the same key are a {@link DynamoWrites.Conflict}, since
     * chunks are immutable.
     */
    public void writeChunk(Stage stage, int index, SdkBytes payload) {
        requireUnexpired(stage);
        if (index < 0 || index >= stage.manifest().chunkCount()) throw new IllegalArgumentException("Invalid chunk index");
        byte[] bytes = Objects.requireNonNull(payload, "payload").asByteArray();
        int length = Math.min(StudyPlanDocumentCodec.CHUNK_BYTES,
                stage.manifest().byteLength() - index * StudyPlanDocumentCodec.CHUNK_BYTES);
        if (bytes.length != length) throw new IllegalArgumentException("Invalid chunk length");
        var existing = get(tables.userStudyPlanDetailsTableName(), stage.id().pk(), chunkKey(index));
        var tx = fence(DynamoWrites.transaction(client), stage, "STAGING");
        if (existing.isEmpty()) {
            tx.insertOnly(tables.userStudyPlanDetailsTableName(), DynamoItems.item(stage.id().pk(), chunkKey(index), 1,
                    Map.of("payload", AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build())));
        } else {
            var stored = existing.get("payload");
            if (!n(1).equals(existing.get("schemaVersion")) || stored == null || stored.b() == null
                    || !Arrays.equals(stored.b().asByteArray(), bytes)) {
                throw conflict(List.of("None", "ConditionalCheckFailed"));
            }
        }
        tx.commit();
    }

    /**
     * Publishes a typed pointer, audit, receipt, manifest and job removal in one transaction. A document already
     * COMMITTED by this operation is a replay: nothing is written, the durable receipt is the answer, and it is
     * looked up under the stage and audit time of the first attempt (both stored on the manifest), whatever a
     * later retry carries.
     */
    public AccountAuditedStore.CommitResult publish(Stage stage, String recordId, UUID operationId,
            long expectedRevision, AccountAuditedStore.AuditEvent audit) {
        Objects.requireNonNull(operationId, "operationId");
        var current = manifest(stage.id());
        stage = reusable(stage, current);
        boolean committed = "COMMITTED".equals(lifecycle(current));
        if (committed && !s(operationId.toString()).equals(current.get("publicationId"))) {
            throw new IllegalStateException("Document already belongs to another publication");
        }
        readChunks(stage); // All chunks must match the immutable manifest before pointer publication.
        if (!committed) requireUnexpired(stage);
        if (committed) audit = audited(audit, current, "publishedAt");
        var mutation = new AccountAuditedStore.Mutation(stage.id().ownerId(), recordId, operationId,
                expectedRevision, pointer(stage), audit);
        return new AccountAuditedStore(client, tables).commitDocument(mutation, new VerifiedDocument(stage, operationId, audit.occurredAt(), committed));
    }

    /**
     * The audit event of a replay, at the time the manifest recorded for the original operation, so the receipt
     * fingerprint is the first attempt's. A manifest without the attribute (written before it existed) keeps the
     * caller's time; a malformed one fails closed.
     */
    static AccountAuditedStore.AuditEvent audited(AccountAuditedStore.AuditEvent audit, Map<String, AttributeValue> manifest, String name) {
        var stored = manifest.get(name);
        if (stored == null || stored.s() == null) return audit;
        try { return new AccountAuditedStore.AuditEvent(audit.actor(), audit.action(), audit.reason(), Instant.parse(stored.s())); }
        catch (RuntimeException malformed) { throw new IllegalStateException("Invalid document manifest", malformed); }
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
            DynamoWrites.transaction(client).update(tables.userStudyPlanDetailsTableName(), DynamoItems.key(id.pk(), "MANIFEST"),
                    Map.of("lifecycle", s("DELETING")), "lifecycle=? AND abandonAfter=? AND schemaVersion=?",
                    s("STAGING"), n(stage.abandonAfter().getEpochSecond()), n(1)).commit();
        }
        // At most 35 chunks, one fenced transaction per chunk: maximum 240 KiB deleted per transaction.
        // Restarting at zero is safe; no cursor/lease or unbounded scan is needed for this primitive.
        for (int i = 0; i < stage.manifest().chunkCount(); i++) {
            fence(DynamoWrites.transaction(client), stage, "DELETING")
                    .delete(tables.userStudyPlanDetailsTableName(), DynamoItems.key(id.pk(), chunkKey(i)), null).commit();
        }
        fence(DynamoWrites.transaction(client), stage, "DELETING")
                .delete(tables.userAccountDetailsTableName(), DynamoItems.key("WORK#DOCUMENT#00", stage.jobKey()), null).commit();
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

    /**
     * The stage to continue with for an existing manifest. The content must be the same; the deadline only matters
     * while staging: a COMMITTED manifest is reusable whatever deadline the caller carries now, and the stored stage
     * (with the deadline the publication receipt was fingerprinted under) is the one to replay with.
     */
    private Stage reusable(Stage expected, Map<String, AttributeValue> item) {
        Stage stored = parse(expected.id(), item);
        if (!expected.manifest().equals(stored.manifest())) throw new IllegalStateException("Document ID reused with different content");
        String state = lifecycle(item);
        if ("DELETING".equals(state) || "RETIRED".equals(state)) throw new IllegalStateException("Document is being deleted");
        if ("COMMITTED".equals(state)) return stored;
        if (!expected.equals(stored)) throw new IllegalStateException("Document ID reused with a different deadline");
        requireUnexpired(expected);
        return expected;
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

    /** The manifest fence every chunk write and cleanup statement runs under: exact lifecycle, content and deadline. */
    private DynamoWrites fence(DynamoWrites tx, Stage stage, String state) {
        return tx.check(tables.userStudyPlanDetailsTableName(), DynamoItems.key(stage.id().pk(), "MANIFEST"), 1,
                "lifecycle=? AND sha256=? AND abandonAfter=? AND byteLength=? AND chunkCount=?",
                s(state), s(stage.manifest().sha256()), n(stage.abandonAfter().getEpochSecond()),
                n(stage.manifest().byteLength()), n(stage.manifest().chunkCount()));
    }
    /** A conflict found by reading (a differing immutable chunk) reported like a lost condition would be. */
    static DynamoWrites.Conflict conflict(List<String> reasons) { return new DynamoWrites.Conflict(reasons); }
    private Map<String, AttributeValue> manifest(DocumentId id) { return get(tables.userStudyPlanDetailsTableName(), id.pk(), "MANIFEST"); }
    private Map<String, AttributeValue> get(String table, String pk, String sk) {
        return DynamoItems.strongGet(client, table, pk, sk);
    }
    static String chunkKey(int index) { return String.format(Locale.ROOT, "CHUNK#%05d", index); }
    private static ParameterizedStatement statement(String sql, AttributeValue... values) {
        return ParameterizedStatement.builder().statement(sql).parameters(values).build();
    }
}
