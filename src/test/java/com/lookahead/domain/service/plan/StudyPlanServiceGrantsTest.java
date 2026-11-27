package com.lookahead.domain.service.plan;

import com.lookahead.domain.db.store.membership.MembershipGrants;
import com.lookahead.domain.db.store.plan.StudyPlanParts;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.util.StudyPlanJson;
import com.lookahead.domain.validator.StudyPlanRecoveryValidator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-968: plan access checks see curriculum grants only; the staff documents scope authorizes no plan content.
 * DLV-1016: the computation moved from the plan repository to StudyPlanService; these two tests moved with it
 * (the first was a test class of its own on the store, the second a test in StudyPlanStoreTest).
 */
class StudyPlanServiceGrantsTest {
    private final StudyPlanParts repository = mock(StudyPlanParts.class, withSettings().mockMaker(MockMakers.PROXY));
    private final AccountGrantService accounts = mock(AccountGrantService.class);
    private final UUID account = UUID.randomUUID();
    private final AccountCatalogService catalog = mock(AccountCatalogService.class);
    private StudyPlanService service() { var mapper = new JsonMapper(); return new StudyPlanService(repository, repository, repository, mapper, null, catalog, new StudyPlanJson(mapper), new StudyPlanRecoveryValidator(catalog.catalog())); }

    @Test void staffScopeIsDroppedFromPlanGrants() {
        when(accounts.findTopicGrants(account)).thenReturn(Set.of("staff:documents", "learn:core-java", "blaze:system-design"));
        var service = service();
        service.configureGrantAccounts(accounts);
        assertThat(service.grants(account)).containsExactlyInAnyOrder("blaze:system-design", "learn:core-java");
        verifyNoInteractions(repository); // the grants come from the identity slice, never from a table read
    }

    @Test void grantsComeFromTheAccountGrantServiceOrMembershipsNeverFromStaffScopes() {
        UUID owner = account;
        when(accounts.findTopicGrants(owner)).thenReturn(Set.of("staff:documents", "learn:core-java"));
        var service = service();
        service.configureGrantAccounts(accounts);
        assertThat(service.grants(owner)).containsExactly("learn:core-java");
        var memberships = mock(MembershipGrants.class, withSettings().mockMaker(MockMakers.PROXY));
        // DLV-1016: the grants store answers scopes; the service expands them with the catalog's topics.
        when(memberships.catalogScopes(owner)).thenReturn(Set.of("grow"));
        when(catalog.allTopicIds()).thenReturn(Set.of("grow:x", "learn:core-java"));
        var fallback = service();
        fallback.configureGrantMemberships(memberships);
        assertThat(fallback.grants(owner)).containsExactly("grow:x");
        var publication = mock(ProtectedContentManifestService.class);
        fallback.configureGrantPublication(publication);
        assertThat(fallback.grants(owner)).containsExactly("grow:x");
        verifyNoInteractions(repository);
    }
}
