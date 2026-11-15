package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * DLV-970: the physical purge behind a plan deletion. The delete transaction only tombstones the plan and queues a
 * {@code WORK#PLAN-PURGE#00} job; this job pages through {@code PLAN#owner#id} (versions, activity, receipt
 * projections), retires every published document through {@link DynamoPlanDocuments}, marks pre-deletion receipts
 * deleted and drops their response bytes, and finally removes the job. Every step is idempotent, so a crash resumes
 * on the next sweep; the plan tombstone itself is never removed (it is what answers PLAN_DELETED to old receipts).
 * Registered with the sweep runner when lane D provides {@link PlanMaintenanceJobs}; {@link #cleanPage} is also callable directly.
 */
@Component
@Profile("accounts")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = com.lookahead.domain.persistence.PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoPlanPurge {
    static final int MAX_JOBS = 25;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("lookahead.event");

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final DynamoPlanDocuments documents;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoPlanPurge(DynamoDbClient client, DynamoTableNames tables, ObjectProvider<PlanMaintenanceJobs> jobs) {
        this(client, tables, Clock.systemUTC());
        jobs.ifAvailable(runner -> runner.register("plan-purge", () -> cleanPage(MAX_JOBS)));
    }
    DynamoPlanPurge(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = client; this.tables = tables; this.documents = new DynamoPlanDocuments(client, tables, clock);
    }

    /** One strong page of queued deletions; returns how many plans were purged. Failures are logged and left queued. */
    public int cleanPage(int limit) {
        if (limit < 1 || limit > MAX_JOBS) throw new IllegalArgumentException("Purge limit must be 1–25");
        var response = DynamoPlanRepository.read(() -> client.query(QueryRequest.builder().tableName(tables.userAccountDetailsTableName())
                .consistentRead(true).keyConditionExpression("pk = :pk").expressionAttributeValues(Map.of(":pk", DynamoItems.s(DynamoPlanRepository.PURGE_PARTITION)))
                .limit(limit).build()));
        int purged = 0;
        for (var job : response.items()) {
            try {
                UUID owner = DynamoItems.uuid(job, "owner"), planId = DynamoItems.uuid(job, "planId");
                purgePlan(owner, planId);
                DynamoWrites.transaction(client).delete(tables.userAccountDetailsTableName(), DynamoItems.key(DynamoPlanRepository.PURGE_PARTITION, DynamoItems.string(job, "sk")), null).commit();
                purged++;
            } catch (RuntimeException failure) {
                LOG.warn("Plan purge job deferred: {}", com.lookahead.domain.Events.types(failure));
            }
        }
        return purged;
    }

    /** Everything under {@code PLAN#owner#id}: documents retired first, then the items in batches of up to 100. */
    public void purgePlan(UUID owner, UUID planId) {
        String pk = DynamoItems.plan(owner, planId);
        var items = query(pk);
        for (var item : items) {
            String sk = DynamoItems.string(item, "sk");
            var reference = DynamoPlanDocuments.Reference.from(item);
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
     * answers 410 after a reset, as on PostgreSQL). Batches of up to 100 distinct items; a batch that loses an item to a
     * concurrent erasure falls back to one receipt at a time.
     */
    void tombstoneReceipts(UUID owner) {
        String pk = DynamoItems.account(owner);
        var pending = DynamoOwnerReads.queryAll(client, tables.userAccountDetailsTableName(), pk, "MUTATION#", "schemaVersion, sk, deleted").stream()
                .filter(item -> !DynamoItems.flag(item, "deleted")).map(item -> DynamoItems.string(item, "sk").substring("MUTATION#".length())).toList();
        for (int from = 0; from < pending.size(); from += DynamoWrites.MAX_ITEMS) {
            var batch = pending.subList(from, Math.min(pending.size(), from + DynamoWrites.MAX_ITEMS));
            var tx = DynamoWrites.transaction(client);
            for (String key : batch) tx.update(tables.userAccountDetailsTableName(), DynamoItems.key(pk, "MUTATION#" + key), TOMBSTONE, null);
            try { tx.commit(); } catch (DynamoWrites.Conflict erasedMeanwhile) { for (String key : batch) tombstoneReceipt(owner, key); }
        }
    }

    List<Map<String, AttributeValue>> query(String pk) { return DynamoOwnerReads.queryAll(client, tables.userAccountDetailsTableName(), pk, null); }

    void deleteAll(List<Map<String, AttributeValue>> keys) {
        for (int from = 0; from < keys.size(); from += DynamoWrites.MAX_ITEMS) {
            var tx = DynamoWrites.transaction(client);
            for (var key : keys.subList(from, Math.min(keys.size(), from + DynamoWrites.MAX_ITEMS))) tx.delete(tables.userAccountDetailsTableName(), key, null);
            tx.commit();
        }
    }
}
