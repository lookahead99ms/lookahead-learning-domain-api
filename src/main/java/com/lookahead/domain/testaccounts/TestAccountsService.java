package com.lookahead.domain.testaccounts;

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
    public record Reset(UUID key,long expectedRevision,String confirmation,String preset) {}
    private void actor(String actor){jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor);}
    private void reader(AccountPrincipal actor){
        policy.requireEnabled();var permissions=staff.permissions(actor);
        if(!permissions.manageAccess()&&!permissions.triageFeedback()&&!"co-author".equals(permissions.feedbackRole()))
            throw new AccountFailure(403,"STAFF_REQUIRED","Owner, admin or co-author required");
    }
    private TestAccountRoster.Entry entry(UUID id){return TestAccountRoster.entries().stream().filter(e->e.accountId().equals(id)).findFirst()
            .orElseThrow(()->new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found"));}
    @Transactional
    public int seed(){
        policy.requireEnabled();actor("seed");
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
        return fresh.size();
    }
    @Transactional(readOnly=true)
    public List<ObjectNode> list(AccountPrincipal actor){reader(actor);var actorRole=staff.permissions(actor).feedbackRole();return jdbc.query("SELECT * FROM test_account_registry WHERE creation_state='active' ORDER BY username",(r,n)->{
        var item=json.createObjectNode().put("accountId",r.getString("account_id")).put("username",r.getString("username"))
                .put("isTestAccount",true).put("revision",r.getLong("revision")).put("initialization",r.getString("initialization"))
                .put("lastChangedBy",r.getString("last_changed_by")).put("lastChangedAt",r.getTimestamp("last_changed_at").toInstant().toString());
        if(r.getObject("creator_id",UUID.class)!=null)item.put("creatorId",r.getObject("creator_id",UUID.class).toString());
        UUID id=r.getObject("account_id",UUID.class);
        var roleRows=jdbc.queryForList("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,id);
        var targetRoles=new HashSet<String>();
        var roleNode=roleRows.isEmpty()?json.createArrayNode():json.readTree(roleRows.getFirst());
        roleNode.forEach(role -> targetRoles.add(role.asText()));
        item.set("roles",roleNode);
        item.put("canDelete",TestAccountDeletionPolicy.allows(actorRole,actor.accountId(),id,targetRoles));
        item.set("access",json.valueToTree(summaries.get(actor,id)));
        item.put("legacyPresetAvailable",r.getString("username").matches("learner(0[1-9]|10)"));
        return item;
    });}
    @Transactional
    public void reset(AccountPrincipal actor,UUID id,Reset request){
        reader(actor);var permissions=staff.permissions(actor);
        if(!permissions.manageAccess()&&!permissions.triageFeedback())throw new AccountFailure(403,"RESET_FORBIDDEN","Only owner or admin can reset test accounts");
        var entry=entry(id);
        if(request==null||request.key()==null||!entry.username().equals(request.confirmation())||(request.preset()==null||!Set.of("scenario","legacy").contains(request.preset())))
            throw new AccountFailure(422,"RESET_CONFIRMATION_REQUIRED","Confirm the exact test username and preset");
        if(request.preset().equals("legacy")&&!entry.username().matches("learner(0[1-9]|10)"))throw new AccountFailure(422,"LEGACY_PRESET_UNAVAILABLE","Legacy fixtures exist only for learner01–learner10");
        jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",rs->{},id);
        var retries=jdbc.queryForList("SELECT actor_id::text || ':' || preset FROM test_account_resets WHERE account_id=? AND mutation_key=?",String.class,id,request.key());
        if(!retries.isEmpty()){
            if(!retries.getFirst().equals(actor.accountId()+":"+request.preset()))throw new AccountFailure(409,"IDEMPOTENCY_CONFLICT","Reset key belongs to another request");
            return;
        }
        var revisions=jdbc.queryForList("SELECT revision FROM test_account_registry WHERE account_id=? AND creation_state='active' FOR UPDATE",Long.class,id);
        if(revisions.isEmpty())throw new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found");
        if(revisions.getFirst()!=request.expectedRevision())throw new AccountFailure(409,"REVISION_CONFLICT","Test account changed; reload before resetting");
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
    }
    private void defaults(TestAccountRoster.Entry entry,UUID actor,String preset,Instant now){
        UUID id=entry.accountId();
        if(entry.staff()){
            String role=entry.username().startsWith("coauthor")?"co-author":"admin";
            jdbc.update("INSERT INTO staff_access(id,account_id,roles,topic_grants,full_curriculum,revision) VALUES (?,?,?::jsonb,'[]',true,1) ON CONFLICT(account_id) DO UPDATE SET roles=excluded.roles,topic_grants='[]',full_curriculum=true,revision=staff_access.revision+1,updated_at=clock_timestamp()",UUID.randomUUID(),id,json.writeValueAsString(List.of(role)));
            return;
        }
        int number=Integer.parseInt(entry.username().substring(7));
        if(preset.equals("legacy")){
            var topics=number==9?Set.of("learn:hands-on-dsa","learn:algorithmic-patterns"):catalog.allTopicIds();
            for(String topic:topics)jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)",id,topic);
            return;
        }
        var scenario=LearnerScenario.of(number);
        if(number==4)for(String topic:List.of("learn:algorithmic-patterns","learn:core-java"))
            jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)",id,topic);
        purchases.populate(id,actor,scenario,now);plans.populate(id,scenario,now);
    }
}
