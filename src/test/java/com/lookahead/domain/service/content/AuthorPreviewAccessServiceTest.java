package com.lookahead.domain.service.content;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.db.store.access.StaffAccess;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthorPreviewAccessServiceTest {
    private final MockEnvironment environment = localEnvironment();
    private final AccountGrantService accounts = mock(AccountGrantService.class,
            withSettings().mockMaker(MockMakers.SUBCLASS));
    private final StaffAccess assignments = mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final AuthorPreviewAccessService access = service(false);

    @Test void reservedAuthorWithEveryCurrentCatalogGrantIsAllowed() {
        when(accounts.findTopicGrants(AuthorPreviewLocalService.ACCOUNT_ID))
                .thenReturn(Set.of("learn:dsa", "grow:api", "unrelated:scope"));
        assertThatCode(() -> access.requireAccess(author(true))).doesNotThrowAnyException();
        verify(accounts).findTopicGrants(AuthorPreviewLocalService.ACCOUNT_ID);
    }

    @Test void learnerCannotUseFullCatalogGrantsAsAnAuthorCapability() {
        denied(new AccountPrincipal(UUID.randomUUID(), "learner01", "Author", true));
        verifyNoInteractions(accounts);
    }

    @Test void usernameAndDisplayNameCannotSpoofTheReservedAccount() {
        denied(new AccountPrincipal(UUID.randomUUID(), AuthorPreviewLocalService.USERNAME, "Author", true));
        denied(new AccountPrincipal(AuthorPreviewLocalService.ACCOUNT_ID, "learner01", "Author", true));
        verifyNoInteractions(accounts);
    }

    /** The staff permissions are read first, as in every environment: without an enabled sign-in there is nobody to read them for. */
    @Test void disabledOrMissingAccountIsAskedToSignIn() {
        for (AccountPrincipal nobody : Arrays.asList(author(false), null))
            assertThatThrownBy(() -> access.requireAccess(nobody))
                    .isInstanceOf(AccountFailure.class).extracting("status").isEqualTo(401);
        verifyNoInteractions(accounts);
    }

    @Test void staffWhoPreviewDocumentsAreAllowedWithEveryGrantAndATesterIsNot() {
        var reviewer = new AccountPrincipal(UUID.randomUUID(), "reviewer01", "Reviewer", true);
        var tester = new AccountPrincipal(UUID.randomUUID(), "tester01", "Tester", true);
        when(assignments.forAccount(reviewer.accountId())).thenReturn(new StaffAccess.Assignment(UUID.randomUUID(), reviewer.accountId(), null, Set.of("reviewer"), Set.of(), false, 1));
        when(assignments.forAccount(tester.accountId())).thenReturn(new StaffAccess.Assignment(UUID.randomUUID(), tester.accountId(), null, Set.of("tester"), Set.of(), false, 1));
        when(accounts.findTopicGrants(reviewer.accountId())).thenReturn(Set.of("learn:dsa", "grow:api"), Set.of("learn:dsa"));
        assertThatCode(() -> access.requireAccess(reviewer)).doesNotThrowAnyException();
        denied(reviewer); // a grant short of the whole catalog
        denied(tester);
        verify(accounts, never()).findTopicGrants(tester.accountId());
    }

    @Test void everyRequestRequiresAllCurrentlyUnexpiredGrants() {
        when(accounts.findTopicGrants(AuthorPreviewLocalService.ACCOUNT_ID))
                .thenReturn(Set.of("learn:dsa", "grow:api"), Set.of("learn:dsa"), Set.of());
        assertThatCode(() -> access.requireAccess(author(true))).doesNotThrowAnyException();
        // The repository excludes expired/revoked grants; a prior full grant set is never cached.
        denied(author(true));
        denied(author(true));
        verify(accounts, times(3)).findTopicGrants(AuthorPreviewLocalService.ACCOUNT_ID);
    }

    @Test void emptyTrustedCatalogNeverConfersAccess() {
        assertThatThrownBy(() -> service(true).requireAccess(author(true)))
                .isInstanceOf(AccountFailure.class).extracting("status").isEqualTo(403);
        verifyNoInteractions(accounts);
    }

    @Test void authorCapabilityMustRemainEnabledAndLocal() {
        environment.setProperty("app.local-test.author-enabled", "false");
        denied(author(true));
        environment.setProperty("app.local-test.author-enabled", "true");
        environment.setProperty("app.deployment-environment", "prod");
        denied(author(true));
        environment.setProperty("app.deployment-environment", "local");
        environment.setActiveProfiles("accounts", "local-test", "resource", "production");
        denied(author(true));
        environment.setActiveProfiles("accounts", "resource");
        denied(author(true));
        verifyNoInteractions(accounts);
    }

    private void denied(AccountPrincipal principal) {
        assertThatThrownBy(() -> access.requireAccess(principal))
                .isInstanceOf(AccountFailure.class).extracting("status").isEqualTo(403);
    }

    private AuthorPreviewAccessService service(boolean emptyCatalog) {
        var mapper = new ObjectMapper();
        var catalog = mapper.createObjectNode();
        catalog.put("schemaVersion", "account-catalog/v1");
        catalog.put("catalogVersion", "sha256:" + "a".repeat(64));
        catalog.putArray("algorithmVersions");
        catalog.putArray("rankingVersions");
        var records = catalog.putArray("records");
        if (!emptyCatalog) {
            var record = records.addObject().put("id", "synthetic-lesson").put("contentType", "lesson");
            record.putArray("topicIds").add("learn:dsa").add("grow:api");
            record.putArray("route").add("learn").add("synthetic-lesson");
        }
        var trusted = new AccountCatalogService(mapper, catalog);
        return new AuthorPreviewAccessService(new StaffAccessService(assignments, new AuthorPreviewLocalService(environment), trusted), accounts, trusted);
    }

    private static MockEnvironment localEnvironment() {
        var environment = new MockEnvironment().withProperty("app.deployment-environment", "local")
                .withProperty("app.local-test.author-enabled", "true");
        environment.setActiveProfiles("accounts", "local-test", "resource");
        return environment;
    }

    private static AccountPrincipal author(boolean enabled) {
        return new AccountPrincipal(AuthorPreviewLocalService.ACCOUNT_ID, AuthorPreviewLocalService.USERNAME,
                "Synthetic Author", enabled);
    }
}
