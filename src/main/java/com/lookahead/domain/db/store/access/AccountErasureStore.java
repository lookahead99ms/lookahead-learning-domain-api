package com.lookahead.domain.db.store.access;

import com.lookahead.domain.db.store.membership.MembershipErasure;
import com.lookahead.domain.db.store.plan.StudyPlanAccountErasure;
import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * DLV-970: the durable, resumable erasure of a deleted account's data on DynamoDB (the SQL {@code erase}): the staff
 * assignment emptied (with its event), grants, preferences and links deleted, then the other lanes' erasures. The
 * control store's {@code ACCOUNT_REMOVAL#id / JOB} item (phase {@code ERASE}) is the receipt: it is moved to
 * {@code DONE} only after every step, so a retry of Delete or a sweep ({@link #pending} then {@link #erase}) finishes an interrupted run.
 * Audit, permanent labels, lifetime quotas, the identity bindings and the removal marker are never touched.
 *
 * <p>DLV-970 lane G: the sweep finds interrupted erasures without a Scan through the bounded queue partition
 * {@code WORK#ACCOUNT-ERASURE#00 / ACCOUNT#id} ({@link #queue}, written by Delete before the state transition and
 * removed by {@link #erase} once the job is DONE); the {@code account-erasure} maintenance job ({@code AccountErasureJob}) walks that queue.
 */
public final class AccountErasureStore {
    public static final String QUEUE_PARTITION = "WORK#ACCOUNT-ERASURE#00";
    public static final int MAX_QUEUED = 25;
    private final AccountAggregateStore accounts;
    private final StaffAccess staff;
    private final ObjectProvider<StudyPlanAccountErasure> plans;
    private final ObjectProvider<MembershipErasure> memberships;

    public AccountErasureStore(AccountAggregateStore accounts, StaffAccess staff, ObjectProvider<StudyPlanAccountErasure> plans, ObjectProvider<MembershipErasure> memberships) {
        this.accounts = accounts; this.staff = staff; this.plans = plans; this.memberships = memberships;
    }

    /** Erases now; every step is idempotent, so an interrupted run is simply repeated. */
    public void erase(UUID actor, UUID id) {
        var assignment = staff.forAccount(id);
        if (assignment != null && (!assignment.roles().isEmpty() || !assignment.topicGrants().isEmpty() || assignment.fullCurriculum())) {
            var next = new StaffAccess.Assignment(assignment.id(), id, null, Set.of(), Set.of(), false, assignment.revision() + 1);
            try { staff.save(actor, "erase", assignment, next); } catch (DynamoWrites.Conflict raced) { /* someone else emptied it: read again on the next step */ }
        }
        var keys = new ArrayList<Map<String, AttributeValue>>();
        for (var grant : query(accounts.client(), accounts.accounts(), account(id), "GRANT#")) keys.add(key(account(id), DynamoItems.string(grant, "sk")));
        keys.add(key(account(id), "PREFERENCES")); keys.add(key(account(id), "LINKS"));
        for (int from = 0; from < keys.size(); from += DynamoWrites.MAX_ITEMS) {
            var tx = accounts.transaction();
            for (var key : keys.subList(from, Math.min(keys.size(), from + DynamoWrites.MAX_ITEMS))) tx.delete(accounts.accounts(), key, null);
            tx.commit();
        }
        var planErasure = plans.getIfAvailable(); var membershipErasure = memberships.getIfAvailable();
        if (planErasure != null) planErasure.eraseAccount(id);
        if (membershipErasure != null) membershipErasure.endMemberships(id);
        // The job receipt: DONE only after every step; absent for a merge-removed waiting account or an older removal.
        var job = get(accounts.client(), accounts.accounts(), removalJobPk(id), "JOB");
        if (!job.isEmpty() && "ERASE".equals(text(job, "phase"))) {
            var done = accounts.transaction();
            done.update(accounts.accounts(), key(removalJobPk(id), "JOB"), Map.of("phase", s("DONE"), "actorId", s(actor.toString())), "\"phase\"=?", s("ERASE"));
            try { done.commit(); } catch (DynamoWrites.Conflict finished) { /* another run completed it */ }
        }
        drop(id);
    }

    /** Queues {@code id} for the sweep (idempotent) so an erasure interrupted after the state transition is found again. */
    public void queue(UUID actor, UUID id) {
        var tx = accounts.transaction();
        tx.insertOnly(accounts.accounts(), item(QUEUE_PARTITION, account(id), attrs("accountId", s(id.toString()), "actorId", s(actor.toString()), "queuedAt", time(accounts.now()))));
        try { tx.commit(); } catch (DynamoWrites.Conflict queued) { /* already queued */ }
    }
    /** Takes {@code id} off the sweep queue. */
    public void drop(UUID id) {
        var tx = accounts.transaction();
        tx.delete(accounts.accounts(), key(QUEUE_PARTITION, account(id)), null);
        tx.commit();
    }
    /** The time of this store's clock: the one that stamped {@code queuedAt}, so the sweep compares like with like. */
    public Instant now() { return accounts.now(); }
    /** One queued entry: the account, the actor whose Delete queued it and when. */
    public record Queued(UUID id, UUID actor, Instant queuedAt) {}
    /** The first {@code limit} queued erasures (a bounded strong query; no Scan). */
    public List<Queued> queued(int limit) {
        if (limit < 1 || limit > MAX_QUEUED) throw new IllegalArgumentException("Queue page must be 1–" + MAX_QUEUED);
        var response = accounts.client().query(QueryRequest.builder().tableName(accounts.accounts()).consistentRead(true)
                .keyConditionExpression("pk = :pk").expressionAttributeValues(Map.of(":pk", s(QUEUE_PARTITION))).limit(limit).build());
        var queued = new ArrayList<Queued>();
        for (var item : response.items()) queued.add(new Queued(DynamoItems.uuid(item, "accountId"), DynamoItems.uuid(item, "actorId"), instant(item, "queuedAt")));
        return queued;
    }

    /** True when the removal job of {@code id} is still in its erasure phase (an interrupted Delete). */
    public boolean pending(UUID id) {
        var job = get(accounts.client(), accounts.accounts(), removalJobPk(id), "JOB");
        return !job.isEmpty() && "ERASE".equals(text(job, "phase"));
    }
}
