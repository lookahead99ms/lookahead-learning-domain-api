package com.lookahead.domain.access;

import com.lookahead.domain.persistence.PersistenceStore;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DLV-970: the PostgreSQL statements of {@link RealAccountMaintenance}, verbatim. */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAccountMaintenanceStore implements AccountMaintenanceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcAccountMaintenanceStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc=jdbc; this.transactions=manager==null?null:new TransactionTemplate(manager); }
    @Override public void transaction(Runnable work) { if(transactions==null)work.run();else transactions.executeWithoutResult(status->work.run()); }
    @Override public void ensureSubject(UUID id) { jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",id); }
    @Override public boolean subjectExists(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_subjects WHERE id=?)",Boolean.class,id)); }
    @Override public boolean lockSubject(UUID id) { return !jdbc.queryForList("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",UUID.class,id).isEmpty(); }
    @Override public boolean alreadyDeleted(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM real_account_maintenance WHERE account_id=? AND action='delete')",Boolean.class,id)); }
    @Override public boolean testAccount(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)",Boolean.class,id)); }
    @Override public boolean author(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND author_access)",Boolean.class,id)); }
    @Override public boolean privileged(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM staff_access WHERE account_id=? AND (roles<>'[]'::jsonb OR topic_grants<>'[]'::jsonb OR full_curriculum))",Boolean.class,id)); }
    @Override public boolean grants(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_grants WHERE account_id=?)",Boolean.class,id)); }
    @Override public boolean openPurchases(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM membership_purchases WHERE account_id=? AND superseded_at IS NULL)",Boolean.class,id)); }
    @Override public void downgrade(UUID actor, UUID id) {
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.toString());
        jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=? AND (roles<>'[]'::jsonb OR topic_grants<>'[]'::jsonb OR full_curriculum)",id);
        jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);
        jdbc.update("UPDATE membership_purchases SET superseded_at=COALESCE(superseded_at,now()),updated_at=now() WHERE account_id=? AND superseded_at IS NULL",id);
        jdbc.update("INSERT INTO real_account_maintenance(id,account_id,actor_id,action) VALUES (?,?,?,'downgrade')",UUID.randomUUID(),id,actor);
    }
    @Override public void deleteLearner(UUID actor, UUID id) {
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.toString());
        jdbc.update("DELETE FROM free_plan_creations WHERE account_id=?",id);
        jdbc.update("DELETE FROM free_plan_deletions WHERE account_id=?",id);
        jdbc.update("DELETE FROM mutation_receipts WHERE account_id=?",id);
        jdbc.update("DELETE FROM plans WHERE account_id=?",id);
        jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);
        jdbc.update("DELETE FROM support_receipts WHERE account_id=?",id);
        jdbc.update("INSERT INTO real_account_maintenance(id,account_id,actor_id,action) VALUES (?,?,?,'delete')",UUID.randomUUID(),id,actor);
    }
}
