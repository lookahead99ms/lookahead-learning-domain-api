package com.lookahead.domain.access;

import com.lookahead.domain.testaccounts.TestAccountPolicy;
import com.lookahead.domain.testaccounts.TestIdentityOperator;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner maintenance of real accounts. Identity disables Local sign-in before data erasure. */
@Service
public class RealAccountMaintenance {
    public record Confirmation(String confirmation) {}
    private final JdbcTemplate jdbc;
    private final StaffAccessService staff;
    private final AccountDirectory directory;
    private final TestAccountPolicy localPolicy;
    private final TestIdentityOperator identity;
    private final TransactionTemplate transactions;

    public RealAccountMaintenance(JdbcTemplate jdbc, StaffAccessService staff, AccountDirectory directory,
            TestAccountPolicy localPolicy, TestIdentityOperator identity, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.staff=staff;this.directory=directory;this.localPolicy=localPolicy;
        this.identity=identity;this.transactions=new TransactionTemplate(manager);
    }

    @Transactional
    public void downgrade(AccountPrincipal actor, UUID id, Confirmation request) {
        staff.requireOwner(actor);
        requireOther(actor,id);
        if(request==null||!"DOWNGRADE".equals(request.confirmation()))throw confirmation();
        directory.byId(id);
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",id);
        requireReal(id);
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.accountId().toString());
        jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=? AND (roles<>'[]'::jsonb OR topic_grants<>'[]'::jsonb OR full_curriculum)",id);
        jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);
        jdbc.update("UPDATE membership_purchases SET superseded_at=COALESCE(superseded_at,now()),updated_at=now() WHERE account_id=? AND superseded_at IS NULL",id);
        jdbc.update("INSERT INTO real_account_maintenance(id,account_id,actor_id,action) VALUES (?,?,?,'downgrade')",UUID.randomUUID(),id,actor.accountId());
    }

    public void deleteLearner(AccountPrincipal actor, UUID id, Confirmation request) {
        localPolicy.requireEnabled();
        staff.requireOwner(actor);
        requireOther(actor,id);
        if(request==null||!"DELETE".equals(request.confirmation()))throw confirmation();
        Boolean alreadyDeleted=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM real_account_maintenance WHERE account_id=? AND action='delete')",Boolean.class,id);
        if(Boolean.TRUE.equals(alreadyDeleted))return;
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_subjects WHERE id=?)",Boolean.class,id))) {
            directory.byId(id);
            jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",id);
        }
        transactions.executeWithoutResult(status -> requireFreeLearner(id));
        identity.disableReal(id);
        transactions.executeWithoutResult(status -> {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM real_account_maintenance WHERE account_id=? AND action='delete')",Boolean.class,id)))return;
            requireFreeLearner(id);
            jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.accountId().toString());
            jdbc.update("DELETE FROM free_plan_creations WHERE account_id=?",id);
            jdbc.update("DELETE FROM free_plan_deletions WHERE account_id=?",id);
            jdbc.update("DELETE FROM mutation_receipts WHERE account_id=?",id);
            jdbc.update("DELETE FROM plans WHERE account_id=?",id);
            jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);
            jdbc.update("DELETE FROM support_receipts WHERE account_id=?",id);
            jdbc.update("INSERT INTO real_account_maintenance(id,account_id,actor_id,action) VALUES (?,?,?,'delete')",UUID.randomUUID(),id,actor.accountId());
        });
    }

    private void requireFreeLearner(UUID id) {
        var rows=jdbc.queryForList("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",UUID.class,id);
        if(rows.isEmpty())throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","Account not found.");
        requireReal(id);
        Boolean privileged=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM staff_access WHERE account_id=? AND (roles<>'[]'::jsonb OR topic_grants<>'[]'::jsonb OR full_curriculum))",Boolean.class,id);
        Boolean grants=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_grants WHERE account_id=?)",Boolean.class,id);
        Boolean purchases=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM membership_purchases WHERE account_id=? AND superseded_at IS NULL)",Boolean.class,id);
        if(Boolean.TRUE.equals(privileged)||Boolean.TRUE.equals(grants)||Boolean.TRUE.equals(purchases))
            throw new AccountFailure(409,"DOWNGRADE_REQUIRED","Downgrade access to a free learner before deleting this account.");
    }
    private void requireReal(UUID id) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)",Boolean.class,id)))
            throw new AccountFailure(403,"TEST_ACCOUNT_ROUTE_REQUIRED","Use the Test accounts page for managed accounts.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND author_access)",Boolean.class,id)))
            throw new AccountFailure(403,"OWNER_ACCOUNT_PROTECTED","An author account cannot be changed here.");
    }
    private static void requireOther(AccountPrincipal actor, UUID id) {
        if(id==null||actor.accountId().equals(id))throw new AccountFailure(403,"OWNER_ACCOUNT_PROTECTED","The owner account cannot be changed here.");
    }
    private static AccountFailure confirmation() {
        return new AccountFailure(422,"ACCOUNT_CONFIRMATION_REQUIRED","Type the required confirmation word.");
    }
}
