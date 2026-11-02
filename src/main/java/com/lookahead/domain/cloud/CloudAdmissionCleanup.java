package com.lookahead.domain.cloud;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log=LoggerFactory.getLogger(CloudAdmissionCleanup.class);
    private record Waiting(UUID accountId,String issuer,String subject) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public CloudAdmissionCleanup(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);}

    @Scheduled(fixedDelayString="PT1H",initialDelayString="PT5M")
    public void run() {
        try {int removed=expire();if(removed>0)log.info("Admission cleanup removed {} expired waiting account(s)",removed);}
        catch(RuntimeException failure){log.warn("Admission cleanup skipped: {}",failure.toString());}
    }
    /** @return the number of waiting rows removed; 0 when another task holds the lock. */
    public int expire() {
        return transaction.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean.class,LOCK)))return 0;
            var expired=jdbc.query("SELECT account_id,issuer,subject FROM cloud_accounts WHERE NOT admitted AND requested_at < clock_timestamp() - make_interval(hours => ?) FOR UPDATE SKIP LOCKED",
                (r,i)->new Waiting(r.getObject("account_id",UUID.class),r.getString("issuer"),r.getString("subject")),(int)WAITING_EXPIRY.toHours());
            for(Waiting row:expired){
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,NULL,'expire','[]'::jsonb)",UUID.randomUUID(),row.accountId,row.issuer,row.subject);
                jdbc.update("DELETE FROM cloud_accounts WHERE account_id=? AND admitted=false",row.accountId);
                jdbc.update("DELETE FROM platform_subjects WHERE id=?",row.accountId);
            }
            return expired.size();
        });
    }
}
