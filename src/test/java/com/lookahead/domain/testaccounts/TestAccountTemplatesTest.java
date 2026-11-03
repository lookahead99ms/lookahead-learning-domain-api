package com.lookahead.domain.testaccounts;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** DLV-969 S35: the scenarios a test account starts from, and who may create or use which. */
class TestAccountTemplatesTest {
    @Test void theListIsLocalsLearnersThenTheTwoStaffRoles() {
        var all = TestAccountTemplates.all();
        assertThat(all).hasSize(29);
        assertThat(all.getFirst().id()).isEqualTo("learner01");
        assertThat(all.get(26).id()).isEqualTo("learner27");
        assertThat(all.subList(27, 29)).extracting(TestAccountTemplates.Template::id).containsExactly("co-author", "admin");
        assertThat(TestAccountTemplates.known("learner14")).isTrue();
        assertThat(TestAccountTemplates.known("learner28")).isFalse();
        assertThat(TestAccountTemplates.known(null)).isFalse();
        assertThat(TestAccountTemplates.roles("admin")).containsExactly("admin");
        assertThat(TestAccountTemplates.roles("learner03")).isEmpty();
        assertThat(TestAccountTemplates.roles("nobody")).isEmpty();
    }

    @Test void labelsSayWhatEachScenarioHas() {
        assertThat(TestAccountTemplates.label(LearnerScenario.of(1))).isEqualTo("Free · no study plan");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(6))).isEqualTo("Learn · ending soon · student discount · custom plan · in progress");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(12))).isEqualTo("Advance · ending soon · Grow next · ready-made plan · in progress");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(19))).isEqualTo("Complete · active · staff upgrade from Learn · ready-made plan · in progress");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(17))).contains("upgraded from Learn");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(26))).isEqualTo("Monthly Promo · expired · plus Interview pass Promo · sale discount · ready-made plan · finished");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(27))).contains("plus Interview sprint", "several plans");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(24))).startsWith("Annual Promo");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(22))).contains("interview plan", "finished");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(4))).endsWith("not started");
        assertThat(TestAccountTemplates.label(LearnerScenario.of(3))).endsWith("overdue");
        for (var template : TestAccountTemplates.all()) assertThat(template.label()).isNotBlank().doesNotContain(" ·  · ");
    }

    @Test void nobodyGainsARoleAboveTheirOwn() {
        Set<String> learner = Set.of(), admin = Set.of("admin"), coauthor = Set.of("co-author");
        assertThat(TestAccountTemplates.allows("owner", coauthor)).isTrue();
        assertThat(TestAccountTemplates.allows("co-author", coauthor)).isTrue();
        assertThat(TestAccountTemplates.allows("co-author", admin)).isTrue();
        assertThat(TestAccountTemplates.allows("co-author", learner)).isTrue();
        assertThat(TestAccountTemplates.allows("admin", admin)).isTrue();
        assertThat(TestAccountTemplates.allows("admin", learner)).isTrue();
        assertThat(TestAccountTemplates.allows("admin", coauthor)).isFalse();
        assertThat(TestAccountTemplates.allows("admin", Set.of("admin", "co-author"))).isFalse();
        for (String nobody : new String[]{"", "user", "reviewer", null}) assertThat(TestAccountTemplates.allows(nobody, learner)).isFalse();
    }
}
