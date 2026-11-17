package com.lookahead.domain.access;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: owner maintenance on DynamoDB. The maintenance record is an insert-only
 * {@code ACCOUNT#id / MAINTENANCE#time#id} audit event; a completed learner deletion also leaves the
 * {@code ACCOUNT#id / DELETED} marker (the unique completion receipt) that makes a repeat a no-op. Plans, free
 * ledgers and memberships are erased through the other lanes' interfaces.
 */
@Repository
public class DynamoAccountMaintenanceStore implements AccountMaintenanceStore {
    private final DynamoCloudAccounts accounts;
    private final StaffAccessRepository staff;
    private final ObjectProvider<TestAccountRegistryLookup> testAccounts;
    private final ObjectProvider<MembershipFacts> memberships;
    private final ObjectProvider<MembershipErasure> membershipErasure;
    private final ObjectProvider<LearningDataErasure> plans;

    @Autowired
    public DynamoAccountMaintenanceStore(DynamoDbClient client, DynamoTableNames tables, StaffAccessRepository staff, ObjectProvider<TestAccountRegistryLookup> testAccounts,
            ObjectProvider<MembershipFacts> memberships, ObjectProvider<MembershipErasure> membershipErasure, ObjectProvider<LearningDataErasure> plans) {
        this(client, tables, staff, testAccounts, memberships, membershipErasure, plans, Clock.systemUTC());
    }
    DynamoAccountMaintenanceStore(DynamoDbClient client, DynamoTableNames tables, StaffAccessRepository staff, ObjectProvider<TestAccountRegistryLookup> testAccounts,
            ObjectProvider<MembershipFacts> memberships, ObjectProvider<MembershipErasure> membershipErasure, ObjectProvider<LearningDataErasure> plans, Clock clock) {
        this.accounts = new DynamoCloudAccounts(client, tables, clock); this.staff = staff; this.testAccounts = testAccounts;
        this.memberships = memberships; this.membershipErasure = membershipErasure; this.plans = plans;
    }

    @Override public void ensureSubject(UUID id) {
        if (subjectExists(id)) return;
        var tx = accounts.transaction();
        tx.insertOnly(accounts.accounts(), item(account(id), "META", attrs("revision", n(1), "lastPlanNumber", n(0), "createdAt", time(accounts.now()))));
        try { tx.commit(); } catch (DynamoWrites.Conflict present) { /* created meanwhile */ }
    }
    @Override public boolean subjectExists(UUID id) { return !get(accounts.client(), accounts.accounts(), account(id), "META").isEmpty(); }
    @Override public boolean lockSubject(UUID id) { return subjectExists(id); }
    @Override public boolean alreadyDeleted(UUID id) { return !get(accounts.client(), accounts.accounts(), account(id), "DELETED").isEmpty(); }
    @Override public boolean testAccount(UUID id) {
        var c = accounts.control(id); var registry = testAccounts.getIfAvailable();
        return (c != null && c.managedTest()) || (registry != null && registry.isTestAccount(id));
    }
    @Override public boolean author(UUID id) { var c = accounts.control(id); return c != null && c.author(); }
    @Override public boolean privileged(UUID id) {
        var a = staff.forAccount(id);
        return a != null && (!a.roles().isEmpty() || !a.topicGrants().isEmpty() || a.fullCurriculum());
    }
    @Override public boolean grants(UUID id) { return !query(accounts.client(), accounts.accounts(), account(id), "GRANT#").isEmpty(); }
    @Override public boolean openPurchases(UUID id) { var facts = memberships.getIfAvailable(); return facts != null && facts.hasOpenPurchases(id); }

    @Override public void downgrade(UUID actor, UUID id) {
        resetStaff(actor, id);
        deleteGrants(id, null);
        var ending = membershipErasure.getIfAvailable();
        if (ending != null) ending.endMemberships(id);
        record(actor, id, "downgrade", false);
    }
    @Override public void deleteLearner(UUID actor, UUID id) {
        var erasure = plans.getIfAvailable();
        if (erasure != null) erasure.eraseAccount(id);
        deleteGrants(id, null);
        record(actor, id, "delete", true);
    }

    private void resetStaff(UUID actor, UUID id) {
        var a = staff.forAccount(id);
        if (a == null || (a.roles().isEmpty() && a.topicGrants().isEmpty() && !a.fullCurriculum())) return;
        staff.save(actor, "downgrade", a, new StaffAccessRepository.Assignment(a.id(), id, null, Set.of(), Set.of(), false, a.revision() + 1));
    }
    private void deleteGrants(UUID id, DynamoWrites into) {
        var keys = new ArrayList<Map<String, AttributeValue>>();
        for (var grant : query(accounts.client(), accounts.accounts(), account(id), "GRANT#")) keys.add(key(account(id), DynamoItems.string(grant, "sk")));
        for (int from = 0; from < keys.size(); from += DynamoWrites.MAX_ITEMS) {
            var tx = accounts.transaction();
            for (var key : keys.subList(from, Math.min(keys.size(), from + DynamoWrites.MAX_ITEMS))) tx.delete(accounts.accounts(), key, null);
            tx.commit();
        }
    }
    /** The audit event, and for a deletion the completion marker, in one transaction; a marker already there ends a repeat quietly. */
    private void record(UUID actor, UUID id, String action, boolean deletion) {
        Instant now = accounts.now(); UUID operationId = UUID.randomUUID();
        var tx = accounts.transaction();
        tx.insertOnly(accounts.audit(), item(account(id), maintenanceSk(now, operationId), attrs("action", s(action), "actorId", s(actor.toString()), "occurredAt", time(now))));
        if (deletion) tx.insertOnly(accounts.accounts(), item(account(id), "DELETED", attrs("actorId", s(actor.toString()), "operationId", s(operationId.toString()), "deletedAt", time(now))));
        try { tx.commit(); } catch (DynamoWrites.Conflict done) { if (!deletion || !alreadyDeleted(id)) throw done; }
    }
}
