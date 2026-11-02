package com.lookahead.learning.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lookahead.domain.access.StaffAccessRepository;
import com.lookahead.learning.content.util.CatalogIds;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DLV-968 (2026-11-02): the owner (cloud author capability) holds the whole curriculum without a staff assignment,
 * and the staff documents scope goes to the owner and to staff who preview documents (admin, co-author, reviewer).
 */
class AccountRepositoryOwnerGrantsTest {
    private static final Set<String> CATALOG = Set.of("learn:docker", "grow:system-design");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StaffAccessRepository staff = mock(StaffAccessRepository.class);
    private final SnapshotValidator catalog = mock(SnapshotValidator.class);
    private final UUID account = UUID.randomUUID();

    private AccountRepository repository(boolean owner, StaffAccessRepository.Assignment assignment) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(account))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("author_access"), eq(Boolean.class), eq(account))).thenReturn(owner);
        when(staff.forAccount(account)).thenReturn(assignment);
        when(catalog.allTopicIds()).thenReturn(CATALOG);
        return new AccountRepository(jdbc, staff, catalog);
    }

    private StaffAccessRepository.Assignment assignment(Set<String> roles, Set<String> topics, boolean full) {
        return new StaffAccessRepository.Assignment(UUID.randomUUID(), account, null, roles, topics, full, 1);
    }

    @Test void ownerHoldsEveryTopicAndTheStaffDocumentsScopeWithoutAStaffAssignment() {
        assertThat(repository(true, null).findTopicGrants(account))
                .containsExactlyInAnyOrder("learn:docker", "grow:system-design", CatalogIds.STAFF_DOCUMENTS_SCOPE);
    }

    @Test void anOrdinaryAccountWithoutGrantsHoldsNothing() {
        assertThat(repository(false, null).findTopicGrants(account)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "co-author", "reviewer"})
    void documentPreviewingStaffReceiveTheStaffDocumentsScope(String role) {
        var grants = repository(false, assignment(Set.of(role), Set.of(), false)).findTopicGrants(account);
        assertThat(grants).contains(CatalogIds.STAFF_DOCUMENTS_SCOPE);
        // The scope is additive to whatever curriculum the role carries: reviewer has none, admin and co-author have all.
        assertThat(CatalogIds.curriculumGrants(grants)).isEqualTo(role.equals("reviewer") ? Set.of() : CATALOG);
    }

    @Test void testersAndAssignedLearnersNeverReceiveTheStaffDocumentsScope() {
        assertThat(repository(false, assignment(Set.of("tester"), Set.of(), false)).findTopicGrants(account))
                .containsExactlyInAnyOrderElementsOf(CATALOG);
        assertThat(repository(false, assignment(Set.of(), Set.of("learn:docker"), false)).findTopicGrants(account))
                .containsExactly("learn:docker");
        assertThat(repository(false, assignment(Set.of(), Set.of(), true)).findTopicGrants(account))
                .containsExactlyInAnyOrderElementsOf(CATALOG);
    }
}
