package com.lookahead.domain.db.store.access;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipErasure;
import com.lookahead.domain.db.store.membership.MembershipFacts;
import com.lookahead.domain.db.store.plan.StudyPlanAccountErasure;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: owner maintenance on DynamoDB. The maintenance record is an insert-only {@code
 * ACCOUNT#id / MAINTENANCE#time#id} audit event; a completed learner deletion also leaves the
 * {@code ACCOUNT#id / DELETED} marker (the unique completion receipt) that makes a repeat a no-op.
 * Plans, free ledgers and memberships are erased through the other lanes' interfaces.
 */
@Repository
public class AccountMaintenanceStore implements AccountMaintenance, AccountMaintenanceFacts {
  private final AccountAggregateStore accounts;
  private final StaffAccess staff;
  private final AccountFactReads facts;
  private final ObjectProvider<MembershipErasure> membershipErasure;
  private final ObjectProvider<StudyPlanAccountErasure> plans;

  @Autowired
  public AccountMaintenanceStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      StaffAccess staff,
      ObjectProvider<TestAccountRegistry> testAccounts,
      ObjectProvider<MembershipFacts> memberships,
      ObjectProvider<MembershipErasure> membershipErasure,
      ObjectProvider<StudyPlanAccountErasure> plans) {
    this(
        client,
        tables,
        staff,
        testAccounts,
        memberships,
        membershipErasure,
        plans,
        Clock.systemUTC());
  }

  AccountMaintenanceStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      StaffAccess staff,
      ObjectProvider<TestAccountRegistry> testAccounts,
      ObjectProvider<MembershipFacts> memberships,
      ObjectProvider<MembershipErasure> membershipErasure,
      ObjectProvider<StudyPlanAccountErasure> plans,
      Clock clock) {
    this.accounts = new AccountAggregateStore(client, tables, clock);
    this.staff = staff;
    this.facts = new AccountFactReads(accounts, testAccounts, memberships);
    this.membershipErasure = membershipErasure;
    this.plans = plans;
  }

  @Override
  public void ensureSubject(UUID id) {
    AccountSubjectStore.ensureSubject(accounts, id);
  }

  @Override
  public boolean subjectExists(UUID id) {
    return !get(accounts.client(), accounts.accounts(), account(id), "META").isEmpty();
  }

  @Override
  public boolean alreadyDeleted(UUID id) {
    return !get(accounts.client(), accounts.accounts(), account(id), "DELETED").isEmpty();
  }

  @Override
  public boolean testAccount(UUID id) {
    return facts.testAccount(id);
  }

  @Override
  public boolean author(UUID id) {
    return facts.author(id);
  }

  @Override
  public boolean privileged(UUID id) {
    var a = staff.forAccount(id);
    return a != null && (!a.roles().isEmpty() || !a.topicGrants().isEmpty() || a.fullCurriculum());
  }

  @Override
  public boolean grants(UUID id) {
    return facts.grants(id);
  }

  @Override
  public boolean openPurchases(UUID id) {
    return facts.openPurchases(id);
  }

  @Override
  public void endMemberships(UUID id) {
    var ending = membershipErasure.getIfAvailable();
    if (ending != null) ending.endMemberships(id);
  }

  @Override
  public void erasePlans(UUID id) {
    var erasure = plans.getIfAvailable();
    if (erasure != null) erasure.eraseAccount(id);
  }

  @Override
  public void resetStaff(UUID actor, UUID id) {
    var a = staff.forAccount(id);
    if (a == null || (a.roles().isEmpty() && a.topicGrants().isEmpty() && !a.fullCurriculum()))
      return;
    staff.save(
        actor,
        "downgrade",
        a,
        new StaffAccess.Assignment(a.id(), id, null, Set.of(), Set.of(), false, a.revision() + 1));
  }

  @Override
  public void deleteGrants(UUID id) {
    AccountSubjectStore.revokeAll(accounts, id);
  }

  /**
   * The audit event, and for a deletion the completion marker, in one transaction; a marker already
   * there ends a repeat quietly.
   */
  @Override
  public void record(UUID actor, UUID id, String action, boolean deletion) {
    Instant now = accounts.now();
    UUID operationId = UUID.randomUUID();
    var tx = accounts.transaction();
    tx.insertOnly(
        accounts.audit(),
        item(
            account(id),
            maintenanceSk(now, operationId),
            attrs("action", s(action), "actorId", s(actor.toString()), "occurredAt", time(now))));
    if (deletion)
      tx.insertOnly(
          accounts.accounts(),
          item(
              account(id),
              "DELETED",
              attrs(
                  "actorId",
                  s(actor.toString()),
                  "operationId",
                  s(operationId.toString()),
                  "deletedAt",
                  time(now))));
    try {
      tx.commit();
    } catch (DynamoWrites.Conflict done) {
      if (!deletion || !alreadyDeleted(id)) throw done;
    }
  }
}
