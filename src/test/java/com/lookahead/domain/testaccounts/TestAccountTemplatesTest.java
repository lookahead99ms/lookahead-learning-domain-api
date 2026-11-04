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

    /** DLV-969 (owner, 2026-11-04): the one hierarchy, every acting role against every kind of target. */
    @Test void nobodyGainsARoleAboveTheirOwn() {
        Set<String> owner = Set.of("owner"), coauthor = Set.of("co-author"), admin = Set.of("admin"), learner = Set.of();
        Set<String> both = Set.of("admin", "co-author"), reviewer = Set.of("reviewer");
        record Row(String actor, boolean owner, boolean coauthor, boolean admin, boolean learner, boolean both, boolean reviewer) {}
        for (var row : java.util.List.of(
                new Row("owner", true, true, true, true, true, true),
                new Row("co-author", false, true, true, true, true, true),
                new Row("admin", false, false, true, true, false, false),
                // Owner decision 2026-11-04: learners, reviewers and testers never switch.
                new Row("learner", false, false, false, false, false, false),
                new Row("", false, false, false, false, false, false),
                new Row("user", false, false, false, false, false, false),
                new Row("reviewer", false, false, false, false, false, false),
                new Row(null, false, false, false, false, false, false))) {
            String as = String.valueOf(row.actor());
            assertThat(TestAccountTemplates.allows(row.actor(), owner)).as(as + " -> owner").isEqualTo(row.owner());
            assertThat(TestAccountTemplates.allows(row.actor(), coauthor)).as(as + " -> co-author").isEqualTo(row.coauthor());
            assertThat(TestAccountTemplates.allows(row.actor(), admin)).as(as + " -> admin").isEqualTo(row.admin());
            assertThat(TestAccountTemplates.allows(row.actor(), learner)).as(as + " -> learner").isEqualTo(row.learner());
            assertThat(TestAccountTemplates.allows(row.actor(), both)).as(as + " -> admin+co-author").isEqualTo(row.both());
            assertThat(TestAccountTemplates.allows(row.actor(), reviewer)).as(as + " -> reviewer").isEqualTo(row.reviewer());
            assertThat(TestAccountTemplates.allows(row.actor(), null)).isFalse();
        }
    }

    /** "Lower role wins"; reviewers and testers count as learners; both role helpers agree for every combination. */
    @Test void theActingRoleIsTheLowerStaffRoleAndBothHelpersAgree() {
        assertThat(TestAccountTemplates.role(true, Set.of("admin"))).isEqualTo("owner");
        assertThat(TestAccountTemplates.role(false, Set.of("admin", "co-author"))).isEqualTo("admin");
        assertThat(TestAccountTemplates.role(false, Set.of("co-author", "reviewer"))).isEqualTo("co-author");
        assertThat(TestAccountTemplates.role(false, Set.of("reviewer", "tester"))).isEqualTo("learner");
        assertThat(TestAccountTemplates.role(false, Set.of("tester"))).isEqualTo("learner");
        assertThat(TestAccountTemplates.role(false, Set.of("reviewer"))).isEqualTo("learner");
        assertThat(TestAccountTemplates.role(false, Set.of("co-author", "tester"))).isEqualTo("co-author");
        assertThat(TestAccountTemplates.role(false, Set.of())).isEqualTo("learner");
        assertThat(TestAccountTemplates.role(false, null)).isEqualTo("learner");
        var all = java.util.List.copyOf(com.lookahead.domain.access.StaffPermissions.ROLES);
        for (int mask = 0; mask < 1 << all.size(); mask++) {
            var roles = new java.util.HashSet<String>();
            for (int bit = 0; bit < all.size(); bit++) if ((mask & 1 << bit) != 0) roles.add(all.get(bit));
            for (boolean owner : new boolean[]{true, false})
                assertThat(TestAccountTemplates.role(com.lookahead.domain.access.StaffPermissions.forRoles(owner, roles)))
                        .as(owner + " " + roles).isEqualTo(TestAccountTemplates.role(owner, roles));
        }
    }

    /** DLV-969 (owner, 2026-11-04): only learner scenarios are created; staff test accounts come from the roster. */
    @Test void onlyLearnerScenariosAreCreatable() {
        assertThat(TestAccountTemplates.creatable()).hasSize(27).allSatisfy(t -> assertThat(t.roles()).isEmpty());
        assertThat(TestAccountTemplates.creatable("learner01")).isTrue();
        for (String staff : new String[]{"co-author", "admin", "nobody", null}) assertThat(TestAccountTemplates.creatable(staff)).isFalse();
    }
}
