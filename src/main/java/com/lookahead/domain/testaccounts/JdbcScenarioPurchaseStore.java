package com.lookahead.domain.testaccounts;

import com.lookahead.domain.persistence.PersistenceStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The fixture SQL of {@link ScenarioPurchases}, unchanged, inside the caller's transaction. */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcScenarioPurchaseStore implements ScenarioPurchaseStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcScenarioPurchaseStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    @Override public ObjectNode definition(String id) {
        return jdbc.query("SELECT definition,price_cents FROM membership_plans WHERE id=?",(row,n)-> {
            var result=(ObjectNode)json.readTree(row.getString(1)); result.put("priceCents",row.getInt(2)); return result;
        },id).stream().findFirst().orElseThrow(()->new IllegalStateException("Scenario membership is unavailable"));
    }
    @Override public void insertPurchase(UUID id, UUID account, UUID actor, ObjectNode plan, Instant startsAt, Instant endsAt, int price,
            String discount, UUID replaces, boolean staff, String payment, Instant superseded) {
        jdbc.update("""
            INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,
              price_paid_cents,discount,status,starts_at,ends_at,source,actor_id,provider,provider_payment_id,
              provider_status,replaces_id,superseded_at)
            VALUES (?,?,?,?::jsonb,?,?,?,?::jsonb,'succeeded',?,?,?,?,?,?,?,?,?)
            """,id,account,plan.path("id").asText(),plan.toString(),"0".repeat(64),"fixture-"+id,price,discount,
                Timestamp.from(startsAt),Timestamp.from(endsAt),staff?"staff-upgrade":"checkout",actor,
                "mock",payment,staff?"not-required":"succeeded",replaces,superseded==null?null:Timestamp.from(superseded));
        if(payment!=null) jdbc.update("INSERT INTO membership_mock_payments(idempotency_key,payment_id,amount_cents,currency,status) VALUES (?,?,?,'USD','succeeded')",
                "fixture-"+id,payment,price);
    }
    @Override public void audit(UUID actor, String action, String subject, JsonNode detail) {
        jdbc.update("INSERT INTO membership_audit(id,actor_id,action,subject_id,detail) VALUES (?,?,?,?,?::jsonb)",
                UUID.randomUUID(),actor,action,subject,detail.toString());
    }
}
