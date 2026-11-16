package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DLV-970: the PostgreSQL expiry of {@link CloudAdmissionCleanup}, verbatim: one guarded transaction under an advisory lock. */
public final class JdbcAdmissionCleanupStore implements AdmissionCleanupStore {
    static final String LOCK=CloudAdmissionCleanup.LOCK;
    static final java.time.Duration WAITING_EXPIRY=CloudAdmissionCleanup.WAITING_EXPIRY;
    private record Waiting(UUID accountId,String issuer,String subject) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcAdmissionCleanupStore(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);}

    /**
     * One line per run: how many waiting rows were removed, or that another task holds the lock; and one line per
     * removed account. The lines are written once the removal commits.
     * @return the number of waiting rows removed; 0 when another task holds the lock.
     */
    @Override public int expire() {
        return transaction.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean.class,LOCK))){
                Events.success(Events.EVENT,"admission.cleanup").addKeyValue("count",0).log("Admission cleanup skipped: another task is running it");
                return 0;
            }
            // DLV-971: a waiting row the owner disabled keeps its state and its block; only untouched requests expire.
            var expired=jdbc.query("SELECT account_id,issuer,subject FROM cloud_accounts WHERE NOT admitted AND requested_at < clock_timestamp() - make_interval(hours => ?) AND NOT EXISTS (SELECT 1 FROM cloud_account_states s WHERE s.account_id=cloud_accounts.account_id) FOR UPDATE SKIP LOCKED",
                (r,i)->new Waiting(r.getObject("account_id",UUID.class),r.getString("issuer"),r.getString("subject")),(int)WAITING_EXPIRY.toHours());
            for(Waiting row:expired){
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,NULL,'expire','[]'::jsonb)",UUID.randomUUID(),row.accountId,row.issuer,row.subject);
                CloudAdmissionCleanup.deleteWaiting(jdbc,row.accountId);
                Events.afterCommit(()->Events.success(Events.EVENT,"admission.expired").addKeyValue("target.id",row.accountId)
                    .log("Waiting account {} expired: not admitted within {} h, removed with its sign-in identities",row.accountId,WAITING_EXPIRY.toHours()));
            }
            int removed=expired.size();
            Events.afterCommit(()->Events.success(Events.EVENT,"admission.cleanup").addKeyValue("count",removed)
                .log("Admission cleanup removed {} expired waiting account(s)",removed));
            return removed;
        });
    }
}
