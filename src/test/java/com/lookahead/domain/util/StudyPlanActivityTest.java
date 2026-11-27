package com.lookahead.domain.util;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StudyPlanActivityTest {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode snapshot() { return (ObjectNode) mapper.readTree("""
      {"config":{"days":3},"days":[{"day":1,"assignments":[{"id":"original","kind":"new","minutes":30,"route":["/learn/course/lesson"]}]}]}
      """); }
    @Test void recordsAnEstimateOncePerActivityAndDay() {
        ObjectNode progress = mapper.createObjectNode();
        StudyPlanActivity.record(progress,snapshot(),"original",2,mapper);
        StudyPlanActivity.record(progress,snapshot(),"original",2,mapper);
        assertThat(progress.path("studyLog").size()).isEqualTo(1);
        assertThat(progress.path("studyLog").get(0).path("minutes").asInt()).isEqualTo(30);
        ObjectNode restored = (ObjectNode) mapper.readTree(progress.toString());
        StudyPlanActivity.record(restored,snapshot(),"original",2,mapper);
        assertThat(restored).isEqualTo(progress);
        StudyPlanActivity.record(restored,snapshot(),"original",3,mapper);
        assertThat(restored.path("studyLog").size()).isEqualTo(2);
    }
    @Test void derivesDailyRecallOnlyFromOwnedOriginalAndChecksItsCompletionDay() {
        var plan=snapshot(); ObjectNode progress=mapper.createObjectNode(); progress.putArray("completedContentIds").add("original");
        StudyPlanActivity.record(progress,plan,"original",2,mapper);
        var recall=StudyPlanActivity.resolve(plan,"original:daily-recall:3",3);
        assertThat(recall.path("route")).isEqualTo(plan.path("days").get(0).path("assignments").get(0).path("route"));
        assertThat(StudyPlanActivity.dailyEligible(plan,progress,recall,"original",3)).isTrue();
        assertThat(StudyPlanActivity.dailyEligible(plan,progress,recall,"original",2)).isFalse();
        assertThat(StudyPlanActivity.resolve(plan,"unknown:daily-recall:3",3)).isNull();
        assertThat(StudyPlanActivity.resolve(plan,"original:daily-recall:4",4)).isNull();
        assertThat(StudyPlanActivity.resolve(plan,"original:daily-recall:3",2)).isNull();
        StudyPlanActivity.record(progress,plan,recall.path("id").asText(),3,mapper);
        assertThat(progress.path("studyLog").get(1).path("minutes").asInt()).isEqualTo(10);
    }
    @Test void oldClientsDoNotManufactureDateEvidence() {
        ObjectNode progress=mapper.createObjectNode(); StudyPlanActivity.record(progress,snapshot(),"original",0,mapper);
        assertThat(progress.has("studyLog")).isFalse();
    }
}
