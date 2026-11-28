package com.lookahead.domain.service.access;

import com.lookahead.domain.client.AccountSignInClient;
import com.lookahead.domain.db.store.access.AccountMaintenance;
import com.lookahead.domain.db.store.access.AccountMaintenanceFacts;
import com.lookahead.domain.dto.AccountMaintenanceConfirmation;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Owner maintenance of real accounts. Identity disables Local sign-in before data erasure. */
@Service
public class AccountMaintenanceService {
  private final AccountMaintenance store;
  private final AccountMaintenanceFacts facts;
  private final StaffAccessService staff;
  private final AccountDirectoryService directory;
  private final TestAccountModeService localPolicy;
  private final AccountSignInClient identity;

  /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
  @Autowired
  public AccountMaintenanceService(
      AccountMaintenance store,
      AccountMaintenanceFacts facts,
      StaffAccessService staff,
      AccountDirectoryService directory,
      TestAccountModeService localPolicy,
      AccountSignInClient identity) {
    this.store = store;
    this.facts = facts;
    this.staff = staff;
    this.directory = directory;
    this.localPolicy = localPolicy;
    this.identity = identity;
  }

  public void downgrade(AccountPrincipal actor, UUID id, AccountMaintenanceConfirmation request) {
    try {
      downgradeChecked(actor, id, request);
    } catch (AccountFailure refused) {
      throw refusal("downgrade", actor, id, refused);
    }
    EventLog.afterCommit(() ->
        EventLog.success(EventLog.EVENT, "account.downgrade")
            .addKeyValue("account.id", actor.accountId())
            .addKeyValue("target.id", id)
            .log(
                "{} downgraded account {} to a free learner: roles, course grants and memberships"
                    + " removed",
                EventLog.actor("Owner", actor.accountId()),
                id));
  }

  public void deleteLearner(
      AccountPrincipal actor, UUID id, AccountMaintenanceConfirmation request) {
    try {
      deleteChecked(actor, id, request);
    } catch (AccountFailure refused) {
      throw refusal("delete-learner", actor, id, refused);
    }
  }

  private static AccountFailure refusal(
      String action, AccountPrincipal actor, UUID id, AccountFailure refused) {
    if ("TEST_IDENTITY_UNAVAILABLE".equals(refused.code()))
      EventLog.failed(EventLog.EVENT, "account." + action, refused.code())
          .addKeyValue("account.id", actor == null ? null : actor.accountId())
          .addKeyValue("target.id", id)
          .log(
              "{} could not delete account {}: Identity did not confirm the sign-in was disabled;"
                  + " nothing was erased, retry the same action",
              EventLog.actor("Owner", actor == null ? null : actor.accountId()),
              id);
    else
      EventLog.refused(EventLog.EVENT, "account." + action, refused.code())
          .addKeyValue("account.id", actor == null ? null : actor.accountId())
          .addKeyValue("target.id", id)
          .log(
              "Account {} {} refused for {}: {}",
              id,
              action,
              EventLog.actor("Account", actor == null ? null : actor.accountId()),
              refused.code());
    return refused;
  }

  private void downgradeChecked(
      AccountPrincipal actor, UUID id, AccountMaintenanceConfirmation request) {
    staff.requireOwner(actor);
    requireOther(actor, id);
    if (request == null || !"DOWNGRADE".equals(request.confirmation())) throw confirmation();
    directory.byId(id);
    store.ensureSubject(id);
    requireReal(id);
    // DLV-1016: the order of the four writes is this service's; each is one store call.
    store.resetStaff(actor.accountId(), id);
    store.deleteGrants(id);
    store.endMemberships(id);
    store.record(actor.accountId(), id, "downgrade", false);
  }

  private void deleteChecked(
      AccountPrincipal actor, UUID id, AccountMaintenanceConfirmation request) {
    localPolicy.requireLocal();
    staff.requireOwner(actor);
    requireOther(actor, id);
    if (request == null || !"DELETE".equals(request.confirmation())) throw confirmation();
    if (facts.alreadyDeleted(id)) {
      EventLog.success(EventLog.EVENT, "account.delete-learner")
          .addKeyValue("account.id", actor.accountId())
          .addKeyValue("target.id", id)
          .addKeyValue("repeated", true)
          .log(
              "Account {} was already deleted; nothing to do for {}",
              id,
              EventLog.actor("Owner", actor.accountId()));
      return;
    }
    if (!facts.subjectExists(id)) {
      directory.byId(id);
      store.ensureSubject(id);
    }
    requireFreeLearner(id);
    identity.disableReal(id);
    if (facts.alreadyDeleted(id)) return;
    requireFreeLearner(id);
    store.erasePlans(id);
    store.deleteGrants(id);
    store.record(actor.accountId(), id, "delete", true);
    EventLog.afterCommit(() ->
        EventLog.success(EventLog.EVENT, "account.delete-learner")
            .addKeyValue("account.id", actor.accountId())
            .addKeyValue("target.id", id)
            .log(
                "{} deleted learner account {}: sign-in disabled in Identity, plans, grants and"
                    + " receipts erased",
                EventLog.actor("Owner", actor.accountId()),
                id));
  }

  private void requireFreeLearner(UUID id) {
    if (!facts.subjectExists(id))
      throw new AccountFailure(404, "ACCOUNT_NOT_FOUND", "Account not found.");
    requireReal(id);
    if (facts.privileged(id) || facts.grants(id) || facts.openPurchases(id))
      throw new AccountFailure(
          409,
          "DOWNGRADE_REQUIRED",
          "Downgrade access to a free learner before deleting this account.");
  }

  private void requireReal(UUID id) {
    if (facts.testAccount(id))
      throw new AccountFailure(
          403, "TEST_ACCOUNT_ROUTE_REQUIRED", "Use the Test accounts page for managed accounts.");
    if (facts.author(id))
      throw new AccountFailure(
          403, "OWNER_ACCOUNT_PROTECTED", "An author account cannot be changed here.");
  }

  private static void requireOther(AccountPrincipal actor, UUID id) {
    if (id == null || actor.accountId().equals(id))
      throw new AccountFailure(
          403, "OWNER_ACCOUNT_PROTECTED", "The owner account cannot be changed here.");
  }

  private static AccountFailure confirmation() {
    return new AccountFailure(
        422, "ACCOUNT_CONFIRMATION_REQUIRED", "Type the required confirmation word.");
  }
}
