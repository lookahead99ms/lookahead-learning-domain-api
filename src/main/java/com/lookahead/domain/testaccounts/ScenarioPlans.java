package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.service.PlanLifecycle;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.util.CatalogIds;
import com.lookahead.learning.content.validator.ReadyMadeTemplateMapper;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Historical local fixtures use the real plan/version/progress/lifecycle model. */
@Component
public class ScenarioPlans {
    private final ProtectedContentPolicy publication;
    private final SnapshotValidator catalog;
    private final PlanRepository plans;
    private final PlanLifecycle lifecycle;
    private final PlanJson hashes;
    private final ObjectMapper json;
    private final TestAccountPolicy policy;
    private final String customPath;
    public ScenarioPlans(ProtectedContentPolicy publication,SnapshotValidator catalog,PlanRepository plans,
            PlanLifecycle lifecycle,PlanJson hashes,ObjectMapper json,TestAccountPolicy policy,
            @Value("${app.test-accounts.plan-fixtures:}") String customPath){
        this.publication=publication;this.catalog=catalog;this.plans=plans;this.lifecycle=lifecycle;
        this.hashes=hashes;this.json=json;this.policy=policy;this.customPath=customPath;
    }
    public void populate(UUID owner,LearnerScenario scenario,Instant now){
        policy.requireEnabled();
        if(scenario.planKind()==LearnerScenario.PlanKind.NONE)return;
        if(scenario.planKind()==LearnerScenario.PlanKind.CUSTOM){
            JsonNode input=custom(scenario.number()==9||scenario.number()==18?"customGrow":"customLearn");
            var validated=catalog.validate(input.path("snapshot"),input.path("provenance"),false,catalog.allTopicIds());
            save(owner,scenario,input.path("snapshot"),validated.provenance(),validated.assignmentContentIds(),now,0);
        }else{
            ready(owner,scenario,scenario.number()==8?"ai-engineer-d7-h1":"coding-interviews-d7-h1",now,0);
            if(scenario.planKind()==LearnerScenario.PlanKind.SEVERAL)ready(owner,scenario,"ai-engineer-d7-h1",now,1);
        }
    }
    private JsonNode custom(String key){
        try{
            Path path=Path.of(customPath);
            if(customPath.isBlank()||Files.isSymbolicLink(path)||Files.size(path)>16*1024*1024)throw new IllegalArgumentException();
            JsonNode bundle=json.readTree(Files.readString(path));
            if(!"test-account-plans/v1".equals(bundle.path("schemaVersion").asText())||!bundle.path(key).isObject())throw new IllegalArgumentException();
            return CatalogIds.normalize(bundle.path(key));
        }catch(Exception failure){throw new IllegalStateException("Validated custom test-plan fixtures must be prepared before seeding");}
    }
    private void ready(UUID owner,LearnerScenario scenario,String templateId,Instant now,int index){
        var asset=publication.find("/content/study-plans/templates/"+templateId+".json")
                .orElseThrow(()->new IllegalStateException("Required scenario template is missing"));
        JsonNode source=json.readTree(publication.read(asset));
        catalog.validateTemplateReferences(CatalogIds.normalize(source));
        var mapping=new ReadyMadeTemplateMapper(json).map(source,asset.sha256());
        if(!mapping.topicIds().equals(asset.scopes()))throw new IllegalStateException("Scenario template scope disagrees");
        save(owner,scenario,mapping.snapshot(),mapping.provenance(),mapping.assignmentContentIds(),now,index);
    }
    private void save(UUID owner,LearnerScenario scenario,JsonNode snapshot,JsonNode pins,Map<String,String> membership,Instant now,int index){
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
            ((tools.jackson.databind.node.ObjectNode)progress.path("sessionOutcomes")).put(assignment,"completed");
        }
        content.forEach(value->progress.withArray("completedContentIds").add(value));
        var recovery=json.createObjectNode().put("strategy","none").put("elapsedDays",0).put("deadlineDays",snapshot.path("config").path("days").asInt());
        recovery.putArray("deferredContentIds");
        String goal="Synthetic "+scenario.username()+" "+(index+1);
        plans.createPlan(owner,id,version,goal,progress,goal,number);
        plans.insertVersion(owner,id,version,null,snapshot,hashes.digest(hashes.canonical(snapshot)),pins,recovery,"create",json.valueToTree(membership));
        int duration=snapshot.path("config").path("days").asInt();
        LocalDate today=now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start=switch(scenario.progress()){
            case OVERDUE,FINISHED->today.minusDays(duration+3);case IN_PROGRESS->today.minusDays(2);default->today;
        };
        lifecycle.create(owner,id,snapshot,json.getNodeFactory().textNode(start.toString()),false);
        if(scenario.progress()==LearnerScenario.Progress.FINISHED)
            lifecycle.transition(owner,id,snapshot,"completed",json.missingNode());
        plans.insertActivity(owner,id,version,1,"test-account-seed",json.createObjectNode().put("scenario",scenario.number()).put("historicalFixture",true));
    }
}
