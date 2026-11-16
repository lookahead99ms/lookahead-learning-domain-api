package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.PersistenceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent transactions model an external payment's durability: the SQL of the mock provider, unchanged. */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcMockPaymentStore implements MockPaymentStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcMockPaymentStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public PaymentGateway.Result record(String key,String paymentId,int amount,String currency,String state) {
        return tx.execute(s->{
            jdbc.update("INSERT INTO membership_mock_payments VALUES (?,?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING",key,paymentId,amount,currency,state);
            return findByKey(key);
        });
    }
    @Override public PaymentGateway.Result findByKey(String key) {
        return jdbc.query("SELECT * FROM membership_mock_payments WHERE idempotency_key=?",(r,n)->new PaymentGateway.Result(r.getString("payment_id"),r.getString("status"),r.getInt("amount_cents"),r.getString("currency")),key).stream().findFirst().orElse(null);
    }
    @Override public PaymentGateway.Result findById(String id) {
        return jdbc.query("SELECT * FROM membership_mock_payments WHERE payment_id=?",(r,n)->new PaymentGateway.Result(r.getString("payment_id"),r.getString("status"),r.getInt("amount_cents"),r.getString("currency")),id).stream().findFirst().orElse(null);
    }
}
