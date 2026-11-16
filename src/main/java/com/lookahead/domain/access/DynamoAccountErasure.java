package com.lookahead.domain.access;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the durable, resumable erasure of a deleted account's data on DynamoDB (the SQL {@code erase}): the staff
 * assignment emptied (with its event), grants, preferences and links deleted, then the other lanes' erasures. The
 * control store's {@code ACCOUNT_REMOVAL#id / JOB} item (phase {@code ERASE}) is the receipt: it is moved to
 * {@code DONE} only after every step, so a retry of Delete or a sweep ({@link #resume}) finishes an interrupted run.
 * Audit, permanent labels, lifetime quotas, the identity bindings and the removal marker are never touched.
 *
 * <p>DLV-970 lane G: the sweep finds interrupted erasures without a Scan through the bounded queue partition
 * {@code WORK#ACCOUNT-ERASURE#00 / ACCOUNT#id} ({@link #queue}, written by Delete before the state transition and
 * removed by {@link #erase} once the job is DONE); {@link #resumePage} is the {@code account-erasure} maintenance job.
 */
public final class DynamoAccountErasure {
    public static final String QUEUE_PARTITION = "WORK#ACCOUNT-ERASURE#00";
    public static final int MAX_QUEUED = 25;
    private final DynamoCloudAccounts accounts;
    private final StaffAccessRepository staff;
    private final ObjectProvider<LearningDataErasure> plans;
    private final ObjectProvider<MembershipErasure> memberships;

    public DynamoAccountErasure(DynamoCloudAccounts accounts, StaffAccessRepository staff, ObjectProvider<LearningDataErasure> plans, ObjectProvider<MembershipErasure> memberships) {
        this.accounts = accounts; this.staff = staff; this.plans = plans; this.memberships = memberships;
    }

    /** Erases now; every step is idempotent, so an interrupted run is simply repeated. */
    public void erase(UUID actor, UUID id) {
        var assignment = staff.forAccount(id);
        if (assignment != null && (!assignment.roles().isEmpty() || !assignment.topicGrants().isEmpty() || assignment.fullCurriculum())) {
            var next = new StaffAccessRepository.Assignment(assignment.id(), id, null, Set.of(), Set.of(), false, assignment.revision() + 1);
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
        dequeue(id);
    }

    /** Queues {@code id} for the sweep (idempotent) so an erasure interrupted after the state transition is found again. */
    public void queue(UUID actor, UUID id) {
        var tx = accounts.transaction();
        tx.insertOnly(accounts.accounts(), item(QUEUE_PARTITION, account(id), attrs("accountId", s(id.toString()), "actorId", s(actor.toString()), "queuedAt", time(accounts.now()))));
        try { tx.commit(); } catch (DynamoWrites.Conflict queued) { /* already queued */ }
    }
    private void dequeue(UUID id) {
        var tx = accounts.transaction();
        tx.delete(accounts.accounts(), key(QUEUE_PARTITION, account(id)), null);
        tx.commit();
    }
    /** A queue entry younger than this whose job is not pending yet is a Delete still between its queue and its state transition. */
    public static final java.time.Duration GRACE = java.time.Duration.ofMinutes(10);
    /** One queued entry: the account, the actor whose Delete queued it and when. */
    public record Queued(UUID id, UUID actor, java.time.Instant queuedAt) {}
    /** The first {@code limit} queued erasures (a bounded strong query; no Scan). */
    public List<Queued> queued(int limit) {
        if (limit < 1 || limit > MAX_QUEUED) throw new IllegalArgumentException("Queue page must be 1–" + MAX_QUEUED);
        var response = accounts.client().query(software.amazon.awssdk.services.dynamodb.model.QueryRequest.builder().tableName(accounts.accounts()).consistentRead(true)
                .keyConditionExpression("pk = :pk").expressionAttributeValues(Map.of(":pk", s(QUEUE_PARTITION))).limit(limit).build());
        var queued = new ArrayList<Queued>();
        for (var item : response.items()) queued.add(new Queued(DynamoItems.uuid(item, "accountId"), DynamoItems.uuid(item, "actorId"), instant(item, "queuedAt")));
        return queued;
    }
    /** What one sweep page did: erasures finished, entries whose job is no longer pending (dropped) and erasures that failed and stay queued. */
    public record Resumed(int finished, int dropped, int failed, boolean more) {}
    /** One bounded page of the queue: pending jobs are finished through {@link #resume}, stale entries are dropped. */
    public Resumed resumePage(int limit) {
        var page = queued(limit);
        int finished = 0, dropped = 0, failed = 0;
        for (Queued entry : page) {
            if (!pending(entry.id())) {
                // A Delete queues before it transitions: a young entry without a job is still in flight, an old one is stale.
                if (entry.queuedAt() != null && entry.queuedAt().plus(GRACE).isAfter(accounts.now())) continue;
                dequeue(entry.id()); dropped++; continue;
            }
            if (resume(entry.actor(), List.of(entry.id())) == 1) finished++; else failed++;
        }
        return new Resumed(finished, dropped, failed, page.size() == limit);
    }

    /** True when the removal job of {@code id} is still in its erasure phase (an interrupted Delete). */
    public boolean pending(UUID id) {
        var job = get(accounts.client(), accounts.accounts(), removalJobPk(id), "JOB");
        return !job.isEmpty() && "ERASE".equals(text(job, "phase"));
    }

    /** Finishes the erasure of {@code ids} whose job is still pending (for a sweep); the number finished. */
    public int resume(UUID actor, List<UUID> ids) {
        int finished = 0;
        for (UUID id : ids) {
            if (!pending(id)) continue;
            try { erase(actor, id); finished++; }
            catch (RuntimeException failure) {
                Events.failed(Events.EVENT, "account.erasure", "UNEXPECTED").addKeyValue("target.id", id).addKeyValue("error.type", Events.types(failure))
                    .log("Erasure of deleted account {} did not complete ({}); the next run tries again", id, Events.types(failure));
            }
        }
        return finished;
    }
}
