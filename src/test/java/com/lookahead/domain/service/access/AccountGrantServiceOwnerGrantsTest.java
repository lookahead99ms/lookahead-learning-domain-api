package com.lookahead.domain.service.access;

import com.lookahead.domain.db.store.access.AccountSubject;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.membership.MembershipGrants;
import com.lookahead.domain.service.content.AccountCatalogService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import com.lookahead.domain.util.CatalogIds;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;

/**
 * DLV-968 (2026-11-02): the owner (cloud author capability) holds the whole curriculum without a staff assignment,
 * and the staff documents scope goes to the owner and to staff who preview documents (admin, co-author, reviewer).
 */
class AccountGrantServiceOwnerGrantsTest {
    private static final Set<String> CATALOG = Set.of("learn:docker", "grow:system-design");
    private final AccountSubject store = mock(AccountSubject.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final StaffAccess staff = mock(StaffAccess.class,withSettings().mockMaker(MockMakers.SUBCLASS));
    private final AccountCatalogService catalog = mock(AccountCatalogService.class);
    private final MembershipGrants memberships = mock(MembershipGrants.class, withSettings().mockMaker(MockMakers.PROXY));
    private final UUID account = UUID.randomUUID();

    private AccountGrantService repository(boolean owner, StaffAccess.Assignment assignment) {
        when(store.grantTopics(account)).thenReturn(List.of());
        when(store.authorEnabled(account)).thenReturn(owner);
        when(staff.forAccount(account)).thenReturn(assignment);
        when(catalog.allTopicIds()).thenReturn(CATALOG);
        return new AccountGrantService(store, staff, catalog, memberships);
    }

    private StaffAccess.Assignment assignment(Set<String> roles, Set<String> topics, boolean full) {
        return new StaffAccess.Assignment(UUID.randomUUID(), account, null, roles, topics, full, 1);
    }

    @Test void ownerHoldsEveryTopicAndTheStaffDocumentsScopeWithoutAStaffAssignment() {
        assertThat(repository(true, null).findTopicGrants(account))
                .containsExactlyInAnyOrder("learn:docker", "grow:system-design", CatalogIds.STAFF_DOCUMENTS_SCOPE);
    }

    @Test void anOrdinaryAccountWithoutGrantsHoldsNothing() {
        assertThat(repository(false, null).findTopicGrants(account)).isEmpty();
    }

    @Test void aMembershipAddsEveryTopicOfItsCatalogScopeAndNothingElse() {
        when(memberships.catalogScopes(account)).thenReturn(Set.of("learn"));
        assertThat(repository(false, null).findTopicGrants(account)).containsExactly("learn:docker");
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
