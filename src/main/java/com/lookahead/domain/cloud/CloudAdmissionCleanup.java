package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DLV-969: removes waiting rows nobody admitted within {@link #WAITING_EXPIRY}. Cloud mode only, under a
 * store's own serialisation (PostgreSQL: a transaction-scoped advisory lock; DynamoDB: one conditional delete per account) so two
 * tasks never both remove one account. Admitted rows are never touched (V17 trigger too).
 * DLV-971 (owner, 2026-11-06): run by {@link com.lookahead.domain.sweeps.RuntimeSweeps} at start and every 4 hours
 * (it was hourly on its own schedule).
 */
public final class CloudAdmissionCleanup {
    public static final Duration WAITING_EXPIRY=Duration.ofHours(48);
    static final String LOCK="cloud-admission-cleanup";
    private final AdmissionCleanupStore store;
    public CloudAdmissionCleanup(JdbcTemplate jdbc,PlatformTransactionManager manager){this(new JdbcAdmissionCleanupStore(jdbc,manager));}
    /** DLV-970: the store decides how expiry is serialised (advisory lock on PostgreSQL, conditional writes on DynamoDB). */
    public CloudAdmissionCleanup(AdmissionCleanupStore store){this.store=store;}

    /** One guarded run: the number of waiting rows removed; 0 when another task holds the lock or the run did not complete (logged). */
    public int run() {
        try {return expire();}
        catch(RuntimeException failure){
            String state=Events.sqlState(failure);
            var line=Events.failed(Events.EVENT,"admission.cleanup","UNEXPECTED").addKeyValue("error.type",Events.types(failure));
            if(state!=null)line=line.addKeyValue("db.sqlState",state);
            line.log("Admission cleanup skipped this run: {}{}; the next run tries again",Events.types(failure),state==null?"":" (SQL state "+state+")");
            return 0;
        }
    }
    /**
     * One line per run: how many waiting rows were removed, or that another task holds the lock; and one line per
     * removed account. The lines are written once the removal commits.
     * @return the number of waiting rows removed; 0 when another task holds the lock.
     */
    public int expire() { return store.expire(); }
    /**
     * Deletes one waiting account and every identity still on it (V19 also cascades, for older releases); the caller has
     * recorded the event. The 48-hour expiry and a merge (DLV-969 S36) remove a waiting account only this way.
     */
    public static void deleteWaiting(JdbcTemplate jdbc,UUID account){
        jdbc.update("DELETE FROM cloud_identities WHERE account_id=?",account);
        jdbc.update("DELETE FROM cloud_accounts WHERE account_id=? AND admitted=false",account);
        jdbc.update("DELETE FROM platform_subjects WHERE id=?",account);
    }
}
