package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.service.*;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.validator.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioPlansTest {
 @TempDir Path root;
 ObjectMapper json=new ObjectMapper();
 @Test void everyScenarioWritesRealVersionProgressAndExplicitLifecycle() throws Exception {
  var publication=mock(ProtectedContentPolicy.class);var catalog=mock(SnapshotValidator.class);
  var plans=mock(PlanRepository.class);var lifecycle=mock(PlanLifecycle.class);
  JsonNode source=json.readTree(getClass().getResourceAsStream("/study-plans/synthetic-adoption-template.json"));
  Set<String> topics=new HashSet<>();source.path("topicIds").forEach(t->topics.add(t.asText()));
  var asset=new ProtectedContentPolicy.Asset("fixture","a".repeat(64),"application/json","pro",topics,Set.of(),"all");
  when(publication.find(anyString())).thenReturn(Optional.of(asset));when(publication.read(asset)).thenReturn(json.writeValueAsBytes(source));
  JsonNode custom=json.readTree(getClass().getResourceAsStream("/accounts/generated-plan.json"));
  var bundle=json.createObjectNode().put("schemaVersion","test-account-plans/v1");bundle.set("customLearn",custom);bundle.set("customGrow",custom);
  Path file=root.resolve("plans.json");Files.writeString(file,bundle.toString());
  Map<String,String> members=new LinkedHashMap<>();for(var day:custom.path("snapshot").path("days"))for(var assignment:day.path("assignments"))members.put(assignment.path("id").asText(),assignment.path("contentId").asText());
  when(catalog.allTopicIds()).thenReturn(topics);
  when(catalog.validate(any(),any(),eq(false),eq(topics))).thenReturn(new SnapshotValidator.ValidationResult(members,Set.copyOf(members.values()),"fixture",custom.path("provenance")));
  var service=new ScenarioPlans(publication,catalog,plans,lifecycle,new PlanJson(json),json,new TestAccountPolicy(TestAccountPolicyTest.local()),file.toString());
  Instant now=Instant.parse("2026-10-15T12:00:00Z");
  for(var row:LearnerScenario.all()) {
   clearInvocations(plans,lifecycle);UUID owner=UUID.randomUUID();service.populate(owner,row,now);
   int count=row.planKind()==LearnerScenario.PlanKind.NONE?0:row.planKind()==LearnerScenario.PlanKind.SEVERAL?2:1;
   var progress=ArgumentCaptor.forClass(JsonNode.class);
   verify(plans,times(count)).createPlan(eq(owner),any(),any(),anyString(),progress.capture(),anyString(),anyLong());
   var snapshots=ArgumentCaptor.forClass(JsonNode.class);var starts=ArgumentCaptor.forClass(JsonNode.class);
   verify(lifecycle,times(count)).create(eq(owner),any(),snapshots.capture(),starts.capture(),eq(false));
   verify(plans,times(count)).insertVersion(eq(owner),any(),any(),isNull(),any(),anyString(),any(),any(),eq("create"),any());
   for(int i=0;i<count;i++) {
    int completed=progress.getAllValues().get(i).path("completedSessionIds").size();
    int assignments=0;for(var day:snapshots.getAllValues().get(i).path("days"))assignments+=day.path("assignments").size();
    if(row.progress()==LearnerScenario.Progress.NOT_STARTED)assertThat(completed).isZero();else assertThat(completed).isPositive();
    if(row.progress()==LearnerScenario.Progress.FINISHED)assertThat(completed).isEqualTo(assignments);
    LocalDate start=LocalDate.parse(starts.getAllValues().get(i).asText());
    if(row.progress()==LearnerScenario.Progress.OVERDUE)assertThat(start.plusDays(snapshots.getAllValues().get(i).path("config").path("days").asInt())).isBefore(LocalDate.of(2026,10,15));
   }
   verify(lifecycle,times(row.progress()==LearnerScenario.Progress.FINISHED?count:0)).transition(eq(owner),any(),any(),eq("completed"),any());
  }
 }
 @Test void missingOrUntrustedFixturesFailBeforePlanWrites() throws Exception {
  var publication=mock(ProtectedContentPolicy.class);var catalog=mock(SnapshotValidator.class);var plans=mock(PlanRepository.class);
  var service=new ScenarioPlans(publication,catalog,plans,mock(PlanLifecycle.class),new PlanJson(json),json,new TestAccountPolicy(TestAccountPolicyTest.local()),root.resolve("missing").toString());
  assertThatThrownBy(()->service.populate(UUID.randomUUID(),LearnerScenario.of(3),Instant.now())).hasMessageContaining("prepared");
  when(publication.find(anyString())).thenReturn(Optional.empty());
  assertThatThrownBy(()->service.populate(UUID.randomUUID(),LearnerScenario.of(2),Instant.now())).hasMessageContaining("missing");
  verifyNoInteractions(plans);
 }
}
