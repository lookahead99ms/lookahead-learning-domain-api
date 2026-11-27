package com.lookahead.domain.service.testaccounts;

import com.lookahead.domain.db.store.content.ProtectedContentFileStore;
import com.lookahead.domain.db.store.plan.StudyPlanVersions;
import com.lookahead.domain.db.store.testaccounts.TestAccountPlanFixtureFileStore;
import com.lookahead.domain.model.TestAccountScenario;
import com.lookahead.domain.db.store.plan.StudyPlan;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.plan.StudyPlanLifecycleService;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.util.StudyPlanJson;
import com.lookahead.domain.util.CatalogIds;
import com.lookahead.domain.validator.StudyPlanReadyMadeTemplateValidator;
import com.lookahead.domain.validator.StudyPlanSnapshotValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import tools.jackson.databind.node.ObjectNode;

/** Historical local fixtures use the real plan/version/progress/lifecycle model. */
@Component
public class TestAccountScenarioPlanService {
    /** The content release has no such ready-made plan template (DLV-969 S37: DEV before its study plans are published). */
    public static final class TemplateMissing extends IllegalStateException {
        TemplateMissing(String templateId) { super("Required scenario template is missing: " + templateId); }
    }
    private final ProtectedContentManifestService publication;
    private final ProtectedContentFileStore contentFiles;
    private final StudyPlanSnapshotValidator catalog;
    private final AccountCatalogService accountCatalog;
    private final StudyPlan plans;
    private final StudyPlanVersions planVersions;
    private final StudyPlanLifecycleService lifecycle;
    private final StudyPlanJson hashes;
    private final ObjectMapper json;
    private final TestAccountModeService policy;
    private final TestAccountPlanFixtureFileStore planFixtures;
    private final String customPath;
    public TestAccountScenarioPlanService(ProtectedContentManifestService publication,ProtectedContentFileStore contentFiles,StudyPlanSnapshotValidator catalog,AccountCatalogService accountCatalog,StudyPlan plans,StudyPlanVersions planVersions,
            StudyPlanLifecycleService lifecycle,StudyPlanJson hashes,ObjectMapper json,TestAccountModeService policy,
            TestAccountPlanFixtureFileStore planFixtures,@Value("${app.test-accounts.plan-fixtures:}") String customPath){
        this.publication=publication;this.contentFiles=contentFiles;this.catalog=catalog;this.accountCatalog=accountCatalog;this.plans=plans;this.planVersions=planVersions;this.lifecycle=lifecycle;
        this.hashes=hashes;this.json=json;this.policy=policy;this.planFixtures=planFixtures;this.customPath=customPath;
    }
    public void populate(UUID owner,TestAccountScenario scenario,Instant now){
        policy.requireEnabled(); // Local seed, or DEV test accounts (DLV-969 S35)
        if(scenario.planKind()==TestAccountScenario.PlanKind.NONE)return;
        // DEV has no custom-plan fixture file (it is built for one content release and kept out of images), so the five
        // custom scenarios get the ready-made plan with the same progress there.
        if(scenario.planKind()==TestAccountScenario.PlanKind.CUSTOM&&!policy.cloud()){
            JsonNode input=custom(scenario.number()==9||scenario.number()==18?"customGrow":"customLearn");
            var validated=catalog.validate(input.path("snapshot"),input.path("provenance"),false,accountCatalog.allTopicIds());
            save(owner,scenario,input.path("snapshot"),validated.provenance(),validated.assignmentContentIds(),now,0);
        }else{
            ready(owner,scenario,scenario.number()==8?"ai-engineer-d7-h1":"coding-interviews-d7-h1",now,0);
            if(scenario.planKind()==TestAccountScenario.PlanKind.SEVERAL)ready(owner,scenario,"ai-engineer-d7-h1",now,1);
        }
    }
    private JsonNode custom(String key){
        try{
            if(customPath.isBlank())throw new IllegalArgumentException();
            var text=planFixtures.text(customPath,16*1024*1024);
            if(text.isEmpty())throw new IllegalArgumentException();
            JsonNode bundle=json.readTree(text.get());
            if(!"test-account-plans/v1".equals(bundle.path("schemaVersion").asText())||!bundle.path(key).isObject())throw new IllegalArgumentException();
            return CatalogIds.normalize(bundle.path(key));
        }catch(Exception failure){throw new IllegalStateException("Validated custom test-plan fixtures must be prepared before seeding");}
    }
    private void ready(UUID owner,TestAccountScenario scenario,String templateId,Instant now,int index){
        var asset=publication.find("/content/study-plans/templates/"+templateId+".json")
                .orElseThrow(()->new TemplateMissing(templateId));
        publication.requirePublished(asset);
        JsonNode source=json.readTree(contentFiles.read(asset));
        catalog.validateTemplateReferences(CatalogIds.normalize(source));
        var mapping=new StudyPlanReadyMadeTemplateValidator(json).map(source,asset.sha256());
        if(!mapping.topicIds().equals(asset.scopes()))throw new IllegalStateException("Scenario template scope disagrees");
        save(owner,scenario,mapping.snapshot(),mapping.provenance(),mapping.assignmentContentIds(),now,index);
    }
    private void save(UUID owner,TestAccountScenario scenario,JsonNode snapshot,JsonNode pins,Map<String,String> membership,Instant now,int index){
        UUID id=UUID.randomUUID(),version=UUID.randomUUID();long number=plans.allocatePlanNumber(owner);
        var progress=json.createObjectNode();
        for(String key:List.of("completedContentIds","completedSessionIds","attemptedContentIds","needsReviewContentIds"))progress.putArray(key);
        progress.putObject("notes");progress.putObject("sessionOutcomes");
        var sessions=new ArrayList<JsonNode>();for(var day:snapshot.path("days"))day.path("assignments").forEach(sessions::add);
        int complete=switch(scenario.progress()){
            case FINISHED->sessions.size();case IN_PROGRESS,OVERDUE->Math.max(1,sessions.size()/4);default->0;
        };
        var content=new LinkedHashSet<String>();
        for(int n=0;n<complete;n++){
            String assignment=sessions.get(n).path("id").asText();
            progress.withArray("completedSessionIds").add(assignment);content.add(membership.get(assignment));
            ((ObjectNode)progress.path("sessionOutcomes")).put(assignment,"completed");
        }
        content.forEach(value->progress.withArray("completedContentIds").add(value));
        var recovery=json.createObjectNode().put("strategy","none").put("elapsedDays",0).put("deadlineDays",snapshot.path("config").path("days").asInt());
        recovery.putArray("deferredContentIds");
        String goal="Synthetic "+scenario.username()+" "+(index+1);
        plans.createPlan(owner,id,version,goal,progress,goal,number);
        planVersions.insertVersion(owner,id,version,null,snapshot,hashes.digest(hashes.canonical(snapshot)),pins,recovery,"create",json.valueToTree(membership));
        int duration=snapshot.path("config").path("days").asInt();
        LocalDate today=now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start=switch(scenario.progress()){
            case OVERDUE,FINISHED->today.minusDays(duration+3);case IN_PROGRESS->today.minusDays(2);default->today;
        };
        lifecycle.create(owner,id,snapshot,json.getNodeFactory().textNode(start.toString()),false);
        if(scenario.progress()==TestAccountScenario.Progress.FINISHED)
            lifecycle.transition(owner,id,snapshot,"completed",json.missingNode());
        planVersions.insertActivity(owner,id,version,1,"test-account-seed",json.createObjectNode().put("scenario",scenario.number()).put("historicalFixture",true));
    }
}
