package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
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
    private final AccountRepository accounts = mock(AccountRepository.class,
            withSettings().mockMaker(MockMakers.SUBCLASS));
    private final AuthorPreviewAccessService access = service(false);

    @Test void reservedAuthorWithEveryCurrentCatalogGrantIsAllowed() {
        when(accounts.findTopicGrants(LocalAuthorAccess.ACCOUNT_ID))
                .thenReturn(Set.of("learn:dsa", "grow:api", "unrelated:scope"));
        assertThatCode(() -> access.requireAccess(author(true))).doesNotThrowAnyException();
        verify(accounts).findTopicGrants(LocalAuthorAccess.ACCOUNT_ID);
    }

    @Test void learnerCannotUseFullCatalogGrantsAsAnAuthorCapability() {
        denied(new AccountPrincipal(UUID.randomUUID(), "learner01", "Author", true));
        verifyNoInteractions(accounts);
    }

    @Test void usernameAndDisplayNameCannotSpoofTheReservedAccount() {
        denied(new AccountPrincipal(UUID.randomUUID(), LocalAuthorAccess.USERNAME, "Author", true));
        denied(new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, "learner01", "Author", true));
        verifyNoInteractions(accounts);
    }

    @Test void disabledOrMissingAccountCannotAccessPreviews() {
        denied(author(false));
        denied(null);
        verifyNoInteractions(accounts);
    }

    @Test void everyRequestRequiresAllCurrentlyUnexpiredGrants() {
        when(accounts.findTopicGrants(LocalAuthorAccess.ACCOUNT_ID))
                .thenReturn(Set.of("learn:dsa", "grow:api"), Set.of("learn:dsa"), Set.of());
        assertThatCode(() -> access.requireAccess(author(true))).doesNotThrowAnyException();
        // The repository excludes expired/revoked grants; a prior full grant set is never cached.
        denied(author(true));
        denied(author(true));
        verify(accounts, times(3)).findTopicGrants(LocalAuthorAccess.ACCOUNT_ID);
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
        return new AuthorPreviewAccessService(new LocalAuthorAccess(environment), accounts,
                new SnapshotValidator(mapper, catalog));
    }

    private static MockEnvironment localEnvironment() {
        var environment = new MockEnvironment().withProperty("app.deployment-environment", "local")
                .withProperty("app.local-test.author-enabled", "true");
        environment.setActiveProfiles("accounts", "local-test", "resource");
        return environment;
    }

    private static AccountPrincipal author(boolean enabled) {
        return new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, LocalAuthorAccess.USERNAME,
                "Synthetic Author", enabled);
    }
}
