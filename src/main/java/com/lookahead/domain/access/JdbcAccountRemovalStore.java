package com.lookahead.domain.access;

import com.lookahead.domain.cloud.CloudIdentities;
import com.lookahead.domain.persistence.PersistenceStore;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.lookahead.domain.access.AccountRemovalService.Changed;
import com.lookahead.domain.access.AccountRemovalService.Target;
import com.lookahead.learning.content.exception.AccountFailure;
import static com.lookahead.domain.access.AccountRemovalService.notSignIn;
import static com.lookahead.domain.access.AccountRemovalService.removed;

/** DLV-970: the PostgreSQL statements of {@link AccountRemovalService}, verbatim: one transaction under the account's row lock. */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAccountRemovalStore implements AccountRemovalStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcAccountRemovalStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); }

    @Override public Target options(UUID id) {
        var rows = jdbc.query("SELECT a.author_access,a.admitted,s.state FROM cloud_accounts a LEFT JOIN cloud_account_states s USING(account_id) WHERE a.account_id=?",
            (r, i) -> new Object[]{r.getBoolean(1), r.getBoolean(2), r.getString(3)}, id);
        if (rows.isEmpty() || (Boolean) rows.getFirst()[0] || !(Boolean) rows.getFirst()[1]) return null;
        String state = (String) rows.getFirst()[2];
        return new Target(null, null, null, true, false, state, List.of());
    }
    @Override public Changed disable(UUID actorId, UUID id) {
        var actor = new com.lookahead.learning.content.security.AccountPrincipal(actorId, null, null, true);
        var target = change(actor, id, "disable", t -> {
            if ("removed".equals(t.state())) throw removed();
            if (t.state() == null) {
                jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'disabled',?)", id, actor.accountId());
                revokeSignIns(id);
                return true;
            }
            return false;
        });
        return target;
    }
    @Override public Changed enable(UUID actorId, UUID id) {
        var actor = new com.lookahead.learning.content.security.AccountPrincipal(actorId, null, null, true);
        var target = change(actor, id, "enable", t -> {
            if ("removed".equals(t.state())) throw removed();
            return jdbc.update("DELETE FROM cloud_account_states WHERE account_id=? AND state='disabled'", id) == 1;
        });
        return target;
    }
    @Override public Changed remove(UUID actorId, UUID id) {
        var actor = new com.lookahead.learning.content.security.AccountPrincipal(actorId, null, null, true);
        var target = change(actor, id, "remove", t -> {
            if ("removed".equals(t.state())) return false;
            if (!t.admitted()) {
                // DLV-971: a waiting row has no learning data and no permanent record to keep; it goes the way an expired
                // request goes, so the same person signing in again appears as a new request.
                jdbc.update("DELETE FROM cloud_account_states WHERE account_id=?", id);
                revokeSignIns(id);
                com.lookahead.domain.cloud.CloudAdmissionCleanup.deleteWaiting(jdbc, id);
                return true;
            }
            jdbc.update("""
                INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)
                ON CONFLICT (account_id) DO UPDATE SET state='removed',changed_at=clock_timestamp(),changed_by=EXCLUDED.changed_by
                """, id, actor.accountId());
            revokeSignIns(id);
            erase(actor.accountId(), id);
            return true;
        });
        return target;
    }

    /** Checks, then one transaction under the account's row lock; an event is recorded only when something changed. */
    private Changed change(com.lookahead.learning.content.security.AccountPrincipal actor, UUID id, String action, java.util.function.Function<Target, Boolean> apply) {
        return transactions.execute(status -> {
            var rows = jdbc.query("""
                SELECT a.issuer,a.subject,a.username,a.admitted,a.author_access,s.state
                FROM cloud_accounts a LEFT JOIN cloud_account_states s USING(account_id) WHERE a.account_id=? FOR UPDATE OF a
                """, (r, i) -> new Target(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4), r.getBoolean(5), r.getString(6), List.of()), id);
            // DLV-971 (owner, 2026-11-06): a waiting row (not admitted) can be disabled or deleted too: Reject on Waiting for access.
            if (rows.isEmpty()) throw notSignIn();
            var row = rows.getFirst();
            // DLV-969 S36: the state again, in a statement of its own under the lock: the locking statement's join keeps
            // its starting snapshot, so a Disable, Delete or merge committed while it waited would go unseen (and its
            // insert would then fail as a duplicate, a 503).
            String state = jdbc.queryForList("SELECT state FROM cloud_account_states WHERE account_id=?", String.class, id).stream().findFirst().orElse(null);
            var target = new Target(row.issuer(), row.subject(), row.username(), row.admitted(), row.author(), state,
                CloudIdentities.usernames(jdbc, id, row.username()));
            if (target.author()) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "An author account cannot be changed here.");
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)", Boolean.class, id)))
                throw new AccountFailure(403, "TEST_ACCOUNT_ROUTE_REQUIRED", "Use the Test accounts page for managed accounts.");
            boolean changed = apply.apply(target);
            if (changed)
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), id, target.issuer(), target.subject(), actor.accountId(), action);
            return new Changed(target, changed);
        });
    }

    /** Ends every sign-in and pending sign-in choice of the account; in the caller's transaction (also the S36 merge). */
    @Override public void revokeSignIns(UUID id) {
        jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=?", id);
        jdbc.update("UPDATE cloud_sign_in_challenges SET cancelled=true WHERE account_id=?", id);
    }

    /**
     * Roles, grants, plans, receipts and profile settings; purchase history stays for the audit. The one deletion of an
     * account's data: Delete here and the DLV-969 S36 merge (the account not kept) both use it, inside the caller's
     * transaction and after the caller has locked the account row.
     */
    @Override public void erase(UUID actor, UUID id) {
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)", rs -> {}, actor.toString());
        jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=? AND (roles<>'[]'::jsonb OR topic_grants<>'[]'::jsonb OR full_curriculum)", id);
        jdbc.update("UPDATE membership_purchases SET superseded_at=COALESCE(superseded_at,now()),updated_at=now() WHERE account_id=? AND superseded_at IS NULL", id);
        // The free-plan allowance rows stay: the runtime may not delete them, and a returning person has a new account id.
        for (String table : new String[]{"account_grants", "mutation_receipts", "plans", "support_receipts",
                "cloud_account_preferences", "cloud_account_profile_links"})
            jdbc.update("DELETE FROM " + table + " WHERE account_id=?", id);
    }

}
