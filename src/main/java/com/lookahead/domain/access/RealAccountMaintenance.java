package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.domain.testaccounts.TestAccountPolicy;
import com.lookahead.domain.testaccounts.TestIdentityOperator;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** Owner maintenance of real accounts. Identity disables Local sign-in before data erasure. */
@Service
public class RealAccountMaintenance {
    public record Confirmation(String confirmation) {}
    private final AccountMaintenanceStore store;
    private final StaffAccessService staff;
    private final AccountDirectory directory;
    private final TestAccountPolicy localPolicy;
    private final TestIdentityOperator identity;

    public RealAccountMaintenance(JdbcTemplate jdbc, StaffAccessService staff, AccountDirectory directory,
            TestAccountPolicy localPolicy, TestIdentityOperator identity, PlatformTransactionManager manager) {
        this(new JdbcAccountMaintenanceStore(jdbc, manager), staff, directory, localPolicy, identity);
    }
    /** DLV-970: the store (PostgreSQL or DynamoDB) is selected by {@code app.persistence.store}. */
    @org.springframework.beans.factory.annotation.Autowired
    public RealAccountMaintenance(AccountMaintenanceStore store, StaffAccessService staff, AccountDirectory directory,
            TestAccountPolicy localPolicy, TestIdentityOperator identity) {
        this.store=store;this.staff=staff;this.directory=directory;this.localPolicy=localPolicy;this.identity=identity;
    }

    public void downgrade(AccountPrincipal actor, UUID id, Confirmation request) {
        try { store.transaction(() -> downgradeChecked(actor, id, request)); }
        catch (AccountFailure refused) { throw refusal("downgrade", actor, id, refused); }
        Events.afterCommit(() -> Events.success(Events.EVENT, "account.downgrade").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
            .log("Owner downgraded account {} to a free learner: roles, course grants and memberships removed", id));
    }

    public void deleteLearner(AccountPrincipal actor, UUID id, Confirmation request) {
        try { deleteChecked(actor, id, request); }
        catch (AccountFailure refused) { throw refusal("delete-learner", actor, id, refused); }
    }

    private static AccountFailure refusal(String action, AccountPrincipal actor, UUID id, AccountFailure refused) {
        if ("TEST_IDENTITY_UNAVAILABLE".equals(refused.code()))
            Events.failed(Events.EVENT, "account." + action, refused.code()).addKeyValue("account.id", actor == null ? null : actor.accountId())
                .addKeyValue("target.id", id).log("Deleting account {} stopped: Identity did not confirm the sign-in was disabled; nothing was erased, retry the same action", id);
        else Events.refused(Events.EVENT, "account." + action, refused.code()).addKeyValue("account.id", actor == null ? null : actor.accountId())
                .addKeyValue("target.id", id).log("Account {} {} refused: {}", id, action, refused.code());
        return refused;
    }

    private void downgradeChecked(AccountPrincipal actor, UUID id, Confirmation request) {
        staff.requireOwner(actor);
        requireOther(actor,id);
        if(request==null||!"DOWNGRADE".equals(request.confirmation()))throw confirmation();
        directory.byId(id);
        store.ensureSubject(id);
        requireReal(id);
        store.downgrade(actor.accountId(),id);
    }

    private void deleteChecked(AccountPrincipal actor, UUID id, Confirmation request) {
        localPolicy.requireLocal();
        staff.requireOwner(actor);
        requireOther(actor,id);
        if(request==null||!"DELETE".equals(request.confirmation()))throw confirmation();
        if(store.alreadyDeleted(id)){
            Events.success(Events.EVENT,"account.delete-learner").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",id).addKeyValue("repeated",true)
                .log("Account {} was already deleted; nothing to do",id);
            return;
        }
        if(!store.subjectExists(id)) {
            directory.byId(id);
            store.ensureSubject(id);
        }
        store.transaction(() -> requireFreeLearner(id));
        identity.disableReal(id);
        store.transaction(() -> {
            if(store.alreadyDeleted(id))return;
            requireFreeLearner(id);
            store.deleteLearner(actor.accountId(),id);
            Events.afterCommit(()->Events.success(Events.EVENT,"account.delete-learner").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",id)
                .log("Owner deleted learner account {}: sign-in disabled in Identity, plans, grants and receipts erased",id));
        });
    }

    private void requireFreeLearner(UUID id) {
        if(!store.lockSubject(id))throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","Account not found.");
        requireReal(id);
        if(store.privileged(id)||store.grants(id)||store.openPurchases(id))
            throw new AccountFailure(409,"DOWNGRADE_REQUIRED","Downgrade access to a free learner before deleting this account.");
    }
    private void requireReal(UUID id) {
        if(store.testAccount(id))
            throw new AccountFailure(403,"TEST_ACCOUNT_ROUTE_REQUIRED","Use the Test accounts page for managed accounts.");
        if(store.author(id))
            throw new AccountFailure(403,"OWNER_ACCOUNT_PROTECTED","An author account cannot be changed here.");
    }
    private static void requireOther(AccountPrincipal actor, UUID id) {
        if(id==null||actor.accountId().equals(id))throw new AccountFailure(403,"OWNER_ACCOUNT_PROTECTED","The owner account cannot be changed here.");
    }
    private static AccountFailure confirmation() {
        return new AccountFailure(422,"ACCOUNT_CONFIRMATION_REQUIRED","Type the required confirmation word.");
    }
}
