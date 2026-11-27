package com.lookahead.domain.util;

import com.lookahead.domain.model.StaffPermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The role hierarchy shared by the People list, test accounts and test sessions. */
class StaffAccessRoleRulesTest {
    @Test void nobodyGainsARoleAboveTheirOwn() {
        Set<String> owner = Set.of("owner"), coauthor = Set.of("co-author"), admin = Set.of("admin"), learner = Set.of();
        Set<String> both = Set.of("admin", "co-author"), reviewer = Set.of("reviewer");
        record Row(String actor, boolean owner, boolean coauthor, boolean admin, boolean learner, boolean both, boolean reviewer) {}
        for (var row : List.of(
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
            assertThat(StaffAccessRoleRules.allows(row.actor(), owner)).as(as + " -> owner").isEqualTo(row.owner());
            assertThat(StaffAccessRoleRules.allows(row.actor(), coauthor)).as(as + " -> co-author").isEqualTo(row.coauthor());
            assertThat(StaffAccessRoleRules.allows(row.actor(), admin)).as(as + " -> admin").isEqualTo(row.admin());
            assertThat(StaffAccessRoleRules.allows(row.actor(), learner)).as(as + " -> learner").isEqualTo(row.learner());
            assertThat(StaffAccessRoleRules.allows(row.actor(), both)).as(as + " -> admin+co-author").isEqualTo(row.both());
            assertThat(StaffAccessRoleRules.allows(row.actor(), reviewer)).as(as + " -> reviewer").isEqualTo(row.reviewer());
            assertThat(StaffAccessRoleRules.allows(row.actor(), null)).isFalse();
        }
    }

    /** "Lower role wins"; reviewers and testers count as learners; both role helpers agree for every combination. */
    @Test void theActingRoleIsTheLowerStaffRoleAndBothHelpersAgree() {
        assertThat(StaffAccessRoleRules.role(true, Set.of("admin"))).isEqualTo("owner");
        assertThat(StaffAccessRoleRules.role(false, Set.of("admin", "co-author"))).isEqualTo("admin");
        assertThat(StaffAccessRoleRules.role(false, Set.of("co-author", "reviewer"))).isEqualTo("co-author");
        assertThat(StaffAccessRoleRules.role(false, Set.of("reviewer", "tester"))).isEqualTo("learner");
        assertThat(StaffAccessRoleRules.role(false, Set.of("tester"))).isEqualTo("learner");
        assertThat(StaffAccessRoleRules.role(false, Set.of("reviewer"))).isEqualTo("learner");
        assertThat(StaffAccessRoleRules.role(false, Set.of("co-author", "tester"))).isEqualTo("co-author");
        assertThat(StaffAccessRoleRules.role(false, Set.of())).isEqualTo("learner");
        assertThat(StaffAccessRoleRules.role(false, null)).isEqualTo("learner");
        var all = List.copyOf(StaffPermissions.ROLES);
        for (int mask = 0; mask < 1 << all.size(); mask++) {
            var roles = new HashSet<String>();
            for (int bit = 0; bit < all.size(); bit++) if ((mask & 1 << bit) != 0) roles.add(all.get(bit));
            for (boolean owner : new boolean[]{true, false})
                assertThat(StaffAccessRoleRules.role(StaffPermissions.forRoles(owner, roles)))
                        .as(owner + " " + roles).isEqualTo(StaffAccessRoleRules.role(owner, roles));
        }
    }

    /** DLV-969 (owner, 2026-11-04): only learner scenarios are created; DLV-971: that is every scenario there is. */
}
