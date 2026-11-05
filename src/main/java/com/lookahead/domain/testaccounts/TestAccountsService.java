package com.lookahead.domain.testaccounts;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.*;
import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

@Service
public class TestAccountsService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TestAccountPolicy policy;
    private final ScenarioPurchases purchases;private final ScenarioPlans plans;private final StaffAccessService staff;
    private final AccessSummaryService summaries;private final SnapshotValidator catalog;
    public TestAccountsService(JdbcTemplate jdbc,ObjectMapper json,TestAccountPolicy policy,ScenarioPurchases purchases,
            ScenarioPlans plans,StaffAccessService staff,AccessSummaryService summaries,SnapshotValidator catalog){
        this.jdbc=jdbc;this.json=json;this.policy=policy;this.purchases=purchases;this.plans=plans;this.staff=staff;this.summaries=summaries;this.catalog=catalog;
    }
    /**
     * {@code expectedRevision} is a {@code Long}: a JSON reader refuses an absent value for a primitive, which answered
     * 400 MALFORMED_JSON; without it the reset now answers 428 REVISION_REQUIRED (DLV-969, 2026-11-04).
     */
    public record Reset(UUID key,Long expectedRevision,String confirmation,String preset) {
        public Reset(UUID key,long expectedRevision,String confirmation,String preset){this(key,Long.valueOf(expectedRevision),confirmation,preset);}
        /** True only when the body sent this revision. */
        public boolean expects(long revision){return expectedRevision!=null&&expectedRevision==revision;}
    }
    private void actor(String actor){jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor);}
    private StaffAliases aliases;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureAliases(StaffAliases aliases){this.aliases=aliases;}
    private com.lookahead.domain.membership.MembershipService memberships;
    /** DLV-969 (owner, 2026-11-04): DEV purchase requests, listed for the owner and admins and decided by them. */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureMemberships(com.lookahead.domain.membership.MembershipService memberships){this.memberships=memberships;}
    /** DLV-969 (owner, 2026-11-04): the "upgraded by" marker, from the wired memberships and aliases (none: no marker). */
    private UpgradeMarkers markers(){return new UpgradeMarkers(jdbc,memberships,aliases);}
    /**
     * DLV-969 (owner, 2026-11-04): the owner or an admin approves or declines a test account's DEV purchase request,
     * for an account the one hierarchy lets them act on ({@link TestAccountTemplates#allows}); everyone else 403
     * PURCHASE_APPROVAL_FORBIDDEN. Outside DEV: 403 PURCHASE_APPROVAL_DISABLED.
     */
    public ObjectNode decidePurchase(AccountPrincipal actor,com.lookahead.domain.membership.MembershipService.Decision decision){
        if(memberships==null)throw new AccountFailure(403,"PURCHASE_APPROVAL_DISABLED","Owner approval of purchases exists only in DEV.");
        String role=actor==null||!actor.isEnabled()?null:TestAccountTemplates.role(staff.permissions(actor));
        return memberships.decide(actor,decision,role,target->policy.cloud()&&TestAccountTemplates.allows(role,targetRoles(target)));
    }
    /**
     * Who created or last changed a test account (DLV-969): the owner gets the account id, which the page names; anyone
     * else gets their own id back (shown as "You"), "seed", or the other person's role label, never their id.
     */
    private String person(AccountPrincipal actor,boolean named,String recorded){
        if(named||recorded==null||"seed".equals(recorded)||recorded.equals(actor.accountId().toString()))return recorded;
        try{return aliases.label(UUID.fromString(recorded));}catch(IllegalArgumentException notAnAccount){return "Unknown";}
    }
    /** {@link #person} once per recorded value for one list: most rows share their creator and last changer. */
    private String person(AccountPrincipal actor,boolean named,String recorded,Map<String,String> memo){
        return recorded==null?null:memo.computeIfAbsent(recorded,value->person(actor,named,value));
    }
    private void reader(AccountPrincipal actor){
        policy.requireEnabled();var permissions=staff.permissions(actor);
        if(!permissions.manageAccess()&&!permissions.triageFeedback()&&!"co-author".equals(permissions.feedbackRole()))
            throw new AccountFailure(403,"STAFF_REQUIRED","Owner, admin or co-author required");
    }
    private TestAccountRoster.Entry entry(UUID id){return TestAccountRoster.entries().stream().filter(e->e.accountId().equals(id)).findFirst()
            .orElseThrow(()->new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found"));}
    @Transactional
    public int seed(){
        policy.requireLocal();actor("seed");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('test-account-seed',0))",rs->{});
        var fresh=new ArrayList<TestAccountRoster.Entry>();
        for(var entry:TestAccountRoster.entries()){
            boolean created=jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",entry.accountId())==1;
            jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",rs->{},entry.accountId());
            int registered=jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES (?,?,?,'seed') ON CONFLICT DO NOTHING",
                    entry.accountId(),entry.username(),created?"scenario":"preserved");
            if(created&&registered==1)fresh.add(entry);
        }
        UUID operator=TestAccountRoster.entries().stream().filter(e->e.username().equals("admin01")).findFirst().orElseThrow().accountId();
        Instant now=Instant.now();for(var entry:fresh)defaults(entry,operator,"scenario",now);
        int created=fresh.size();
        Events.afterCommit(()->Events.success(Events.EVENT,"test-account.seed").addKeyValue("count",created)
            .log("Local test roster seeded: {} new account(s) of {}",created,TestAccountRoster.entries().size()));
        return created;
    }
    @Transactional(readOnly=true)
    public List<ObjectNode> list(AccountPrincipal actor){reader(actor);var permissions=staff.permissions(actor);var actorRole=permissions.feedbackRole();
    // DLV-969 (owner, 2026-11-04): each reader sees the test accounts the one hierarchy lets them act on.
    String hierarchyRole=TestAccountTemplates.role(permissions);
    boolean named=aliases==null||aliases.seesIdentities(actor);
    var markers=markers();var people=new HashMap<String,String>();
    // Cloud mode: learners created through CreatedTestAccounts, and the DEV roster the owner created (it records its
    // creator; Local's seed does not, and never runs in cloud mode).
    String rows=policy.cloud()?"SELECT * FROM test_account_registry WHERE creation_state='active' AND (initialization='created' OR creator_id IS NOT NULL) ORDER BY username"
        :"SELECT * FROM test_account_registry WHERE creation_state='active' ORDER BY username";
    return jdbc.query(rows,(r,n)->{
        UUID id=r.getObject("account_id",UUID.class);
        var roleRows=jdbc.queryForList("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,id);
        var targetRoles=new HashSet<String>();
        var roleNode=roleRows.isEmpty()?json.createArrayNode():json.readTree(roleRows.getFirst());
        roleNode.forEach(role -> targetRoles.add(role.asText()));
        if(!TestAccountTemplates.allows(hierarchyRole,targetRoles))return null;
        var item=json.createObjectNode().put("accountId",r.getString("account_id")).put("username",r.getString("username"))
                .put("isTestAccount",true).put("revision",r.getLong("revision")).put("initialization",r.getString("initialization"))
                .put("lastChangedBy",person(actor,named,r.getString("last_changed_by"),people)).put("lastChangedAt",r.getTimestamp("last_changed_at").toInstant().toString());
        if(r.getObject("creator_id",UUID.class)!=null)item.put("creatorId",person(actor,named,r.getObject("creator_id",UUID.class).toString(),people));
        item.set("roles",roleNode);
        item.put("canDelete",policy.cloud()
                ?TestAccountDeletionPolicy.allowsInDev("owner".equals(actorRole),actor.accountId(),id,r.getObject("creator_id",UUID.class))
                :TestAccountDeletionPolicy.allows(actorRole,actor.accountId(),id,targetRoles));
        item.set("access",json.valueToTree(summaries.get(actor,id)));
        item.put("legacyPresetAvailable",policy.local()&&r.getString("username").matches("learner(0[1-9]|10)"));
        // DLV-969 (owner, 2026-11-04): the request waiting for a decision, for the owner and admins in DEV (null: none).
        if(policy.cloud()&&memberships!=null&&Set.of("owner","admin").contains(hierarchyRole))item.set("pendingPurchase",pendingPurchase(actor,named,id));
        // DLV-969 (owner, 2026-11-04): who upgraded it since its latest Reset, for every reader of the row (null: nobody);
        // and whether this viewer may reset it: the existing rule and, while it is upgraded, owner or requester only.
        var marker=policy.cloud()?markers.find(id):null;
        if(policy.cloud()&&memberships!=null)item.set("upgradedBy",markers.row(marker,actor.accountId(),named));
        boolean rosterRow=r.getObject("creator_id",UUID.class)!=null&&!"created".equals(r.getString("initialization"));
        boolean canReset;
        try{requireResettable(permissions,id,rosterRow,targetRoles);requireNotReserved(actor,permissions,marker,markers);canReset=true;}
        catch(AccountFailure refused){canReset=false;}
        item.put("canReset",canReset);
        return item;
    }).stream().filter(Objects::nonNull).toList();}
    /**
     * DLV-969 (owner, 2026-11-04): the request with who sent it: {@code {"self": true}} for the viewer's own, otherwise
     * the sender's permanent role label, and for the owner also their display name. Null: no request, or sender unknown.
     */
    private ObjectNode pendingPurchase(AccountPrincipal viewer,boolean named,UUID account){
        var pending=memberships.pendingRequest(account);
        if(pending==null)return null;
        var body=pending.body();UUID by=pending.requestedBy();
        if(by==null){body.putNull("requestedBy");return body;}
        var who=body.putObject("requestedBy").put("self",by.equals(viewer.accountId()));
        if(by.equals(viewer.accountId()))return body;
        who.put("label",aliases==null?null:aliases.label(by));
        if(named)who.put("name",jdbc.queryForList("SELECT display_name FROM cloud_accounts WHERE account_id=?",String.class,by).stream().findFirst().orElse(null));
        return body;
    }
    @Transactional
    public void reset(AccountPrincipal actor,UUID id,Reset request){
        try{
            boolean repeated=resetChecked(actor,id,request);
            Events.afterCommit(()->Events.success(Events.EVENT,"test-account.reset").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",id)
                .addKeyValue("preset",request.preset()).addKeyValue("repeated",repeated)
                .log(repeated?"Test account {} reset retried with the same key; it was already reset to its {} preset by account {}"
                    :"Test account {} reset to its {} preset by account {}",id,request.preset(),actor.accountId()));
        }catch(AccountFailure refused){
            Events.refused(Events.EVENT,"test-account.reset",refused.code()).addKeyValue("account.id",actor==null?null:actor.accountId()).addKeyValue("target.id",id)
                .log("Reset of test account {} refused: {}",id,refused.code());
            throw refused;
        }
    }
    /** @return true when this key already reset the account (a retry), false when it was reset now. */
    private boolean resetChecked(AccountPrincipal actor,UUID id,Reset request){
        reader(actor);var permissions=staff.permissions(actor);
        if(policy.cloud()&&(request==null||!"scenario".equals(request.preset())))throw resetUnavailable();
        requireResettable(permissions,id,policy.cloud()&&!jdbc.queryForList("SELECT 1 FROM test_account_registry WHERE account_id=? AND creator_id IS NOT NULL AND initialization<>'created'",Integer.class,id).isEmpty(),null);
        var entry=entry(id);
        // DLV-969 (owner, 2026-11-04): while upgraded, only the owner or the requester; checked under the account's lock.
        jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",rs->{},id);
        var markers=markers();
        requireNotReserved(actor,permissions,policy.cloud()?markers.find(id):null,markers);
        String shown=policy.cloud()?entry.cloudUsername():entry.username(); // the username the list shows
        if(request==null||request.key()==null||!shown.equals(request.confirmation())||(request.preset()==null||!Set.of("scenario","legacy").contains(request.preset())))
            throw new AccountFailure(422,"RESET_CONFIRMATION_REQUIRED","Confirm the exact test username and preset");
        if(request.preset().equals("legacy")&&!entry.username().matches("learner(0[1-9]|10)"))throw new AccountFailure(422,"LEGACY_PRESET_UNAVAILABLE","Legacy fixtures exist only for learner01–learner10");
        var retries=jdbc.queryForList("SELECT actor_id::text || ':' || preset FROM test_account_resets WHERE account_id=? AND mutation_key=?",String.class,id,request.key());
        if(!retries.isEmpty()){
            if(!retries.getFirst().equals(actor.accountId()+":"+request.preset()))throw new AccountFailure(409,"IDEMPOTENCY_CONFLICT","Reset key belongs to another request");
            return true;
        }
        var revisions=jdbc.queryForList("SELECT revision FROM test_account_registry WHERE account_id=? AND creation_state='active' FOR UPDATE",Long.class,id);
        if(revisions.isEmpty())throw new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found");
        if(request.expectedRevision()==null)throw new AccountFailure(428,"REVISION_REQUIRED","An expected revision is required");
        if(!request.expects(revisions.getFirst()))throw new AccountFailure(409,"REVISION_CONFLICT","Test account changed; reload before resetting");
        actor(actor.accountId().toString());
        // Preserve purchase/audit history. Superseded purchases grant nothing.
        jdbc.update("UPDATE membership_purchases SET superseded_at=COALESCE(superseded_at,now()),updated_at=now() WHERE account_id=?",id);
        jdbc.update("UPDATE mutation_receipts SET deleted=true,response=NULL WHERE account_id=?",id);
        jdbc.update("DELETE FROM plans WHERE account_id=?",id);
        jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);
        jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=?",id);
        defaults(entry,actor.accountId(),request.preset(),Instant.now());
        jdbc.update("UPDATE test_account_registry SET initialization=?,revision=revision+1,last_changed_by=?,last_changed_at=clock_timestamp() WHERE account_id=?",request.preset(),actor.accountId().toString(),id);
        jdbc.update("INSERT INTO test_account_resets(account_id,mutation_key,actor_id,preset) VALUES (?,?,?,?)",id,request.key(),actor.accountId(),request.preset());
        return false;
    }
    private static AccountFailure resetUnavailable(){
        return new AccountFailure(422,"RESET_UNAVAILABLE","In DEV only the test roster resets, and only to its scenario.");
    }
    /**
     * The existing Reset rule, before the request is read: in DEV only the owner-created roster ({@code rosterRow}), in
     * Local the fixture policy; owner or admin; a roster account; the one hierarchy (an admin never resets a co-author).
     * {@code knownRoles}: the account's staff roles when the caller has read them, else null.
     */
    private void requireResettable(StaffPermissions permissions,UUID id,boolean rosterRow,Set<String> knownRoles){
        // DLV-969 S35: in DEV only the owner-created roster resets, and only to its scenario; Local keeps both presets.
        if(policy.cloud()){if(!rosterRow)throw resetUnavailable();}
        else policy.requireLocal();
        if(!permissions.manageAccess()&&!permissions.triageFeedback())throw new AccountFailure(403,"RESET_FORBIDDEN","Only owner or admin can reset test accounts");
        entry(id);
        // DLV-969 (owner, 2026-11-04): the one hierarchy; an admin never resets a co-author test account.
        if(!TestAccountTemplates.allows(TestAccountTemplates.role(permissions),knownRoles==null?targetRoles(id):knownRoles))
            throw new AccountFailure(403,"RESET_FORBIDDEN","Your role cannot reset this test account.");
    }
    /**
     * DLV-969 (owner, 2026-11-04): an upgraded test account ({@code marker} not null) resets only by the owner or the
     * person who sent the approved request; anyone else, other admins included, 403 RESET_RESERVED, naming the
     * requester by role label only.
     */
    private static void requireNotReserved(AccountPrincipal actor,StaffPermissions permissions,
            com.lookahead.domain.membership.MembershipService.ApprovedRequest marker,UpgradeMarkers markers){
        if(marker==null||"owner".equals(TestAccountTemplates.role(permissions)))return;
        UUID requester=marker.requestedBy();
        if(requester!=null&&requester.equals(actor.accountId()))return;
        String label=markers.label(requester);
        throw new AccountFailure(403,"RESET_RESERVED",requester==null||"Owner".equals(label)||"Unknown".equals(label)
            ?"Only the owner can reset this upgraded account."
            :"Only the owner or "+label+", who upgraded this account, can reset it.");
    }
    private Set<String> targetRoles(UUID id){
        var roles=new HashSet<String>();
        for(String row:jdbc.queryForList("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,id))json.readTree(row).forEach(role->roles.add(role.asText()));
        return roles;
    }
    /** DLV-969: the existing owner check (403 OWNER_REQUIRED), for the roster's Restore. */
    void requireOwner(AccountPrincipal actor){staff.requireOwner(actor);}
    /**
     * DLV-969 (owner, 2026-11-04): one deleted DEV roster account back to active, rebuilt from its scenario exactly like
     * Reset (membership, purchases and study plan for a learner; the role for staff), inside the caller's transaction.
     * Only a roster id whose owner-created registry row is deleted; a created test account is never restored.
     * @return true when it was restored now, false when there was nothing to restore.
     */
    boolean restoreRosterEntry(AccountPrincipal owner,TestAccountRoster.Entry entry){
        policy.requireCloud();
        UUID id=entry.accountId();
        if(!TestAccountRoster.isRoster(id))return false;
        jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",rs->{},id);
        if(jdbc.queryForList("SELECT 1 FROM test_account_registry WHERE account_id=? AND creation_state='deleted' AND creator_id IS NOT NULL AND initialization<>'created' FOR UPDATE",Integer.class,id).isEmpty())
            return false;
        actor(owner.accountId().toString());
        // As Reset: purchase history stays (superseded), everything else the scenario sets is rebuilt.
        jdbc.update("UPDATE membership_purchases SET superseded_at=COALESCE(superseded_at,now()),updated_at=now() WHERE account_id=?",id);
        jdbc.update("UPDATE mutation_receipts SET deleted=true,response=NULL WHERE account_id=?",id);
        jdbc.update("DELETE FROM plans WHERE account_id=?",id);
        jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);
        jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=?",id);
        // Its admitted cloud row stays on deletion (V17); a roster account deleted before that rule gets one again.
        if(jdbc.queryForList("SELECT 1 FROM cloud_accounts WHERE account_id=?",Integer.class,id).isEmpty())
            admitTestAccount(id,entry.cloudUsername(),entry.firstName(),owner.accountId());
        jdbc.update("UPDATE test_account_registry SET creation_state='active',initialization='scenario',revision=revision+1,last_changed_by=?,last_changed_at=clock_timestamp() WHERE account_id=?",
                owner.accountId().toString(),id);
        jdbc.update("UPDATE test_account_creations SET state='active' WHERE account_id=? AND state='deleted'",id);
        defaults(entry,owner.accountId(),"scenario",Instant.now());
        // The append-only reset ledger records who restored it and when.
        jdbc.update("INSERT INTO test_account_resets(account_id,mutation_key,actor_id,preset) VALUES (?,?,?,'restore')",id,UUID.randomUUID(),owner.accountId());
        return true;
    }
    private void defaults(TestAccountRoster.Entry entry,UUID actor,String preset,Instant now){
        applyTemplate(entry.accountId(),entry.template(),actor,preset,now);
    }
    /**
     * DLV-969 S35: one DEV roster account inside the caller's transaction: the subject, the registry row with its
     * creator, an admitted cloud_accounts row on the test issuer and its 'admit' event, then the same defaults as Local.
     * An account that already has a registry row, deleted or not, is left alone.
     */
    void admitRosterEntry(AccountPrincipal owner,TestAccountRoster.Entry entry){
        policy.requireCloud();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('test-account-seed',0))",rs->{});
        if(!jdbc.queryForList("SELECT 1 FROM test_account_registry WHERE account_id=?",Integer.class,entry.accountId()).isEmpty())return;
        actor(owner.accountId().toString());
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",entry.accountId());
        jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,creator_id,last_changed_by) VALUES (?,?,'scenario',?,?)",
                entry.accountId(),entry.cloudUsername(),owner.accountId(),owner.accountId().toString());
        admitTestAccount(entry.accountId(),entry.cloudUsername(),entry.firstName(),owner.accountId());
        defaults(entry,owner.accountId(),"scenario",Instant.now());
        // DLV-969 (owner, 2026-11-04): its scenario start in the reset ledger, the transaction's last write, so the
        // roster's "changed" count (DevTestRoster.STATUS) has a start for an account that was never reset.
        jdbc.update("INSERT INTO test_account_resets(account_id,mutation_key,actor_id,preset) VALUES (?,?,?,'create')",entry.accountId(),UUID.randomUUID(),owner.accountId());
    }
    /** DLV-969 S35: the admitted cloud_accounts row of a DEV test account, on the test issuer, its identity (S36) and its 'admit' event. */
    void admitTestAccount(UUID id,String username,String displayName,UUID admittedBy){
        jdbc.update("""
                INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,enabled,author_access,username,display_name,requested_at,admitted_at,admitted_by)
                VALUES (?,?,?,true,true,false,?,?,clock_timestamp(),clock_timestamp(),?)
                """,id,TestAccountRoster.TEST_ISSUER,id.toString(),username,displayName,admittedBy);
        // DLV-969 S36: its one identity, on the test issuer; a test account is never a link candidate.
        com.lookahead.domain.cloud.CloudIdentities.insert(jdbc,id,TestAccountRoster.TEST_ISSUER,id.toString(),username,null,"first_contact");
        jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES (?,?,?,?,?,'admit','[]'::jsonb)",
                UUID.randomUUID(),id,TestAccountRoster.TEST_ISSUER,id.toString(),admittedBy);
    }
    /** A created test account starts from a scenario (DLV-969 S35), inside the creating transaction. */
    void applyCreated(UUID id,String template,UUID actor){
        this.actor(actor.toString());
        applyTemplate(id,template,actor,"scenario",Instant.now());
    }
    /** {@code learnerNN} gets its Local scenario (or the legacy preset), {@code co-author} and {@code admin} their role. */
    private void applyTemplate(UUID id,String template,UUID actor,String preset,Instant now){
        if(template.equals("co-author")||template.equals("admin")){
            String role=template;
            jdbc.update("INSERT INTO staff_access(id,account_id,roles,topic_grants,full_curriculum,revision) VALUES (?,?,?::jsonb,'[]',true,1) ON CONFLICT(account_id) DO UPDATE SET roles=excluded.roles,topic_grants='[]',full_curriculum=true,revision=staff_access.revision+1,updated_at=clock_timestamp()",UUID.randomUUID(),id,json.writeValueAsString(List.of(role)));
            return;
        }
        int number=Integer.parseInt(template.substring(7));
        if(preset.equals("legacy")){
            var topics=number==9?Set.of("learn:hands-on-dsa","learn:algorithmic-patterns"):catalog.allTopicIds();
            for(String topic:topics)jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)",id,topic);
            return;
        }
        var scenario=LearnerScenario.of(number);
        if(number==4)for(String topic:List.of("learn:algorithmic-patterns","learn:core-java"))
            jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)",id,topic);
        purchases.populate(id,actor,scenario,now);
        try { plans.populate(id,scenario,now); }
        catch(ScenarioPlans.TemplateMissing missing) {
            // DLV-969 S37: DEV before its study plans are published. The account keeps its membership, purchases and
            // role; Reset adds the plan once the content release has the template. Local still requires it.
            if(!policy.cloud())throw missing;
            Events.failed(Events.EVENT,"test-account.scenario","TEMPLATE_MISSING").addKeyValue("target.id",id)
                .log("Test account {} set up without its study plan: the content release has no {} template yet; Reset adds it later",id,scenario.planKind());
        }
    }
}
