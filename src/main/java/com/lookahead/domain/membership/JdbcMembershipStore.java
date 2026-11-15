package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.learning.content.exception.AccountFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lookahead.domain.membership.MembershipRules.invalid;

/** The PostgreSQL membership aggregate: the SQL {@link MembershipService} ran before DLV-970, unchanged. */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcMembershipStore implements MembershipStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public JdbcMembershipStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    private ObjectNode parse(String value){return (ObjectNode)json.readTree(value);}
    private static Timestamp ts(Instant value){return Timestamp.from(value);}
    private ObjectNode definitionRow(String id) {
        return jdbc.query("SELECT definition,price_cents FROM membership_plans WHERE id=?",(r,n)->{var p=parse(r.getString(1));p.put("priceCents",r.getInt(2));return p;},id)
            .stream().findFirst().orElseThrow(()->invalid("Unknown membership plan"));
    }
    @Override public ObjectNode definition(String id){
        return jdbc.query("SELECT definition,price_cents FROM membership_plans WHERE id=?",(r,n)->{var p=parse(r.getString(1));p.put("priceCents",r.getInt(2));return p;},id)
            .stream().findFirst().orElse(null);
    }
    @Override public List<ObjectNode> catalog() {
        return jdbc.queryForList("SELECT id FROM membership_plans ORDER BY price_cents,id",String.class).stream().map(this::definitionRow).toList();
    }
    @Override public ObjectNode activeSale(Instant now) {
        return jdbc.query("SELECT * FROM membership_sales WHERE stopped_at IS NULL AND starts_at<=? AND ends_at>? ORDER BY starts_at DESC LIMIT 1",(r,n)->{
            var p=json.createObjectNode();p.put("id",r.getString("id"));p.put("template",r.getString("template"));p.put("greeting",r.getString("greeting"));p.put("startsAt",r.getTimestamp("starts_at").toInstant().toString());p.put("endsAt",r.getTimestamp("ends_at").toInstant().toString());p.set("planIds",json.readTree(r.getString("plan_ids")));return p;
        },ts(now),ts(now)).stream().findFirst().orElse(null);
    }
    private void audit(UUID actor,String action,String subject,JsonNode detail){jdbc.update("INSERT INTO membership_audit(id,actor_id,action,subject_id,detail) VALUES (?,?,?,?,?::jsonb)",UUID.randomUUID(),actor,action,subject,detail.toString());}
    private ObjectNode purchaseRow(UUID account,UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM membership_purchases WHERE account_id=? AND id=?"+(lock?" FOR UPDATE":""),(r,n)->{
            var p=json.createObjectNode();p.put("id",r.getString("id"));p.put("planId",r.getString("plan_id"));p.set("plan",json.readTree(r.getString("plan_snapshot")));p.put("pricePaidCents",r.getInt("price_paid_cents"));p.put("currency","USD");p.put("status",r.getString("status"));p.put("startsAt",r.getTimestamp("starts_at").toInstant().toString());p.put("endsAt",r.getTimestamp("ends_at").toInstant().toString());p.put("source",r.getString("source"));p.put("provider","mock");p.put("providerPaymentId",r.getString("provider_payment_id"));p.put("providerStatus",r.getString("provider_status"));p.put("replacesId",r.getString("replaces_id"));p.put("supersededAt",r.getTimestamp("superseded_at")==null?null:r.getTimestamp("superseded_at").toInstant().toString());p.put("fingerprint",r.getString("request_fingerprint"));p.putArray("courses");if(r.getString("discount")==null)p.putNull("discount");else p.set("discount",json.readTree(r.getString("discount")));return p;
        },account,id).stream().findFirst().orElse(null);
    }
    @Override public ObjectNode purchase(UUID account,UUID id){return purchaseRow(account,id,false);}
    @Override public List<UUID> accessIds(UUID account){
        return jdbc.queryForList("SELECT id FROM membership_purchases WHERE account_id=? AND status IN ('succeeded','scheduled','expired') ORDER BY created_at DESC",UUID.class,account);
    }
    @Override public List<JsonNode> activeSnapshots(UUID account,Instant now){
        Timestamp at=Timestamp.from(now);
        return jdbc.query("""
            SELECT plan_snapshot FROM membership_purchases WHERE account_id=?
            AND status IN ('succeeded','scheduled') AND superseded_at IS NULL
            AND starts_at<=? AND ends_at>? ORDER BY plan_id,id
            """,(r,n)->json.readTree(r.getString(1)),account,at,at);
    }
    @Override public boolean staffCreatedTestAccount(UUID account){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=? AND creation_state='active' AND creator_id IS NOT NULL AND username LIKE '%@lookahead.dev')",Boolean.class,account));
    }
    @Override public int replacementsScheduled(UUID replacesId){
        return jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE replaces_id=? AND status IN ('pending','scheduled')",Integer.class,replacesId);
    }
    @Override public PendingRow pendingRequest(UUID account,String awaiting){
        var ids=jdbc.queryForList("SELECT id FROM membership_purchases WHERE account_id=? AND status='pending' AND provider_status=? AND superseded_at IS NULL ORDER BY created_at,id LIMIT 1",UUID.class,account,awaiting);
        if(ids.isEmpty())return null;
        UUID id=ids.getFirst();
        Timestamp requested=jdbc.queryForObject("SELECT created_at FROM membership_purchases WHERE id=?",Timestamp.class,id);
        return new PendingRow(id,requested.toInstant());
    }
    @Override public UUID requestedBy(UUID purchase){
        return jdbc.queryForList("SELECT detail->>'requestedBy' FROM membership_audit WHERE subject_id=? AND action='purchase-created'",String.class,purchase.toString())
            .stream().filter(Objects::nonNull).findFirst().map(UUID::fromString).orElse(null);
    }
    @Override public Approval approvedRequest(UUID account){
        var rows=jdbc.query("""
                SELECT p.id,a.actor_id,a.recorded_at FROM membership_audit a JOIN membership_purchases p ON a.subject_id=p.id::text
                WHERE p.account_id=? AND a.action='purchase-approved' AND p.status IN ('succeeded','scheduled')
                  AND a.recorded_at>COALESCE((SELECT max(r.recorded_at) FROM test_account_resets r WHERE r.account_id=p.account_id),'-infinity'::timestamptz)
                ORDER BY a.recorded_at DESC,a.id DESC LIMIT 1
                """,(r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant()},account);
        if(rows.isEmpty())return null;
        return new Approval((UUID)rows.getFirst()[0],(UUID)rows.getFirst()[1],(Instant)rows.getFirst()[2]);
    }
    @Override public <T> T transact(UUID actor,Function<Work,T> body){
        return tx.execute(s->{
            jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.toString());
            return body.apply(new JdbcWork());
        });
    }
    @Override public PriceChange changePrice(UUID actor,String id,int price){
        int[] before={0};
        var changed=tx.execute(s->{before[0]=definitionRow(id).path("priceCents").asInt();jdbc.update("UPDATE membership_plans SET price_cents=?,updated_at=now() WHERE id=?",price,id);var now=definitionRow(id);audit(actor,"price-changed",id,now);return now;});
        return new PriceChange(before[0],changed);
    }
    @Override public UUID startSale(UUID actor,MembershipService.Sale sale){
        return tx.execute(s->{jdbc.execute("LOCK TABLE membership_sales IN SHARE ROW EXCLUSIVE MODE");if(jdbc.queryForObject("SELECT count(*) FROM membership_sales WHERE stopped_at IS NULL AND starts_at<? AND ends_at>?",Integer.class,ts(sale.endsAt()),ts(sale.startsAt()))>0)throw new AccountFailure(409,"SALE_OVERLAP","A sale already covers this period");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO membership_sales(id,template,greeting,starts_at,ends_at,plan_ids,created_by) VALUES (?,?,?,?,?,?::jsonb,?)",id,sale.template(),sale.greeting(),ts(sale.startsAt()),ts(sale.endsAt()),json.writeValueAsString(sale.planIds()),actor);var receipt=json.createObjectNode().put("id",id.toString());audit(actor,"sale-started",id.toString(),receipt);return id;});
    }
    @Override public void stopSale(UUID actor,UUID id,Instant now){
        tx.executeWithoutResult(s->{if(jdbc.update("UPDATE membership_sales SET stopped_at=COALESCE(stopped_at,?) WHERE id=?",ts(now),id)!=1)throw new AccountFailure(404,"SALE_NOT_FOUND","Sale not found");audit(actor,"sale-stopped",id.toString(),json.createObjectNode());});
    }

    /** The in-transaction statements, verbatim. */
    private final class JdbcWork implements Work {
        @Override public void lockAccount(UUID account){if(jdbc.queryForList("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",UUID.class,account).isEmpty())throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","Account not found");}
        @Override public UUID purchaseAccount(UUID purchaseId){
            var accounts=jdbc.queryForList("SELECT account_id FROM membership_purchases WHERE id=?",UUID.class,purchaseId);
            return accounts.isEmpty()?null:accounts.getFirst();
        }
        @Override public ObjectNode purchase(UUID account,UUID id,boolean lock){return purchaseRow(account,id,lock);}
        @Override public List<UUID> purchasesByKey(UUID account,String key){return jdbc.queryForList("SELECT id FROM membership_purchases WHERE account_id=? AND idempotency_key=?",UUID.class,account,key);}
        @Override public List<String> pendingProviderStatuses(UUID account,String awaiting){
            return jdbc.queryForList("SELECT coalesce(provider_status,'') FROM membership_purchases WHERE account_id=? AND status='pending' AND (coalesce(provider_status,'')<>? OR superseded_at IS NULL)",String.class,account,awaiting);
        }
        @Override public ObjectNode definition(String id){return JdbcMembershipStore.this.definition(id);}
        @Override public ObjectNode activeSale(Instant now){return JdbcMembershipStore.this.activeSale(now);}
        @Override public int replacementsScheduled(UUID replacesId){return JdbcMembershipStore.this.replacementsScheduled(replacesId);}
        @Override public List<UUID> accessIds(UUID account){return JdbcMembershipStore.this.accessIds(account);}
        @Override public void insertPurchase(NewPurchase p){
            jdbc.update("INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,price_paid_cents,status,starts_at,ends_at,source,actor_id,provider,replaces_id) VALUES (?,?,?,?::jsonb,?,?,?,'pending',?,?,?,?, 'mock',?)",p.id(),p.account(),p.planId(),p.plan().toString(),p.fingerprint(),p.key(),p.price(),ts(p.startsAt()),ts(p.endsAt()),p.source(),p.actor(),p.replacesId());
        }
        @Override public int paymentAttempts(UUID purchase){return jdbc.queryForObject("SELECT count(*) FROM membership_payment_attempts WHERE purchase_id=?",Integer.class,purchase);}
        @Override public void recordAttempt(UUID purchase,String status,String error){
            if(error==null)jdbc.update("INSERT INTO membership_payment_attempts VALUES (?,?,?,NULL,now())",UUID.randomUUID(),purchase,status);
            else jdbc.update("INSERT INTO membership_payment_attempts VALUES (?,?,?,?,now())",UUID.randomUUID(),purchase,status,error);
        }
        @Override public void failValidation(UUID purchase){jdbc.update("UPDATE membership_purchases SET status='failed',provider_status='validation-failed' WHERE id=?",purchase);}
        @Override public void decline(UUID purchase,String providerStatus){jdbc.update("UPDATE membership_purchases SET status='failed',provider_status=?,updated_at=now() WHERE id=?",providerStatus,purchase);}
        @Override public void settle(UUID purchase,String state,String providerPaymentId,String providerStatus){
            jdbc.update("UPDATE membership_purchases SET status=?,provider_payment_id=?,provider_status=?,updated_at=now() WHERE id=?",state,providerPaymentId,providerStatus,purchase);
        }
        @Override public boolean recordProviderEvent(String eventId,UUID purchase){
            return jdbc.update("INSERT INTO membership_provider_events(provider,event_id,purchase_id) VALUES ('mock',?,?) ON CONFLICT DO NOTHING",eventId,purchase)==1;
        }
        @Override public void supersede(UUID account,UUID purchase,Instant at){jdbc.update("UPDATE membership_purchases SET superseded_at=? WHERE id=? AND account_id=?",ts(at),purchase,account);}
        @Override public void audit(UUID actor,String action,String subject,JsonNode detail){JdbcMembershipStore.this.audit(actor,action,subject,detail);}
        @Override public List<String> decisions(UUID purchase){
            return jdbc.queryForList("SELECT action FROM membership_audit WHERE subject_id=? AND action IN ('purchase-approved','purchase-declined')",String.class,purchase.toString());
        }
    }
}
