package com.lookahead.learning.content.repository;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.service.SupportFeedbackService;
import com.lookahead.learning.content.service.SupportReceiptStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Repository
@Profile("accounts")
public class SupportReceiptRepository implements SupportReceiptStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public SupportReceiptRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager);
    }
    @Override public Reservation reserve(String owner, String key, String digest) {
        return transaction.execute(status -> {
            UUID account=UUID.fromString(owner);
            // Platform subject lock serializes request-key creation and the per-account rate limit across replicas.
            var users=jdbc.queryForList("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",UUID.class,account);
            if (users.isEmpty()) throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue");
            var existing=jdbc.query("SELECT request_hash,reference,status FROM support_receipts WHERE account_id=? AND request_key=?",
                (rs,row)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},account,key);
            if (!existing.isEmpty()) {
                var stored=existing.getFirst();
                if (!stored[0].equals(digest)) throw new AccountFailure(409,"FEEDBACK_KEY_REUSED","Use the same message when checking an existing submission");
                return new Reservation(new SupportFeedbackService.Receipt(stored[1],stored[2]),false);
            }
            Integer recent=jdbc.queryForObject("SELECT count(*) FROM support_receipts WHERE account_id=? AND created_at>now()-interval '1 hour'",Integer.class,account);
            if (recent!=null && recent>=5) throw new AccountFailure(429,"FEEDBACK_RATE_LIMIT","Please wait before sending another message");
            String reference=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO support_receipts(account_id,request_key,request_hash,reference,status) VALUES (?,?,?,?,'unconfirmed')",account,key,digest,UUID.fromString(reference));
            return new Reservation(new SupportFeedbackService.Receipt(reference,"unconfirmed"),true);
        });
    }
    @Override public void accepted(String owner, String key) {
        transaction.executeWithoutResult(status -> jdbc.update("UPDATE support_receipts SET status='accepted',updated_at=now() WHERE account_id=? AND request_key=?",UUID.fromString(owner),key));
    }
}
