package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.db.store.dynamodb.OwnerTransactionReads;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * DLV-970: the physical purge behind a plan deletion. The delete transaction only tombstones the plan and queues a
 * {@code WORK#PLAN-PURGE#00} job; this job pages through {@code PLAN#owner#id} (versions, activity, receipt
 * projections), retires every published document through {@link StudyPlanDocumentPublishStore}, marks pre-deletion receipts
 * deleted and drops their response bytes, and finally removes the job. Every step is idempotent, so a crash resumes
 * on the next sweep; the plan tombstone itself is never removed (it is what answers PLAN_DELETED to old receipts).
 * The sweep runs it as the {@code plan-purge} maintenance job ({@code job.StudyPlanPurgeJob}: one {@link #queued} page per sweep,
 * each deletion through {@link #purge}); this store registers nothing itself and writes no line.
 */
@Component
@Profile("accounts")
public class StudyPlanPurgeStore {
    /** The most queued deletions one page takes; also the page size of the {@code plan-purge} maintenance job. */
    public static final int MAX_JOBS = 25;

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final StudyPlanDocumentPublishStore documents;

    @Autowired
    public StudyPlanPurgeStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    StudyPlanPurgeStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = client; this.tables = tables; this.documents = new StudyPlanDocumentPublishStore(client, tables, clock);
    }

    /** One queued deletion, as its queue item holds it; only this store reads it. */
    public static final class Queued {
        private final Map<String, AttributeValue> item;
        Queued(Map<String, AttributeValue> item) { this.item = item; }
    }

    /** One strong page of queued deletions, in queue order. */
    public List<Queued> queued(int limit) {
        if (limit < 1 || limit > MAX_JOBS) throw new IllegalArgumentException("Purge limit must be 1–25");
        var response = StudyPlanStore.read(() -> client.query(QueryRequest.builder().tableName(tables.userAccountDetailsTableName())
                .consistentRead(true).keyConditionExpression("pk = :pk").expressionAttributeValues(Map.of(":pk", DynamoItems.s(StudyPlanStore.PURGE_PARTITION)))
                .limit(limit).build()));
        return response.items().stream().map(Queued::new).toList();
    }

    /** Purges the plan of one queued deletion, then takes the deletion off the queue. A failure leaves it queued: every step is idempotent, the next sweep repeats it. */
    public void purge(Queued job) {
        UUID owner = DynamoItems.uuid(job.item, "owner"), planId = DynamoItems.uuid(job.item, "planId");
        purgePlan(owner, planId);
        DynamoWrites.transaction(client).delete(tables.userAccountDetailsTableName(), DynamoItems.key(StudyPlanStore.PURGE_PARTITION, DynamoItems.string(job.item, "sk")), null).commit();
    }

    /** Everything under {@code PLAN#owner#id}: documents retired first, then the items in batches of up to 100. */
    public void purgePlan(UUID owner, UUID planId) {
        String pk = DynamoItems.plan(owner, planId);
        var items = query(pk);
        for (var item : items) {
            String sk = DynamoItems.string(item, "sk");
            var reference = StudyPlanDocumentPublishStore.Reference.from(item);
            if (sk.startsWith("VERSION#") && reference != null)
                documents.retire(owner, reference, "plan-version:" + planId + ":" + reference.documentId(), "plan-version-retirement", "plan-purge:" + planId);
            if (sk.startsWith("RECEIPT#")) {
                String key = sk.substring("RECEIPT#".length());
                if (reference != null) documents.retire(owner, reference, "plan-receipt:" + key, "plan-receipt-retirement", "plan-purge:" + planId);
                if (DynamoItems.number(item, "status") != 204) tombstoneReceipt(owner, key);
            }
        }
        var keys = items.stream().map(item -> DynamoItems.key(pk, DynamoItems.string(item, "sk"))).toList();
        deleteAll(keys);
    }

    private static final Map<String, AttributeValue> TOMBSTONE = Map.of("deleted", DynamoItems.bool(true), "response", DynamoItems.nul(), "documentId", DynamoItems.nul());

    /** The receipt keeps its hash and status, answers PLAN_DELETED, and holds no response bytes any more. */
    private void tombstoneReceipt(UUID owner, String key) {
        var tx = DynamoWrites.transaction(client).update(tables.userAccountDetailsTableName(), DynamoItems.key(DynamoItems.account(owner), "MUTATION#" + key), TOMBSTONE, null);
        try { tx.commit(); } catch (DynamoWrites.Conflict alreadyErased) { /* the receipt itself is gone: nothing to tombstone */ }
    }

    /**
     * The test-account reset's {@code UPDATE mutation_receipts SET deleted=true,response=NULL WHERE account_id=?}: every
     * receipt of the owner not yet a tombstone, the 204 receipts of earlier deletions included (a replayed delete key
     * answers 410 after a reset). Batches of up to 100 distinct items; a batch that loses an item to a
     * concurrent erasure falls back to one receipt at a time.
     */
    void tombstoneReceipts(UUID owner) {
        String pk = DynamoItems.account(owner);
        var pending = OwnerTransactionReads.queryAll(client, tables.userAccountDetailsTableName(), pk, "MUTATION#", "schemaVersion, sk, deleted").stream()
                .filter(item -> !DynamoItems.flag(item, "deleted")).map(item -> DynamoItems.string(item, "sk").substring("MUTATION#".length())).toList();
        for (int from = 0; from < pending.size(); from += DynamoWrites.MAX_ITEMS) {
            var batch = pending.subList(from, Math.min(pending.size(), from + DynamoWrites.MAX_ITEMS));
            var tx = DynamoWrites.transaction(client);
            for (String key : batch) tx.update(tables.userAccountDetailsTableName(), DynamoItems.key(pk, "MUTATION#" + key), TOMBSTONE, null);
            try { tx.commit(); } catch (DynamoWrites.Conflict erasedMeanwhile) { for (String key : batch) tombstoneReceipt(owner, key); }
        }
    }

    List<Map<String, AttributeValue>> query(String pk) { return OwnerTransactionReads.queryAll(client, tables.userAccountDetailsTableName(), pk, null); }

    void deleteAll(List<Map<String, AttributeValue>> keys) {
        for (int from = 0; from < keys.size(); from += DynamoWrites.MAX_ITEMS) {
            var tx = DynamoWrites.transaction(client);
            for (var key : keys.subList(from, Math.min(keys.size(), from + DynamoWrites.MAX_ITEMS))) tx.delete(tables.userAccountDetailsTableName(), key, null);
            tx.commit();
        }
    }
}
