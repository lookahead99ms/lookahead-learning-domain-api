package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DLV-969: removes waiting rows nobody admitted within {@link #WAITING_EXPIRY}. Cloud mode only, hourly, under a
 * transaction-scoped advisory lock so two tasks never both run it. Admitted rows are never touched (V17 trigger too).
 */
public final class CloudAdmissionCleanup {
    public static final Duration WAITING_EXPIRY=Duration.ofHours(48);
    static final String LOCK="cloud-admission-cleanup";
    private record Waiting(UUID accountId,String issuer,String subject) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public CloudAdmissionCleanup(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);}

    @Scheduled(fixedDelayString="PT1H",initialDelayString="PT5M")
    public void run() {
        try {expire();}
        catch(RuntimeException failure){
            Events.failed(Events.EVENT,"admission.cleanup","UNEXPECTED").addKeyValue("error.type",Events.types(failure))
                .log("Admission cleanup skipped this hour: {}; the next run tries again",Events.types(failure));
        }
    }
    /**
     * One line per run: how many waiting rows were removed, or that another task holds the lock; and one line per
     * removed account. The lines are written once the removal commits.
     * @return the number of waiting rows removed; 0 when another task holds the lock.
     */
    public int expire() {
        return transaction.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean.class,LOCK))){
                Events.success(Events.EVENT,"admission.cleanup").addKeyValue("count",0).log("Admission cleanup skipped: another task is running it");
                return 0;
            }
            var expired=jdbc.query("SELECT account_id,issuer,subject FROM cloud_accounts WHERE NOT admitted AND requested_at < clock_timestamp() - make_interval(hours => ?) FOR UPDATE SKIP LOCKED",
                (r,i)->new Waiting(r.getObject("account_id",UUID.class),r.getString("issuer"),r.getString("subject")),(int)WAITING_EXPIRY.toHours());
            for(Waiting row:expired){
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,NULL,'expire','[]'::jsonb)",UUID.randomUUID(),row.accountId,row.issuer,row.subject);
                deleteWaiting(jdbc,row.accountId);
                Events.afterCommit(()->Events.success(Events.EVENT,"admission.expired").addKeyValue("target.id",row.accountId)
                    .log("Waiting account {} expired: not admitted within {} h, removed with its sign-in identities",row.accountId,WAITING_EXPIRY.toHours()));
            }
            int removed=expired.size();
            Events.afterCommit(()->Events.success(Events.EVENT,"admission.cleanup").addKeyValue("count",removed)
                .log("Admission cleanup removed {} expired waiting account(s)",removed));
            return removed;
        });
    }
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
