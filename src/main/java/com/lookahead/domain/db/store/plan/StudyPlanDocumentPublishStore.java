package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: large plan payloads (version snapshots, replay responses above the inline budget) go through the chunked
 * {@link StudyPlanDocumentStore}: staged and published before the metadata transaction, which then only checks that the
 * manifest is COMMITTED with the expected hash. Operation IDs derive from the owning key, so a retried mutation
 * replays the same publication instead of publishing twice. Retirement is the audited, resumable physical purge.
 */
final class StudyPlanDocumentPublishStore {
    static final long STAGE_SECONDS = 3600;

    /** What an item keeps about a published document; enough to read it and to retire it later. */
    record Reference(UUID documentId, int byteLength, int chunkCount, String sha256, long abandonAfter) {
        Map<String, AttributeValue> attributes() {
            return Map.of("documentId", DynamoItems.s(documentId.toString()), "byteLength", DynamoItems.n(byteLength),
                    "chunkCount", DynamoItems.n(chunkCount), "sha256", DynamoItems.s(sha256), "abandonAfter", DynamoItems.n(abandonAfter));
        }
        static Reference from(Map<String, AttributeValue> item) {
            if (item.get("documentId") == null || item.get("documentId").s() == null) return null;
            return new Reference(DynamoItems.uuid(item, "documentId"), (int) DynamoItems.number(item, "byteLength"),
                    (int) DynamoItems.number(item, "chunkCount"), DynamoItems.string(item, "sha256"), DynamoItems.number(item, "abandonAfter"));
        }
        StudyPlanDocumentStore.Stage stage(UUID owner) {
            return new StudyPlanDocumentStore.Stage(new StudyPlanDocumentStore.DocumentId(owner, documentId),
                    new StudyPlanDocumentCodec.Manifest(1, byteLength, chunkCount, sha256), Instant.ofEpochSecond(abandonAfter));
        }
        String pk(UUID owner) { return StudyPlanDocumentStore.DocumentId.pk(owner, documentId); }
    }

    private final DynamoTableNames tables;
    private final Clock clock;
    private final StudyPlanDocumentStore store;
    private final StudyPlanDocumentRetirementStore retirement;

    StudyPlanDocumentPublishStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.tables = tables; this.clock = clock;
        this.store = new StudyPlanDocumentStore(client, tables, clock);
        this.retirement = new StudyPlanDocumentRetirementStore(client, tables);
    }

    static UUID operation(String scope, UUID owner, String key) {
        return UUID.nameUUIDFromBytes((scope + ":" + owner + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stage all chunks, then publish pointer + audit + receipt + manifest; idempotent for the same document and key.
     * The reference carries the stage the store answered with: for a document already published by an earlier
     * attempt that is the original deadline, which the manifest and the publication receipt are fenced on.
     */
    Reference publish(UUID owner, UUID documentId, byte[] bytes, String recordId, String operationScope, String action, String reason) {
        Instant now = clock.instant();
        var id = new StudyPlanDocumentStore.DocumentId(owner, documentId);
        var stage = store.stage(id, bytes, Instant.ofEpochSecond(now.getEpochSecond() + STAGE_SECONDS));
        var audit = new AccountAuditedStore.AuditEvent("account:" + owner, action, reason, now);
        store.publish(stage, recordId, operation(operationScope, owner, documentId.toString()), 0, audit);
        return new Reference(documentId, stage.manifest().byteLength(), stage.manifest().chunkCount(), stage.manifest().sha256(), stage.abandonAfter().getEpochSecond());
    }

    byte[] read(UUID owner, Reference reference) {
        return store.readPublished(new StudyPlanDocumentStore.DocumentId(owner, reference.documentId()));
    }

    /** The metadata transaction never references a document that is not COMMITTED with the expected content. */
    void check(DynamoWrites tx, UUID owner, Reference reference) {
        tx.check(tables.userStudyPlanDetailsTableName(), DynamoItems.key(reference.pk(owner), "MANIFEST"), 1,
                "lifecycle=? AND sha256=? AND byteLength=?", DynamoItems.s("COMMITTED"), DynamoItems.s(reference.sha256()), DynamoItems.n(reference.byteLength()));
    }

    /** Audited retirement of a published document and bounded chunk cleanup; a document already gone is not an error. */
    boolean retire(UUID owner, Reference reference, String recordId, String operationScope, String reason) {
        var id = new StudyPlanDocumentStore.DocumentId(owner, reference.documentId());
        try {
            retirement.retire(reference.stage(owner), recordId, operation(operationScope, owner, reference.documentId().toString()), 1,
                    new AccountAuditedStore.AuditEvent("account:" + owner, "plan.document.retire", reason, clock.instant()));
        } catch (IllegalStateException alreadyGone) {
            // Missing manifest (purged earlier) or a RETIRED manifest of the same operation (replay): nothing to retire.
            if (!alreadyGone.getMessage().startsWith("Missing or unsupported document manifest")
                    && !alreadyGone.getMessage().startsWith("Document was retired")) throw alreadyGone;
        }
        return retirement.cleanRetired(id);
    }
}
