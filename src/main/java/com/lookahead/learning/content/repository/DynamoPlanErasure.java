package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: {@link PlanErasure} for dynamodb mode. Purges every plan partition (documents retired through the audited
 * retirement), then every owner-scoped item this lane writes under {@code ACCOUNT#id}: plans and tombstones, list
 * projections, lifecycle rows, mutation receipts (including the document-publication receipts and pointers of this
 * lane) and support receipts. The plan-number sequence on {@code ACCOUNT#id / META} always survives. The free-plan
 * ledgers follow the SQL twins: {@link #eraseAccount} (account removal) keeps the {@code FREECREATE#} /
 * {@code FREEDELETE#} receipts and the {@code freePlanCreations} counter; {@link #erase} (test accounts) deletes the
 * receipts and resets the counter in one transaction fenced on the owner revision.
 */
@Component
@Profile("accounts")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = com.lookahead.domain.persistence.PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoPlanErasure implements PlanErasure {
    static final List<String> PREFIXES = List.of("PLAN#", "PLANLIST#", "LIFECYCLE#", "MUTATION#", "SUPPORT#", "SUPPORTTIME#");
    static final List<String> LEDGER_PREFIXES = List.of("FREECREATE#", "FREEDELETE#");
    static final List<String> RESET_PREFIXES = List.of("PLAN#", "PLANLIST#", "LIFECYCLE#");
    static final int FENCE_RETRIES = 3;
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final DynamoPlanPurge purge;
    private final DynamoPlanRepository plans;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoPlanErasure(DynamoDbClient client, DynamoTableNames tables, DynamoPlanRepository plans, DynamoPlanPurge purge) {
        this.client = client; this.tables = tables; this.plans = plans; this.purge = purge;
    }
    /** Tests and the cross-lane fixtures: a private repository and purge on this client. */
    public DynamoPlanErasure(DynamoDbClient client, DynamoTableNames tables, tools.jackson.databind.ObjectMapper mapper) { this(client, tables, mapper, Clock.systemUTC()); }
    DynamoPlanErasure(DynamoDbClient client, DynamoTableNames tables, tools.jackson.databind.ObjectMapper mapper, Clock clock) {
        this(client, tables, new DynamoPlanRepository(client, tables, mapper, clock), new DynamoPlanPurge(client, tables, clock));
    }

    @Override public void erase(UUID account) { erase(account, PREFIXES); eraseLedgers(account); }

    @Override public void eraseAccount(UUID account) { erase(account, PREFIXES); }

    /**
     * The test-account reset (the SQL {@code DELETE FROM plans} cascade plus {@code UPDATE mutation_receipts SET
     * deleted=true, response=NULL} for the whole account): every plan with its versions, activity, list projection and
     * lifecycle row is purged; every mutation receipt, the 204 receipts of earlier deletions included, becomes a
     * tombstone (a replayed key answers 410 PLAN_DELETED); free-plan ledgers and support receipts are kept.
     */
    public void resetPlans(UUID account) { erase(account, RESET_PREFIXES); purge.tombstoneReceipts(account); }

    private void erase(UUID account, List<String> prefixes) {
        String pk = DynamoItems.account(account);
        for (var plan : plans.queryAll(pk, "PLAN#")) purge.purgePlan(account, DynamoItems.uuid(plan, "planId"));
        var keys = new ArrayList<Map<String, AttributeValue>>();
        for (String prefix : prefixes) for (var item : plans.queryAll(pk, prefix)) keys.add(DynamoItems.key(pk, DynamoItems.string(item, "sk")));
        // Document pointers (AuditedAccountStore RECORD# items) of this lane's publications only; other lanes' records stay.
        for (var item : plans.queryAll(pk, "RECORD#v1#")) {
            String record = DynamoItems.decode(DynamoItems.string(item, "sk").substring("RECORD#".length()));
            if (record.startsWith("plan-version:") || record.startsWith("plan-receipt:")) keys.add(DynamoItems.key(pk, DynamoItems.string(item, "sk")));
        }
        purge.deleteAll(keys);
    }

    /**
     * {@code DELETE FROM free_plan_creations / free_plan_deletions} plus the counter: the ledger receipts and
     * {@code META.freePlanCreations = 0} land in one transaction under the exact owner revision, so a free creation
     * committing at the same time either lands first (and is deleted by the retry) or loses its own fence; it can never
     * be counted on a reset counter. More than 99 receipts go in earlier unfenced batches, the last batch carries the
     * counter. An owner without META (never created, or already removed) has no counter to reset.
     */
    private void eraseLedgers(UUID account) {
        String pk = DynamoItems.account(account), table = tables.userAccountDetailsTableName();
        for (int attempt = 1; ; attempt++) {
            var keys = new ArrayList<Map<String, AttributeValue>>();
            for (String prefix : LEDGER_PREFIXES) for (var item : plans.queryAll(pk, prefix)) keys.add(DynamoItems.key(pk, DynamoItems.string(item, "sk")));
            var meta = DynamoOwnerReads.get(client, table, pk, "META");
            boolean counted = !meta.isEmpty() && meta.get("freePlanCreations") != null && meta.get("freePlanCreations").n() != null && DynamoItems.number(meta, "freePlanCreations") != 0;
            if (keys.isEmpty() && !counted) return;
            int last = Math.max(0, keys.size() - (DynamoWrites.MAX_ITEMS - 1));
            purge.deleteAll(keys.subList(0, last));
            var tx = DynamoWrites.transaction(client);
            for (var key : keys.subList(last, keys.size())) tx.delete(table, key, null);
            if (!meta.isEmpty()) {
                long revision = DynamoItems.number(meta, "revision");
                tx.update(table, DynamoItems.key(pk, "META"), Map.of("revision", DynamoItems.n(revision + 1), "freePlanCreations", DynamoItems.n(0)), "revision=?", DynamoItems.n(revision));
            }
            try { tx.commit(); return; }
            catch (DynamoWrites.Conflict moved) { if (attempt >= FENCE_RETRIES) throw moved; }
        }
    }
}
