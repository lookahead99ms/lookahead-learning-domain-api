package com.lookahead.domain.util;

import com.lookahead.domain.model.TestAccountScenario;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** DLV-969 S35: the scenarios a test account starts from, and who may create or use which. */
class TestAccountTemplatesTest {
    /** DLV-971 (owner, 2026-11-06): the list is Local's 27 learners and nothing else; no scenario carries a role. */
    @Test void theListIsLocalsLearnersOnly() {
        var all = TestAccountTemplates.all();
        assertThat(all).hasSize(27).allSatisfy(t -> assertThat(t.roles()).isEmpty());
        assertThat(all.getFirst().id()).isEqualTo("learner01");
        assertThat(all.getLast().id()).isEqualTo("learner27");
        assertThat(all).extracting(TestAccountTemplates.Template::id).doesNotContain("co-author", "admin");
        assertThat(TestAccountTemplates.known("learner14")).isTrue();
        assertThat(TestAccountTemplates.known("learner28")).isFalse();
        assertThat(TestAccountTemplates.known(null)).isFalse();
        for (String staff : new String[]{"co-author", "admin"}) assertThat(TestAccountTemplates.known(staff)).as(staff).isFalse();
    }

    @Test void labelsSayWhatEachScenarioHas() {
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(1))).isEqualTo("Free · no study plan");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(6))).isEqualTo("Learn · ending soon · student discount · custom plan · in progress");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(12))).isEqualTo("Advance · ending soon · Grow next · ready-made plan · in progress");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(19))).isEqualTo("Complete · active · staff upgrade from Learn · ready-made plan · in progress");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(17))).contains("upgraded from Learn");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(26))).isEqualTo("Monthly Promo · expired · plus Interview pass Promo · sale discount · ready-made plan · finished");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(27))).contains("plus Interview sprint", "several plans");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(24))).startsWith("Annual Promo");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(22))).contains("interview plan", "finished");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(4))).endsWith("not started");
        assertThat(TestAccountTemplates.label(TestAccountScenario.of(3))).endsWith("overdue");
        for (var template : TestAccountTemplates.all()) assertThat(template.label()).isNotBlank().doesNotContain(" ·  · ");
    }

    /** DLV-969 (owner, 2026-11-04): the one hierarchy, every acting role against every kind of target. */
    @Test void onlyLearnerScenariosAreCreatable() {
        assertThat(TestAccountTemplates.creatable()).hasSize(27).allSatisfy(t -> assertThat(t.roles()).isEmpty()).isEqualTo(TestAccountTemplates.all());
        assertThat(TestAccountTemplates.creatable()).anyMatch(template -> template.id().equals("learner01"));
        for (String staff : new String[]{"co-author", "admin", "nobody", null}) assertThat(TestAccountTemplates.creatable()).noneMatch(template -> template.id().equals(staff));
    }
}
