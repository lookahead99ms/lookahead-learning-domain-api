package com.lookahead.domain.membership;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.exception.AccountFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static com.lookahead.domain.membership.MembershipRules.*;

@Service
public class MembershipService {
    public record Buy(String planId,UUID replacesId,List<String> chosenCourses,Integer expectedPriceCents) {
        public Buy(String planId,UUID replacesId,List<String> chosenCourses){this(planId,replacesId,chosenCourses,null);}
    }
    public record Sale(String template,String greeting,Instant startsAt,Instant endsAt,List<String> planIds) {}
    /** DLV-969 (owner, 2026-11-04): the owner's or an admin's decision on a DEV purchase request: {@code approve} or {@code decline}. */
    public record Decision(UUID purchaseId,String decision) {}
    /** What a decision did, for its log lines once it has committed. */
    private record Decided(UUID target,ObjectNode purchase,boolean repeated,boolean settled) {}
    /** What the purchase transaction decided, for its log line once it has committed. {@code kind}: new, renewal, upgrade, change or staff-upgrade. */
    private record Requested(UUID id,boolean reused,String plan,int price,String kind,Integer fullPrice) {}
    static final int PAYMENT_ATTEMPTS=3;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final PaymentGateway payments;
    private final StaffAccessService staff;
    private final Clock clock;
    /** DEV (DLV-969, owner 2026-11-04): checkout is a request from a staff-created test account; Local and PROD never. */
    private final boolean ownerApproval;
    static final String TEST_ACCOUNTS_ONLY="In DEV, purchases are for test accounts; an owner or admin approves them.";
    // One local payment workflow at a time leaves a connection for the independent mock provider.
    private final java.util.concurrent.Semaphore paymentSlot = new java.util.concurrent.Semaphore(1, true);
    @org.springframework.beans.factory.annotation.Autowired
    public MembershipService(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,PaymentGateway payments,StaffAccessService staff) {
        this(jdbc,json,manager,payments,staff,Clock.systemUTC());
    }
    public MembershipService(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,PaymentGateway payments,StaffAccessService staff,Clock clock) {
        this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);this.payments=payments;this.staff=staff;this.clock=clock;
        this.ownerApproval=payments instanceof OwnerApprovalPaymentGateway;
    }
    private Instant now(){return clock.instant();}
    private ObjectNode parse(String value){return (ObjectNode)json.readTree(value);}
    private static Timestamp ts(Instant value){return Timestamp.from(value);}
    private Set<String> catalogs(JsonNode plan){var out=new HashSet<String>();plan.path("scope").path("catalogs").forEach(x->out.add(x.asText()));return out;}
    private ObjectNode definition(String id) {
        return jdbc.query("SELECT definition,price_cents FROM membership_plans WHERE id=?",(r,n)->{var p=parse(r.getString(1));p.put("priceCents",r.getInt(2));return p;},id)
            .stream().findFirst().orElseThrow(()->invalid("Unknown membership plan"));
    }
    public List<ObjectNode> catalog() {
        return jdbc.queryForList("SELECT id FROM membership_plans ORDER BY price_cents,id",String.class).stream().map(id->{var p=definition(id);p.put("onSale",p.path("pending").isEmpty()&&!id.equals("free")&&(!p.path("group").asText().equals("promo")||saleAllows(id)));return p;}).toList();
    }
    public ObjectNode activeSale() {
        return jdbc.query("SELECT * FROM membership_sales WHERE stopped_at IS NULL AND starts_at<=? AND ends_at>? ORDER BY starts_at DESC LIMIT 1",(r,n)->{
            var p=json.createObjectNode();p.put("id",r.getString("id"));p.put("template",r.getString("template"));p.put("greeting",r.getString("greeting"));p.put("startsAt",r.getTimestamp("starts_at").toInstant().toString());p.put("endsAt",r.getTimestamp("ends_at").toInstant().toString());p.set("planIds",json.readTree(r.getString("plan_ids")));return p;
        },ts(now()),ts(now())).stream().findFirst().orElse(null);
    }
    private boolean saleAllows(String id){var sale=activeSale();if(sale==null)return false;for(var value:sale.path("planIds"))if(value.asText().equals(id))return true;return false;}
    private void identity(AccountPrincipal actor){if(actor==null||!actor.isEnabled())throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue");}
    private void audit(UUID actor,String action,String subject,JsonNode detail){jdbc.update("INSERT INTO membership_audit(id,actor_id,action,subject_id,detail) VALUES (?,?,?,?,?::jsonb)",UUID.randomUUID(),actor,action,subject,detail.toString());}
    private ObjectNode purchase(UUID account,UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM membership_purchases WHERE account_id=? AND id=?"+(lock?" FOR UPDATE":""),(r,n)->{
            var p=json.createObjectNode();p.put("id",r.getString("id"));p.put("planId",r.getString("plan_id"));p.set("plan",json.readTree(r.getString("plan_snapshot")));p.put("pricePaidCents",r.getInt("price_paid_cents"));p.put("currency","USD");p.put("status",r.getString("status"));p.put("startsAt",r.getTimestamp("starts_at").toInstant().toString());p.put("endsAt",r.getTimestamp("ends_at").toInstant().toString());p.put("source",r.getString("source"));p.put("provider","mock");p.put("providerPaymentId",r.getString("provider_payment_id"));p.put("providerStatus",r.getString("provider_status"));p.put("replacesId",r.getString("replaces_id"));p.put("supersededAt",r.getTimestamp("superseded_at")==null?null:r.getTimestamp("superseded_at").toInstant().toString());p.put("fingerprint",r.getString("request_fingerprint"));p.putArray("courses");if(r.getString("discount")==null)p.putNull("discount");else p.set("discount",json.readTree(r.getString("discount")));return p;
        },account,id).stream().findFirst().orElseThrow(()->new AccountFailure(404,"PURCHASE_NOT_FOUND","Purchase not found"));
    }
    public ObjectNode get(AccountPrincipal actor,UUID id){identity(actor);var p=purchase(actor.accountId(),id,false);p.remove("fingerprint");return p;}
    public List<ObjectNode> access(AccountPrincipal actor,UUID target) {
        identity(actor);
        if (!actor.accountId().equals(target)) {
            var permissions = staff.permissions(actor);
            if (!permissions.manageAccess() && !permissions.triageFeedback()
                    && !"co-author".equals(permissions.feedbackRole()))
                throw new AccountFailure(403, "STAFF_REQUIRED", "Author, co-author or admin required");
        }
        return jdbc.queryForList("SELECT id FROM membership_purchases WHERE account_id=? AND status IN ('succeeded','scheduled','expired') ORDER BY created_at DESC",UUID.class,target).stream().map(id->{
            var p=purchase(target,id,false);p.remove("fingerprint");Instant start=Instant.parse(p.path("startsAt").asText()),end=Instant.parse(p.path("endsAt").asText());
            p.put("status",!p.path("supersededAt").isNull()||!now().isBefore(end)?"expired":now().isBefore(start)?"scheduled":"active");return p;
        }).toList();
    }
    /**
     * DEV: only a test account staff created (the roster or "Create test account": an active registry row with a
     * creator, on the owner's domain) may purchase, through a test session. Everyone else: 403. Local: no check.
     */
    private void requireTestAccount(AccountPrincipal actor){
        if(!ownerApproval)return;
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=? AND creation_state='active' AND creator_id IS NOT NULL AND username LIKE '%@lookahead.dev')",Boolean.class,actor.accountId())))
            throw new AccountFailure(403,"PURCHASES_TEST_ACCOUNTS_ONLY",TEST_ACCOUNTS_ONLY);
    }
    private void requireStaff(AccountPrincipal actor){var permissions=staff.permissions(actor);if(!permissions.manageAccess()&&!permissions.triageFeedback())throw new AccountFailure(403,"STAFF_REQUIRED","Author or admin required");}
    private void accountLock(UUID account){if(jdbc.queryForList("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",UUID.class,account).isEmpty())throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","Account not found");}
    private String fingerprint(Buy request,String source,UUID actor){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((request.planId()+"|"+request.replacesId()+"|"+source+"|"+actor).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public ObjectNode buy(AccountPrincipal actor,UUID target,Buy request,String key,boolean staffUpgrade) {return buy(actor,target,request,key,staffUpgrade,null);}
    /** {@code requestedBy}: DEV, the staff account using the test account's session (recorded on the request), or null. */
    public ObjectNode buy(AccountPrincipal actor,UUID target,Buy request,String key,boolean staffUpgrade,UUID requestedBy) {
        UUID who=actor==null?null:actor.accountId();
        acquireSlot("purchase.request",who,target);
        try{return buyWithinSlot(actor,target,request,key,staffUpgrade,requestedBy);}
        catch(AccountFailure refused){
            // Membership refusals carry fixed server text (MembershipRules and this class), never what the caller sent.
            Events.refused(Events.PAYMENT,"purchase.request",refused.code()).addKeyValue("account.id",who).addKeyValue("target.id",target)
                .addKeyValue("plan.code",request==null?null:planCode(request.planId()))
                .log("Purchase for account {} refused: {} ({})",target,refused.code(),refused.getMessage());
            throw refused;
        }
        finally{paymentSlot.release();}
    }
    /** One payment workflow at a time: 429 when none finished within 3 s, 503 when interrupted while waiting. */
    private void acquireSlot(String action,UUID who,UUID target){
        try {
            if(!paymentSlot.tryAcquire(3,java.util.concurrent.TimeUnit.SECONDS)){
                Events.limit(action,"PAYMENT_BUSY","payment-slot",1,"3s").addKeyValue("account.id",who).addKeyValue("target.id",target)
                    .log("Payment busy for account {}: one payment runs at a time and none finished within 3 s; refused with 429, retry with the same key",target);
                throw new AccountFailure(429,"PAYMENT_BUSY","Payment service is busy. Retry with the same key");
            }
        } catch(InterruptedException e){
            Thread.currentThread().interrupt();
            Events.failed(Events.PAYMENT,action,"PAYMENT_INTERRUPTED").addKeyValue("account.id",who).addKeyValue("target.id",target)
                .log("Purchase for account {} interrupted while waiting for the payment slot; refused with 503, retry with the same key",target);
            throw new AccountFailure(503,"PAYMENT_INTERRUPTED","Retry with the same key");
        }
    }
    /** A plan code is logged only when it is one: lowercase letters, digits and dashes. */
    private static String planCode(String id){return id!=null&&id.matches("[a-z0-9-]{1,64}")?id:null;}
    private ObjectNode buyWithinSlot(AccountPrincipal actor,UUID target,Buy request,String key,boolean staffUpgrade,UUID requestedBy) {
        identity(actor);if(staffUpgrade)requireStaff(actor);else if(!actor.accountId().equals(target))throw new AccountFailure(403,"ACCOUNT_MISMATCH","Wrong account");
        if(!staffUpgrade)requireTestAccount(actor);
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,128}"))throw invalid("A valid Idempotency-Key is required");
        if(request==null||request.planId()==null||(request.chosenCourses()!=null&&!request.chosenCourses().isEmpty()))throw invalid("Course purchases are not available");
        String source=staffUpgrade?"staff-upgrade":"checkout",fingerprint=fingerprint(request,source,actor.accountId());
        Requested requested=tx.execute(s->{
            jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.accountId().toString());
            accountLock(target);
            var existing=jdbc.queryForList("SELECT id FROM membership_purchases WHERE account_id=? AND idempotency_key=?",UUID.class,target,key);
            if(!existing.isEmpty()){var prior=purchase(target,existing.getFirst(),false);if(!fingerprint.equals(prior.path("fingerprint").asText()))throw new AccountFailure(409,"IDEMPOTENCY_CONFLICT","Key was used for a different request");return new Requested(existing.getFirst(),true,prior.path("planId").asText(),prior.path("pricePaidCents").asInt(),null,null);}
            // A DEV request that a Reset superseded grants nothing and no longer blocks; every other pending purchase does.
            var pending=jdbc.queryForList("SELECT coalesce(provider_status,'') FROM membership_purchases WHERE account_id=? AND status='pending' AND (coalesce(provider_status,'')<>? OR superseded_at IS NULL)",String.class,target,OwnerApprovalPaymentGateway.AWAITING);
            if(pending.contains(OwnerApprovalPaymentGateway.AWAITING))throw new AccountFailure(409,"PURCHASE_REQUEST_PENDING","A request is already waiting for the owner or an admin.");
            if(!pending.isEmpty())throw new AccountFailure(409,"PURCHASE_PENDING","Resolve the pending purchase before starting another");
            ObjectNode plan=definition(request.planId());
            if(!plan.path("pending").isEmpty()||request.planId().equals("free"))throw invalid("Plan is not purchasable");
            if(plan.path("group").asText().equals("promo")&&!saleAllows(request.planId()))throw new AccountFailure(409,"SALE_INACTIVE","This promo is available only during its sale");
            Instant starts=now(),ends=end(starts,plan.path("duration").path("kind").asText(),plan.path("duration").path("count").asInt());int price=plan.path("priceCents").asInt();
            String kind="new";Integer fullPrice=null;
            if(request.replacesId()!=null){
                var old=purchase(target,request.replacesId(),true);var previous=old.path("plan");Instant oldStart=Instant.parse(old.path("startsAt").asText()),oldEnd=Instant.parse(old.path("endsAt").asText());
                if(!Set.of("succeeded","scheduled").contains(old.path("status").asText())||!old.path("supersededAt").isNull()||now().isBefore(oldStart)||!now().isBefore(oldEnd))throw invalid("An active purchase is required");
                if(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE replaces_id=? AND status IN ('pending','scheduled')",Integer.class,request.replacesId())>0)throw new AccountFailure(409,"CHANGE_ALREADY_SCHEDULED","An existing change is already scheduled");
                if(old.path("planId").asText().equals(request.planId())){if(staffUpgrade)throw new AccountFailure(409,"STAFF_DOWNGRADE_REJECTED","Choose a larger plan");starts=oldEnd;ends=end(starts,plan.path("duration").path("kind").asText(),plan.path("duration").path("count").asInt());kind="renewal";}
                else {
                if(!YEARLY.contains(old.path("planId").asText())||!YEARLY.contains(request.planId()))throw invalid("Change rules apply only between different yearly plans");
                if(upgrade(catalogs(previous),catalogs(plan))){fullPrice=price-previous.path("priceCents").asInt();price=prorateHalfUpCents(fullPrice,oldStart,oldEnd,now());ends=oldEnd;kind="upgrade";}
                else {if(staffUpgrade)throw new AccountFailure(409,"STAFF_DOWNGRADE_REJECTED","Staff cannot reduce or replace access");requireChangeWindow(now(),oldEnd);starts=oldEnd;ends=end(starts,"years",1);kind="change";}
                }
            } else {
                var active=access(actor,target).stream().filter(p->p.path("status").asText().equals("active")||p.path("status").asText().equals("scheduled")).toList();
                if(!active.isEmpty())throw new AccountFailure(409,"EXISTING_MEMBERSHIP","Select an explicit plan change for existing access");
            }
            if(staffUpgrade){price=0;kind="staff-upgrade";}
            else if(request.expectedPriceCents()!=null&&request.expectedPriceCents()!=price)throw new AccountFailure(409,"PRICE_CHANGED","The price changed. Review a fresh quote before purchasing");
            UUID next=UUID.randomUUID();jdbc.update("INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,price_paid_cents,status,starts_at,ends_at,source,actor_id,provider,replaces_id) VALUES (?,?,?,?::jsonb,?,?,?,'pending',?,?,?,?, 'mock',?)",next,target,request.planId(),plan.toString(),fingerprint,key,price,ts(starts),ts(ends),source,actor.accountId(),request.replacesId());
            // DEV: the request's audit row also names who used the test account (its purchase actor is the account itself).
            var created=plan;if(ownerApproval&&!staffUpgrade&&requestedBy!=null){created=plan.deepCopy();created.put("requestedBy",requestedBy.toString());}
            audit(actor.accountId(),"purchase-created",next.toString(),created);
            return new Requested(next,false,request.planId(),price,kind,fullPrice);
        });
        var line=(requested.reused()?Events.success(Events.PAYMENT,"purchase.reused"):Events.success(Events.PAYMENT,"purchase.requested"))
            .addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",target).addKeyValue("purchase.id",requested.id())
            .addKeyValue("plan.code",planCode(requested.plan())).addKeyValue("price.cents",requested.price());
        if(requested.reused())line.log("Purchase {} for account {} requested again with the same Idempotency-Key: the existing purchase is used",requested.id(),target);
        else {
            if(requested.fullPrice()!=null)line=line.addKeyValue("price.difference",requested.fullPrice());
            line.addKeyValue("purchase.kind",requested.kind()).log("Purchase {} requested for account {} by account {}: plan {} ({}), price {} cents{}",requested.id(),target,actor.accountId(),
                planCode(requested.plan()),requested.kind(),requested.price(),requested.fullPrice()==null?"":", prorated from a difference of "+requested.fullPrice()+" cents for the remaining term");
        }
        return process(actor,target,requested.id());
    }
    private ObjectNode process(AccountPrincipal actor,UUID target,UUID id) {
        var settled=new boolean[1];
        var done=tx.execute(s->{
            jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.accountId().toString());
            accountLock(target);var p=purchase(target,id,true);
            if(!p.path("status").asText().equals("pending")){p.remove("fingerprint");return p;}
            // A DEV request waits for its decision: retrying it never asks the provider again.
            if(p.path("providerStatus").asText().equals(OwnerApprovalPaymentGateway.AWAITING)){p.remove("fingerprint");return p;}
            PaymentGateway.Result result=null;long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            int already=jdbc.queryForObject("SELECT count(*) FROM membership_payment_attempts WHERE purchase_id=?",Integer.class,id);
            boolean staff=p.path("source").asText().equals("staff-upgrade");
            int tried=already;String last=null;boolean stopped=false;
            for(int attempt=already;attempt<PAYMENT_ATTEMPTS&&System.nanoTime()<deadline;attempt++) {
                tried=attempt+1;
                try {
                    result=staff?new PaymentGateway.Result("pi_mock_staff_"+id,"succeeded",0,"USD"):payments.create("membership-"+id,p.path("pricePaidCents").asInt(),"USD");
                    jdbc.update("INSERT INTO membership_payment_attempts VALUES (?,?,?,NULL,now())",UUID.randomUUID(),id,result.status());break;
                } catch(PaymentGateway.Failure failure){
                    jdbc.update("INSERT INTO membership_payment_attempts VALUES (?,?,?,?,now())",UUID.randomUUID(),id,"error",failure.getMessage());
                    last=failure.getMessage();
                    if(!failure.transientFailure()){
                        jdbc.update("UPDATE membership_purchases SET status='failed',provider_status='validation-failed' WHERE id=?",id);
                        attemptLine(Events.failed(Events.PAYMENT,"payment.attempt",last),actor,target,id,tried)
                            .log("Payment attempt {} of {} for purchase {} failed: {} (not retryable); purchase failed, no access granted",tried,PAYMENT_ATTEMPTS,id,last);
                        stopped=true;break;
                    }
                    if(attempt<PAYMENT_ATTEMPTS-1){
                        long pause=java.util.concurrent.ThreadLocalRandom.current().nextLong(25L<<attempt,50L<<attempt);
                        attemptLine(Events.retry(Events.PAYMENT,"payment.attempt",last,tried,PAYMENT_ATTEMPTS),actor,target,id,tried)
                            .log("Payment attempt {} of {} for purchase {} failed: {} (retryable); retrying in {} ms",tried,PAYMENT_ATTEMPTS,id,last,pause);
                        try{Thread.sleep(pause);}catch(InterruptedException interrupted){
                            Thread.currentThread().interrupt();
                            attemptLine(Events.failed(Events.PAYMENT,"payment.attempt","PAYMENT_INTERRUPTED"),actor,target,id,tried)
                                .log("Payment for purchase {} stopped after attempt {} of {}: interrupted while waiting to retry; purchase stays pending",id,tried,PAYMENT_ATTEMPTS);
                            stopped=true;break;
                        }
                    }
                }
            }
            if(result==null&&!stopped){
                if(tried==already)attemptLine(Events.failed(Events.PAYMENT,"payment.attempt","ATTEMPTS_USED"),actor,target,id,tried)
                    .log("Payment for purchase {} not tried again: all {} attempts were used earlier; purchase stays pending",id,PAYMENT_ATTEMPTS);
                else if(tried>=PAYMENT_ATTEMPTS)attemptLine(Events.failed(Events.PAYMENT,"payment.attempt",last),actor,target,id,tried)
                    .log("Payment for purchase {} failed after {} attempts: {}; purchase stays pending, no access granted",id,tried,last);
                else attemptLine(Events.failed(Events.PAYMENT,"payment.attempt","PAYMENT_DEADLINE"),actor,target,id,tried)
                    .log("Payment for purchase {} stopped after attempt {} of {}: the 3-second payment deadline passed (last: {}); purchase stays pending",id,tried,PAYMENT_ATTEMPTS,last);
            }
            if(result!=null){
                String provider=Events.safe(result.status());
                switch(result.status()){
                    case "succeeded" -> attemptLine(Events.success(Events.PAYMENT,"payment.attempt"),actor,target,id,tried)
                        .log(staff?"Staff upgrade purchase {} needs no payment: granted on attempt {} of {}":"Payment for purchase {} succeeded on attempt {} of {}",id,tried,PAYMENT_ATTEMPTS);
                    case "requires_payment_method" -> attemptLine(Events.refused(Events.PAYMENT,"payment.attempt","DECLINED"),actor,target,id,tried)
                        .log("Payment for purchase {} declined by the provider on attempt {} of {} (requires_payment_method): not retried; purchase failed, no access granted",id,tried,PAYMENT_ATTEMPTS);
                    case OwnerApprovalPaymentGateway.AWAITING -> Events.success(Events.PAYMENT,"purchase.approval-requested").addKeyValue("account.id",actor.accountId())
                        .addKeyValue("target.id",target).addKeyValue("purchase.id",id).addKeyValue("plan.code",planCode(p.path("planId").asText()))
                        .addKeyValue("price.cents",p.path("pricePaidCents").asInt())
                        .log("Purchase {} for account {} is a request: it waits for the owner or an admin to approve it; nothing is paid and no access is given until then",id,target);
                    case "requires_action" -> attemptLine(Events.refused(Events.PAYMENT,"payment.attempt","REQUIRES_ACTION"),actor,target,id,tried)
                        .log("Payment for purchase {} needs customer action on attempt {} of {} (requires_action); purchase stays pending, no access yet",id,tried,PAYMENT_ATTEMPTS);
                    default -> attemptLine(Events.refused(Events.PAYMENT,"payment.attempt","NOT_SETTLED"),actor,target,id,tried).addKeyValue("provider.status",provider)
                        .log("Payment for purchase {} not settled on attempt {} of {} (provider status {}); purchase stays pending",id,tried,PAYMENT_ATTEMPTS,provider);
                }
            }
            if(result!=null&&apply(actor,target,id,p,result))settled[0]=true;
            var finished=purchase(target,id,false);finished.remove("fingerprint");return finished;
        });
        if(settled[0])membershipLine(actor,target,done);
        return done;
    }
    /** The provider's answer on the purchase: succeeded or scheduled (and the replaced one superseded), failed, or pending. */
    private boolean apply(AccountPrincipal actor,UUID target,UUID id,ObjectNode p,PaymentGateway.Result result){
        if(result.amountCents()!=p.path("pricePaidCents").asInt()||!result.currency().equals("USD"))throw new IllegalStateException("Provider amount mismatch");
        boolean success=result.status().equals("succeeded");
        String state=success?(Instant.parse(p.path("startsAt").asText()).isAfter(now())?"scheduled":"succeeded"):result.status().equals("requires_payment_method")?"failed":"pending";
        jdbc.update("UPDATE membership_purchases SET status=?,provider_payment_id=?,provider_status=?,updated_at=now() WHERE id=?",state,result.id(),result.status(),id);
        if(!success)return false;
        int inserted=jdbc.update("INSERT INTO membership_provider_events(provider,event_id,purchase_id) VALUES ('mock',?,?) ON CONFLICT DO NOTHING",result.id()+":succeeded",id);
        if(inserted!=1)return false;
        if(!p.path("replacesId").isNull()&&!state.equals("scheduled"))jdbc.update("UPDATE membership_purchases SET superseded_at=? WHERE id=? AND account_id=?",ts(now()),UUID.fromString(p.path("replacesId").asText()),target);
        audit(actor.accountId(),"purchase-succeeded",id.toString(),json.createObjectNode().put("providerPaymentId",result.id()));
        return true;
    }
    private static org.slf4j.spi.LoggingEventBuilder attemptLine(org.slf4j.spi.LoggingEventBuilder line,AccountPrincipal actor,UUID target,UUID purchase,int attempt){
        return line.addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",target).addKeyValue("purchase.id",purchase)
            .addKeyValue("retry.attempt",attempt).addKeyValue("retry.max",PAYMENT_ATTEMPTS);
    }
    /** Committed: the membership this purchase gives, starts or changes. */
    private static void membershipLine(AccountPrincipal actor,UUID target,ObjectNode done){
        String plan=planCode(done.path("planId").asText()),purchase=done.path("id").asText(),replaces=done.path("replacesId").isNull()?null:done.path("replacesId").asText();
        boolean scheduled=done.path("status").asText().equals("scheduled");
        String action=scheduled?"membership.scheduled":replaces==null?"membership.activated":"membership.upgraded";
        var line=Events.success(Events.PAYMENT,action).addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",target)
            .addKeyValue("purchase.id",purchase).addKeyValue("plan.code",plan);
        if(replaces!=null)line=line.addKeyValue("purchase.replaces",replaces);
        if(scheduled)line.log("Membership for account {} scheduled: plan {} from {} until {} (purchase {}{})",target,plan,done.path("startsAt").asText(),
            done.path("endsAt").asText(),purchase,replaces==null?"":", after purchase "+replaces);
        else if(replaces==null)line.log("Membership activated for account {}: plan {} until {} (purchase {})",target,plan,done.path("endsAt").asText(),purchase);
        else line.log("Membership of account {} upgraded to plan {} until {}: purchase {} replaces purchase {}",target,plan,done.path("endsAt").asText(),purchase,replaces);
    }
    public ObjectNode quote(AccountPrincipal actor,Buy request) {
        identity(actor);
        try{requireTestAccount(actor);}
        catch(AccountFailure refused){
            Events.refused(Events.PAYMENT,"purchase.quote",refused.code()).addKeyValue("account.id",actor.accountId())
                .log("Quote for account {} refused: {} ({})",actor.accountId(),refused.code(),refused.getMessage());
            throw refused;
        }
        if(request==null||request.planId()==null||(request.chosenCourses()!=null&&!request.chosenCourses().isEmpty()))throw invalid("Course purchases are not available");var plan=definition(request.planId());
        if(!plan.path("pending").isEmpty()||request.planId().equals("free"))throw invalid("Plan is not purchasable");
        if(plan.path("group").asText().equals("promo")&&!saleAllows(request.planId()))throw new AccountFailure(409,"SALE_INACTIVE","Sale is not active");
        Instant starts=now(),ends=end(starts,plan.path("duration").path("kind").asText(),plan.path("duration").path("count").asInt());int price=plan.path("priceCents").asInt();
        if(request.replacesId()!=null){var old=purchase(actor.accountId(),request.replacesId(),false);Instant oldStart=Instant.parse(old.path("startsAt").asText()),oldEnd=Instant.parse(old.path("endsAt").asText());
            if(!Set.of("succeeded","scheduled").contains(old.path("status").asText())||!old.path("supersededAt").isNull()||now().isBefore(oldStart)||!now().isBefore(oldEnd))throw invalid("An active purchase is required");
            if(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE replaces_id=? AND status IN ('pending','scheduled')",Integer.class,request.replacesId())>0)throw new AccountFailure(409,"CHANGE_ALREADY_SCHEDULED","An existing change is already scheduled");
            if(old.path("planId").asText().equals(request.planId())){starts=oldEnd;ends=end(starts,plan.path("duration").path("kind").asText(),plan.path("duration").path("count").asInt());}
            else {
            if(!YEARLY.contains(old.path("planId").asText())||!YEARLY.contains(request.planId())||!old.path("supersededAt").isNull()||now().isBefore(oldStart)||!now().isBefore(oldEnd)||!Set.of("succeeded","scheduled").contains(old.path("status").asText()))throw invalid("An active yearly plan is required");
            if(upgrade(catalogs(old.path("plan")),catalogs(plan))){price=prorateHalfUpCents(price-old.path("plan").path("priceCents").asInt(),oldStart,oldEnd,now());ends=oldEnd;}
            else {requireChangeWindow(now(),oldEnd);starts=oldEnd;ends=end(starts,"years",1);}
            }
        }
        var quote=json.createObjectNode().put("priceCents",price).put("currency","USD").put("startsAt",starts.toString()).put("endsAt",ends.toString()).put("renewal","manual").put("mock",true);
        // DEV: buying sends a request that the owner or an admin approves.
        if(ownerApproval)quote.put("ownerApproval",true);
        return quote;
    }
    /** A DEV purchase request waiting for a decision: its description, and who sent it (null when unknown). */
    public record PendingRequest(ObjectNode body,UUID requestedBy) {}
    /**
     * DLV-969 (owner, 2026-11-04): the DEV purchase request a test account is waiting on, for the owner's and admins'
     * test-account list; null when none (always in Local). A request a Reset superseded no longer counts. The body:
     * {@code purchaseId, kind (new|renewal|upgrade|change), fromPlan {code,name} (Free for a new plan), toPlan {code,name},
     * priceCents, currency, priceDifferenceCents (upgrade: the full difference before proration), startsAt, endsAt,
     * requestedAt}. Course Pass purchases are refused at checkout, so no request has courses.
     */
    public PendingRequest pendingRequest(UUID account){
        if(!ownerApproval)return null;
        var ids=jdbc.queryForList("SELECT id FROM membership_purchases WHERE account_id=? AND status='pending' AND provider_status=? AND superseded_at IS NULL ORDER BY created_at,id LIMIT 1",UUID.class,account,OwnerApprovalPaymentGateway.AWAITING);
        if(ids.isEmpty())return null;
        UUID id=ids.getFirst();
        var p=purchase(account,id,false);
        Timestamp requested=jdbc.queryForObject("SELECT created_at FROM membership_purchases WHERE id=?",Timestamp.class,id);
        var old=replaced(account,p);
        String kind=requestKind(old,p);
        var body=json.createObjectNode().put("purchaseId",id.toString()).put("kind",kind);
        body.putObject("fromPlan").put("code",old==null?"free":old.path("planId").asText()).put("name",old==null?"Free":old.path("plan").path("name").asText());
        body.putObject("toPlan").put("code",p.path("planId").asText()).put("name",p.path("plan").path("name").asText());
        body.put("priceCents",p.path("pricePaidCents").asInt()).put("currency","USD");
        if(kind.equals("upgrade"))body.put("priceDifferenceCents",p.path("plan").path("priceCents").asInt()-old.path("plan").path("priceCents").asInt());
        body.put("startsAt",p.path("startsAt").asText()).put("endsAt",p.path("endsAt").asText()).put("requestedAt",requested.toInstant().toString());
        return new PendingRequest(body,requestedBy(id));
    }
    /** The purchase a request replaces, or null for a new plan. */
    private ObjectNode replaced(UUID account,ObjectNode p){
        return p.path("replacesId").isNull()?null:purchase(account,UUID.fromString(p.path("replacesId").asText()),false);
    }
    /** new, renewal (same plan), upgrade (more catalogs) or change. */
    private String requestKind(ObjectNode old,ObjectNode p){
        return old==null?"new":old.path("planId").asText().equals(p.path("planId").asText())?"renewal"
            :upgrade(catalogs(old.path("plan")),catalogs(p.path("plan")))?"upgrade":"change";
    }
    /** Who sent a DEV request: the gate holder its purchase-created audit row names, or null when unknown. */
    private UUID requestedBy(UUID purchase){
        return jdbc.queryForList("SELECT detail->>'requestedBy' FROM membership_audit WHERE subject_id=? AND action='purchase-created'",String.class,purchase.toString())
            .stream().filter(Objects::nonNull).findFirst().map(UUID::fromString).orElse(null);
    }
    /**
     * An approved DEV purchase request: what it bought ({@code kind} new, renewal, upgrade or change; the plan), who sent
     * it ({@code requestedBy}, null when unknown), who decided ({@code approvedBy}, the purchase-approved audit row's
     * actor) and when.
     */
    public record ApprovedRequest(UUID purchaseId,String kind,String planCode,String planName,UUID requestedBy,UUID approvedBy,Instant approvedAt) {}
    /**
     * DLV-969 (owner, 2026-11-04): the latest request approved for the DEV test account {@code account} since its latest
     * Reset or Restore (its latest {@code test_account_resets} row; any time when it was never reset) whose purchase was
     * paid or scheduled; null when none, and always in Local. A request the mock provider declined on approval does not
     * count. Derived from the purchase-approved audit row; one query when there is none.
     */
    public ApprovedRequest approvedRequest(UUID account){
        if(!ownerApproval)return null;
        var rows=jdbc.query("""
                SELECT p.id,a.actor_id,a.recorded_at FROM membership_audit a JOIN membership_purchases p ON a.subject_id=p.id::text
                WHERE p.account_id=? AND a.action='purchase-approved' AND p.status IN ('succeeded','scheduled')
                  AND a.recorded_at>COALESCE((SELECT max(r.recorded_at) FROM test_account_resets r WHERE r.account_id=p.account_id),'-infinity'::timestamptz)
                ORDER BY a.recorded_at DESC,a.id DESC LIMIT 1
                """,(r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant()},account);
        if(rows.isEmpty())return null;
        UUID id=(UUID)rows.getFirst()[0];
        var p=purchase(account,id,false);
        return new ApprovedRequest(id,requestKind(replaced(account,p),p),p.path("planId").asText(),p.path("plan").path("name").asText(),
            requestedBy(id),(UUID)rows.getFirst()[1],(Instant)rows.getFirst()[2]);
    }
    /**
     * DLV-969 (owner, 2026-11-04): the owner or an admin approves or declines a DEV purchase request. {@code role} is
     * the decider's place in the test-account hierarchy ({@code owner}, {@code admin}, ...), {@code mayDecideFor} whether
     * that hierarchy lets them act on the requesting account. Approve pays through the mock provider and applies the
     * membership exactly as a successful checkout; decline fails the request (provider_status declined_by_owner) and
     * changes nothing. Repeating the same decision answers the same result.
     */
    public ObjectNode decide(AccountPrincipal actor,Decision request,String role,java.util.function.Predicate<UUID> mayDecideFor){
        UUID who=actor==null?null:actor.accountId(),id=request==null?null:request.purchaseId();
        String decision=request==null?null:request.decision();
        Decided decided;
        try{
            identity(actor);
            if(!ownerApproval)throw new AccountFailure(403,"PURCHASE_APPROVAL_DISABLED","Owner approval of purchases exists only in DEV.");
            if(!Set.of("owner","admin").contains(String.valueOf(role)))throw forbiddenDecision();
            if(id==null||!Set.of("approve","decline").contains(String.valueOf(decision)))
                throw new AccountFailure(422,"PURCHASE_DECISION_INVALID","Give the purchaseId and the decision approve or decline.");
            acquireSlot("purchase.decision",who,null);
            try{decided=tx.execute(s->decideWithin(actor,id,decision,mayDecideFor));}
            finally{paymentSlot.release();}
        }catch(AccountFailure refused){
            Events.refused(Events.PAYMENT,"purchase.decision",refused.code()).addKeyValue("account.id",who).addKeyValue("actor.role",role)
                .addKeyValue("purchase.id",id).log("Decision on purchase request {} refused: {} ({})",id,refused.code(),refused.getMessage());
            throw refused;
        }
        var done=decided.purchase();
        if(decided.settled())membershipLine(actor,decided.target(),done);
        boolean approve=decision.equals("approve");
        Events.success(Events.PAYMENT,approve?"purchase.approved":"purchase.declined").addKeyValue("account.id",who).addKeyValue("actor.role",role)
            .addKeyValue("target.id",decided.target()).addKeyValue("purchase.id",id).addKeyValue("plan.code",planCode(done.path("planId").asText()))
            .addKeyValue("price.cents",done.path("pricePaidCents").asInt()).addKeyValue("repeated",decided.repeated())
            .log(decided.repeated()?"Purchase request {} of account {} was already {} ({}); the same decision by account {} ({}) changes nothing"
                :"Purchase request {} of account {} {} ({}) by account {} ({})",id,decided.target(),approve?"approved":"declined",done.path("status").asText(),who,role);
        var answer=json.createObjectNode().put("decision",decision).put("repeated",decided.repeated());
        answer.set("purchase",done);
        return answer;
    }
    private static AccountFailure forbiddenDecision(){return new AccountFailure(403,"PURCHASE_APPROVAL_FORBIDDEN","Only the owner or an admin can approve purchase requests.");}
    private Decided decideWithin(AccountPrincipal actor,UUID id,String decision,java.util.function.Predicate<UUID> mayDecideFor){
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.accountId().toString());
        var accounts=jdbc.queryForList("SELECT account_id FROM membership_purchases WHERE id=?",UUID.class,id);
        if(accounts.isEmpty())throw new AccountFailure(404,"PURCHASE_NOT_FOUND","Purchase not found");
        UUID target=accounts.getFirst();
        if(mayDecideFor==null||!mayDecideFor.test(target))throw forbiddenDecision();
        accountLock(target);
        var p=purchase(target,id,true);
        var prior=jdbc.queryForList("SELECT action FROM membership_audit WHERE subject_id=? AND action IN ('purchase-approved','purchase-declined')",String.class,id.toString());
        if(!prior.isEmpty()){
            if(!prior.getFirst().equals("purchase-"+(decision.equals("approve")?"approved":"declined")))
                throw new AccountFailure(409,"PURCHASE_REQUEST_DECIDED","This request was already decided.");
            p.remove("fingerprint");return new Decided(target,p,true,false);
        }
        if(!p.path("status").asText().equals("pending")||!p.path("providerStatus").asText().equals(OwnerApprovalPaymentGateway.AWAITING))
            throw new AccountFailure(409,"PURCHASE_NOT_A_REQUEST","This purchase is not waiting for a decision.");
        var detail=json.createObjectNode().put("planId",p.path("planId").asText()).put("pricePaidCents",p.path("pricePaidCents").asInt());
        if(decision.equals("decline")){
            jdbc.update("UPDATE membership_purchases SET status='failed',provider_status=?,updated_at=now() WHERE id=?",OwnerApprovalPaymentGateway.DECLINED,id);
            audit(actor.accountId(),"purchase-declined",id.toString(),detail);
            var finished=purchase(target,id,false);finished.remove("fingerprint");return new Decided(target,finished,false,false);
        }
        // The account must still be where the request found it: not reset since, and a replaced purchase still active.
        boolean stale=!p.path("supersededAt").isNull();
        if(!stale&&!p.path("replacesId").isNull()){
            var old=purchase(target,UUID.fromString(p.path("replacesId").asText()),true);
            stale=!Set.of("succeeded","scheduled").contains(old.path("status").asText())||!old.path("supersededAt").isNull()||!now().isBefore(Instant.parse(old.path("endsAt").asText()));
        }
        if(stale)throw new AccountFailure(409,"PURCHASE_REQUEST_STALE","The test account changed after this request. Decline it and send a new one.");
        PaymentGateway.Result result;
        try{result=((OwnerApprovalPaymentGateway)payments).approve("membership-"+id,p.path("pricePaidCents").asInt(),"USD");}
        catch(PaymentGateway.Failure failure){throw new AccountFailure(503,"PAYMENT_UNAVAILABLE","The mock payment did not complete. Approve again.");}
        if(!Set.of("succeeded","requires_payment_method").contains(result.status()))
            throw new AccountFailure(503,"PAYMENT_UNAVAILABLE","The mock payment did not complete. Approve again.");
        jdbc.update("INSERT INTO membership_payment_attempts VALUES (?,?,?,NULL,now())",UUID.randomUUID(),id,result.status());
        boolean settled=apply(actor,target,id,p,result);
        audit(actor.accountId(),"purchase-approved",id.toString(),detail);
        var finished=purchase(target,id,false);finished.remove("fingerprint");return new Decided(target,finished,false,settled);
    }
    public ObjectNode startSale(AccountPrincipal actor,Sale sale) {
        try {
            var receipt=startSaleChecked(actor,sale);
            Events.success(Events.PAYMENT,"sale.started").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",receipt.path("id").asText())
                .addKeyValue("plan.codes",sale.planIds())
                .log("Owner started sale {} ({}) for plan(s) {} from {} until {}",receipt.path("id").asText(),sale.template(),sale.planIds(),sale.startsAt(),sale.endsAt());
            return receipt;
        } catch(AccountFailure refused) {
            Events.refused(Events.PAYMENT,"sale.start",refused.code()).addKeyValue("account.id",actor==null?null:actor.accountId())
                .log("Starting a sale refused: {} ({})",refused.code(),refused.getMessage());
            throw refused;
        }
    }
    private ObjectNode startSaleChecked(AccountPrincipal actor,Sale sale) {
        staff.requireOwner(actor);
        if(sale==null||!Set.of("holiday","spring","graduation","engineers-day").contains(sale.template())||sale.greeting()==null||sale.greeting().isBlank()||sale.greeting().length()>500||sale.startsAt()==null||sale.endsAt()==null||!sale.endsAt().isAfter(sale.startsAt())||!sale.endsAt().isAfter(now())||sale.planIds()==null||sale.planIds().isEmpty()||new HashSet<>(sale.planIds()).size()!=sale.planIds().size())throw invalid("Invalid sale");
        for(String id:sale.planIds())if(!definition(id).path("group").asText().equals("promo"))throw invalid("Only promo plans belong to a sale");
        return tx.execute(s->{jdbc.execute("LOCK TABLE membership_sales IN SHARE ROW EXCLUSIVE MODE");if(jdbc.queryForObject("SELECT count(*) FROM membership_sales WHERE stopped_at IS NULL AND starts_at<? AND ends_at>?",Integer.class,ts(sale.endsAt()),ts(sale.startsAt()))>0)throw new AccountFailure(409,"SALE_OVERLAP","A sale already covers this period");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO membership_sales(id,template,greeting,starts_at,ends_at,plan_ids,created_by) VALUES (?,?,?,?,?,?::jsonb,?)",id,sale.template(),sale.greeting(),ts(sale.startsAt()),ts(sale.endsAt()),json.writeValueAsString(sale.planIds()),actor.accountId());var receipt=json.createObjectNode().put("id",id.toString());audit(actor.accountId(),"sale-started",id.toString(),receipt);return receipt;});
    }
    public void stopSale(AccountPrincipal actor,UUID id){
        try {
            staff.requireOwner(actor);tx.executeWithoutResult(s->{if(jdbc.update("UPDATE membership_sales SET stopped_at=COALESCE(stopped_at,?) WHERE id=?",ts(now()),id)!=1)throw new AccountFailure(404,"SALE_NOT_FOUND","Sale not found");audit(actor.accountId(),"sale-stopped",id.toString(),json.createObjectNode());});
        } catch(AccountFailure refused) {
            Events.refused(Events.PAYMENT,"sale.stop",refused.code()).addKeyValue("account.id",actor==null?null:actor.accountId()).addKeyValue("target.id",id)
                .log("Stopping sale {} refused: {}",id,refused.code());
            throw refused;
        }
        Events.success(Events.PAYMENT,"sale.stopped").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",id).log("Owner stopped sale {}",id);
    }
    public ObjectNode price(AccountPrincipal actor,String id,int price){
        try {
            staff.requireOwner(actor);if(price<1||price>10000000||id.equals("free"))throw invalid("Invalid price");
            int[] before={0};
            var p=tx.execute(s->{before[0]=definition(id).path("priceCents").asInt();jdbc.update("UPDATE membership_plans SET price_cents=?,updated_at=now() WHERE id=?",price,id);var changed=definition(id);audit(actor.accountId(),"price-changed",id,changed);return changed;});
            Events.success(Events.PAYMENT,"plan.price-changed").addKeyValue("account.id",actor.accountId()).addKeyValue("plan.code",planCode(id))
                .addKeyValue("price.cents",price).addKeyValue("price.before",before[0])
                .log("Owner changed the price of plan {} from {} to {} cents",planCode(id),before[0],price);
            return p;
        } catch(AccountFailure refused) {
            Events.refused(Events.PAYMENT,"plan.price-change",refused.code()).addKeyValue("account.id",actor==null?null:actor.accountId()).addKeyValue("plan.code",planCode(id))
                .log("Price change for plan {} refused: {} ({})",planCode(id),refused.code(),refused.getMessage());
            throw refused;
        }
    }
}
