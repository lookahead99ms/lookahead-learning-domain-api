package com.lookahead.domain.testaccounts;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TestAccountDeletionPolicyTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test void ownerCanDeleteOtherManagedRolesButNeverSelf() {
        for (var roles : List.of(Set.<String>of(), Set.of("admin"), Set.of("co-author")))
            assertThat(TestAccountDeletionPolicy.allows("owner", actor, target, roles)).isTrue();
        assertThat(TestAccountDeletionPolicy.allows("owner", actor, actor, Set.of())).isFalse();
    }

    @Test void coauthorCanDeleteAdminsAndLearnersButNotPeers() {
        assertThat(TestAccountDeletionPolicy.allows("co-author", actor, target, Set.of())).isTrue();
        assertThat(TestAccountDeletionPolicy.allows("co-author", actor, target, Set.of("admin"))).isTrue();
        assertThat(TestAccountDeletionPolicy.allows("co-author", actor, target, Set.of("admin", "reviewer"))).isTrue();
        assertThat(TestAccountDeletionPolicy.allows("co-author", actor, target, Set.of("co-author"))).isFalse();
        assertThat(TestAccountDeletionPolicy.allows("co-author", actor, target, Set.of("admin", "co-author"))).isFalse();
    }

    @Test void adminCanDeleteLearnersOnlyAndLearnerCannotDelete() {
        assertThat(TestAccountDeletionPolicy.allows("admin", actor, target, Set.of())).isTrue();
        assertThat(TestAccountDeletionPolicy.allows("admin", actor, target, Set.of("admin"))).isFalse();
        assertThat(TestAccountDeletionPolicy.allows("admin", actor, target, Set.of("co-author"))).isFalse();
        assertThat(TestAccountDeletionPolicy.allows("user", actor, target, Set.of())).isFalse();
    }
}
