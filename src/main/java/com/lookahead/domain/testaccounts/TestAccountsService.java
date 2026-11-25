package com.lookahead.domain.testaccounts;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.*;
import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.Clock;
import java.util.*;

@Service
public class TestAccountsService {
    private final TestAccountStore store;private final ObjectMapper json;private final TestAccountPolicy policy;
    private final ScenarioPurchases purchases;private final ScenarioPlans plans;private final StaffAccessService staff;
    private final AccessSummaryService summaries;private final SnapshotValidator catalog;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public TestAccountsService(TestAccountStore store,ObjectMapper json,TestAccountPolicy policy,ScenarioPurchases purchases,
            ScenarioPlans plans,StaffAccessService staff,AccessSummaryService summaries,SnapshotValidator catalog){
        this(store,json,policy,purchases,plans,staff,summaries,catalog,Clock.systemUTC());
    }
    TestAccountsService(TestAccountStore store,ObjectMapper json,TestAccountPolicy policy,ScenarioPurchases purchases,
            ScenarioPlans plans,StaffAccessService staff,AccessSummaryService summaries,SnapshotValidator catalog,Clock clock){
        this.store=store;this.json=json;this.policy=policy;this.purchases=purchases;this.plans=plans;this.staff=staff;this.summaries=summaries;this.catalog=catalog;
        this.clock=clock;
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
    private StaffAliases aliases;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureAliases(StaffAliases aliases){this.aliases=aliases;}
    private com.lookahead.domain.membership.MembershipService memberships;
    /** DLV-969 (owner, 2026-11-04): DEV purchase requests, listed for the owner and admins and decided by them. */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureMemberships(com.lookahead.domain.membership.MembershipService memberships){this.memberships=memberships;}
    /** DLV-969 (owner, 2026-11-04): the "upgraded by" marker, from the wired memberships and aliases (none: no marker). */
    private UpgradeMarkers markers(){return new UpgradeMarkers(store,memberships,aliases);}
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
    /** The Local roster seed ({@link TestAccountRoster#entries}); see {@link #seed(List)}. */
    public int seed(){return seed(TestAccountRoster.entries());}
    /**
     * Seeds a roster one account per transaction (DLV-970): the subject and the registry row commit together, fenced on
     * the subject's revision, and the scenario (grants, purchases, plan) follows in a second transaction of its own, so a
     * roster of any size stays well inside the store's statement bound and a failure part-way leaves every earlier
     * account complete and the failed one consistent: its subject and registry row exist, its scenario is what the
     * failure left (the store's note on immediate commits), and a repeat seed leaves it alone. A subject that exists
     * without a registry row (a crash between the two writes, or an account made elsewhere) is registered as
     * {@code preserved} and gets no scenario.
     * @return how many accounts were created now
     */
    int seed(List<TestAccountRoster.Entry> roster){
        policy.requireLocal();
        // DLV-971 (owner, 2026-11-06): the roster has no staff accounts, so the Local author/owner account is the operator
        // of the scenario purchases (learner19's staff upgrade and the purchase actor rows). It gets its subject row here
        // and is never registered as a test account.
        UUID operator=com.lookahead.learning.content.security.LocalAuthorAccess.ACCOUNT_ID;
        store.transact(work->{work.actor("seed");work.seedLock();work.createSubject(operator);return null;});
        Instant now=clock.instant();int created=0;
        for(var entry:roster){
            boolean fresh=store.transact(work->{
                work.actor("seed");
                work.seedLock();
                boolean subject=work.createSubject(entry.accountId());
                work.lockSubject(entry.accountId());
                return work.registerSeed(entry.accountId(),entry.username(),subject?"scenario":"preserved")&&subject;
            });
            if(!fresh)continue;
            created++;
            store.transact(work->{work.actor("seed");work.lockSubject(entry.accountId());defaults(work,entry,operator,"scenario",now);return null;});
        }
        int count=created;
        Events.afterCommit(()->Events.success(Events.EVENT,"test-account.seed").addKeyValue("count",count)
            .log("Local test roster seeded: {} new account(s) of {}",count,roster.size()));
        return created;
    }
    public List<ObjectNode> list(AccountPrincipal actor){reader(actor);var permissions=staff.permissions(actor);var actorRole=permissions.feedbackRole();
    // DLV-969 (owner, 2026-11-04): each reader sees the test accounts the one hierarchy lets them act on.
    String hierarchyRole=TestAccountTemplates.role(permissions);
    boolean named=aliases==null||aliases.seesIdentities(actor);
    // The rows are read side by side (ParallelRows), so the memo of names is shared between threads.
    var markers=markers();var people=new java.util.concurrent.ConcurrentHashMap<String,String>();
    // Cloud mode: learners created through CreatedTestAccounts, and the DEV roster the owner created (it records its
    // creator; Local's seed does not, and never runs in cloud mode).
    return com.lookahead.domain.ParallelRows.map(store.activeRegistry(policy.cloud()),r->{
        UUID id=r.accountId();
        var roles=store.staffRoles(id);
        var targetRoles=new HashSet<String>(roles);
        var roleNode=json.createArrayNode();roles.forEach(roleNode::add);
        if(!TestAccountTemplates.allows(hierarchyRole,targetRoles))return null;
        var item=json.createObjectNode().put("accountId",r.accountId().toString()).put("username",r.username())
                .put("isTestAccount",true).put("revision",r.revision()).put("initialization",r.initialization())
                .put("lastChangedBy",person(actor,named,r.lastChangedBy(),people)).put("lastChangedAt",r.lastChangedAt().toString());
        if(r.creatorId()!=null)item.put("creatorId",person(actor,named,r.creatorId().toString(),people));
        item.set("roles",roleNode);
        item.put("canDelete",policy.cloud()
                ?TestAccountDeletionPolicy.allowsInDev("owner".equals(actorRole),actor.accountId(),id,r.creatorId())
                :TestAccountDeletionPolicy.allows(actorRole,actor.accountId(),id,targetRoles));
        item.set("access",json.valueToTree(summaries.get(actor,id)));
        item.put("legacyPresetAvailable",policy.local()&&r.username().matches("learner(0[1-9]|10)"));
        // DLV-969 (owner, 2026-11-04): the request waiting for a decision, for the owner and admins in DEV (null: none).
        if(policy.cloud()&&memberships!=null&&Set.of("owner","admin").contains(hierarchyRole))item.set("pendingPurchase",pendingPurchase(actor,named,id));
        // DLV-969 (owner, 2026-11-04): who upgraded it since its latest Reset, for every reader of the row (null: nobody);
        // and whether this viewer may reset it: the existing rule and, while it is upgraded, owner or requester only.
        var marker=policy.cloud()?markers.find(id):null;
        if(policy.cloud()&&memberships!=null)item.set("upgradedBy",markers.row(marker,actor.accountId(),named));
        boolean rosterRow=r.creatorId()!=null&&!"created".equals(r.initialization());
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
        if(named)who.put("name",store.displayName(by));
        return body;
    }
    public void reset(AccountPrincipal actor,UUID id,Reset request){
        try{
            boolean repeated=store.transact(work->resetChecked(work,actor,id,request));
            Events.afterCommit(()->Events.success(Events.EVENT,"test-account.reset").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",id)
                .addKeyValue("preset",request.preset()).addKeyValue("repeated",repeated)
                .log(repeated?"Test account {} reset retried with the same key; it was already reset to its {} preset by account {}"
                    :"Test account {} reset to its {} preset by account {}",id,request.preset(),actor.accountId()));
        }catch(AccountFailure refused){
            Events.refused(Events.EVENT,"test-account.reset",refused.code()).addKeyValue("account.id",actor==null?null:actor.accountId()).addKeyValue("target.id",id)
                .log("Reset of test account {} by {} refused: {}",id,Events.actor("Account",actor==null?null:actor.accountId()),refused.code());
            throw refused;
        }
    }
    /** @return true when this key already reset the account (a retry), false when it was reset now. */
    private boolean resetChecked(TestAccountStore.Work work,AccountPrincipal actor,UUID id,Reset request){
        reader(actor);var permissions=staff.permissions(actor);
        if(policy.cloud()&&(request==null||!"scenario".equals(request.preset())))throw resetUnavailable();
        requireResettable(permissions,id,policy.cloud()&&work.rosterRow(id),null);
        var entry=entry(id);
        // DLV-969 (owner, 2026-11-04): while upgraded, only the owner or the requester; checked under the account's lock.
        work.lockSubject(id);
        var markers=markers();
        requireNotReserved(actor,permissions,policy.cloud()?markers.find(id):null,markers);
        String shown=policy.cloud()?entry.cloudUsername():entry.username(); // the username the list shows
        if(request==null||request.key()==null||!shown.equals(request.confirmation())||(request.preset()==null||!Set.of("scenario","legacy").contains(request.preset())))
            throw new AccountFailure(422,"RESET_CONFIRMATION_REQUIRED","Confirm the exact test username and preset");
        if(request.preset().equals("legacy")&&!entry.username().matches("learner(0[1-9]|10)"))throw new AccountFailure(422,"LEGACY_PRESET_UNAVAILABLE","Legacy fixtures exist only for learner01–learner10");
        var retry=work.resetReceipt(id,request.key());
        if(retry!=null){
            if(!retry.equals(actor.accountId()+":"+request.preset()))throw new AccountFailure(409,"IDEMPOTENCY_CONFLICT","Reset key belongs to another request");
            return true;
        }
        var revision=work.activeRevision(id);
        if(revision==null)throw new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found");
        if(request.expectedRevision()==null)throw new AccountFailure(428,"REVISION_REQUIRED","An expected revision is required");
        if(!request.expects(revision))throw new AccountFailure(409,"REVISION_CONFLICT","Test account changed; reload before resetting");
        work.actor(actor.accountId().toString());
        // Preserve purchase/audit history. Superseded purchases grant nothing.
        work.supersedePurchases(id);
        work.tombstoneReceipts(id);
        work.deletePlans(id);
        work.deleteGrants(id);
        work.clearStaffAccess(id);
        defaults(work,entry,actor.accountId(),request.preset(),clock.instant());
        work.resetRegistry(id,request.preset(),actor.accountId());
        work.insertReset(id,request.key(),actor.accountId(),request.preset());
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
    private Set<String> targetRoles(UUID id){return new HashSet<>(store.staffRoles(id));}
    /** DLV-969: the existing owner check (403 OWNER_REQUIRED), for the roster's Restore. */
    void requireOwner(AccountPrincipal actor){staff.requireOwner(actor);}
    /**
     * DLV-969 (owner, 2026-11-04): one deleted DEV roster account back to active, rebuilt from its scenario exactly like
     * Reset (membership, purchases and study plan), inside the caller's transaction.
     * Only a roster id whose owner-created registry row is deleted; a created test account is never restored.
     * @return true when it was restored now, false when there was nothing to restore.
     */
    boolean restoreRosterEntry(AccountPrincipal owner,TestAccountRoster.Entry entry){
        policy.requireCloud();
        UUID id=entry.accountId();
        if(!TestAccountRoster.isRoster(id))return false;
        return store.transact(work->{
        work.lockSubject(id);
        if(!work.deletedRosterRow(id))
            return false;
        work.actor(owner.accountId().toString());
        // As Reset: purchase history stays (superseded), everything else the scenario sets is rebuilt.
        work.supersedePurchases(id);
        work.tombstoneReceipts(id);
        work.deletePlans(id);
        work.deleteGrants(id);
        work.clearStaffAccess(id);
        // Its admitted cloud row stays on deletion (V17); a roster account deleted before that rule gets one again.
        if(!work.hasAccountRecord(id))
            work.admitTestAccount(id,entry.cloudUsername(),entry.firstName(),owner.accountId());
        work.restoreRegistry(id,owner.accountId());
        work.restoreCreations(id);
        defaults(work,entry,owner.accountId(),"scenario",clock.instant());
        // The append-only reset ledger records who restored it and when.
        work.insertReset(id,UUID.randomUUID(),owner.accountId(),"restore");
        return true;
        });
    }
    private void defaults(TestAccountStore.Work work,TestAccountRoster.Entry entry,UUID actor,String preset,Instant now){
        applyTemplate(work,entry.accountId(),entry.template(),actor,preset,now);
    }
    /**
     * DLV-969 S35: one DEV roster account inside the caller's transaction: the subject, the registry row with its
     * creator, an admitted cloud_accounts row on the test issuer and its 'admit' event, then the same defaults as Local.
     * An account that already has a registry row, deleted or not, is left alone.
     */
    void admitRosterEntry(AccountPrincipal owner,TestAccountRoster.Entry entry){
        policy.requireCloud();
        store.transact(work->{
        work.seedLock();
        if(work.registry(entry.accountId())!=null)return null;
        work.actor(owner.accountId().toString());
        work.createSubject(entry.accountId());
        work.registerRoster(entry.accountId(),entry.cloudUsername(),owner.accountId());
        work.admitTestAccount(entry.accountId(),entry.cloudUsername(),entry.firstName(),owner.accountId());
        defaults(work,entry,owner.accountId(),"scenario",clock.instant());
        // DLV-969 (owner, 2026-11-04): its scenario start in the reset ledger, the transaction's last write, so the
        // roster's "changed" count (DevTestRoster.STATUS) has a start for an account that was never reset.
        work.insertReset(entry.accountId(),UUID.randomUUID(),owner.accountId(),"create");
        return null;
        });
    }
    /** DLV-969 S35: the admitted cloud_accounts row of a DEV test account, on the test issuer, its identity (S36) and its 'admit' event. */
    void admitTestAccount(UUID id,String username,String displayName,UUID admittedBy){
        store.transact(work->{work.admitTestAccount(id,username,displayName,admittedBy);return null;});
    }
    /** A created test account starts from a scenario (DLV-969 S35), inside the creating transaction. */
    void applyCreated(UUID id,String template,UUID actor){
        store.transact(work->{
        work.actor(actor.toString());
        applyTemplate(work,id,template,actor,"scenario",clock.instant());
        return null;
        });
    }
    /**
     * {@code learnerNN} gets its Local scenario (or the legacy preset). DLV-971 (owner, 2026-11-06): there is no staff
     * scenario any more; a test account never gets a staff role here.
     */
    private void applyTemplate(TestAccountStore.Work work,UUID id,String template,UUID actor,String preset,Instant now){
        int number=Integer.parseInt(template.substring(7));
        if(preset.equals("legacy")){
            var topics=number==9?Set.of("learn:hands-on-dsa","learn:algorithmic-patterns"):catalog.allTopicIds();
            work.grantTopics(id,topics);
            return;
        }
        var scenario=LearnerScenario.of(number);
        if(number==4)work.grantTopics(id,List.of("learn:algorithmic-patterns","learn:core-java"));
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
