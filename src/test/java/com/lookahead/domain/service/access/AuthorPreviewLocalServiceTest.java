package com.lookahead.domain.service.access;

import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorPreviewLocalServiceTest {
    private MockEnvironment local() {
        var env = new MockEnvironment().withProperty("app.local-test.author-enabled", "true")
                .withProperty("app.deployment-environment", "local");
        env.setActiveProfiles("accounts", "local-test"); return env;
    }
    private AccountPrincipal author() {
        return new AccountPrincipal(AuthorPreviewLocalService.ACCOUNT_ID, AuthorPreviewLocalService.USERNAME, "Author", true);
    }
    @Test void onlyTheReservedLocalAccountHasTheCapability() {
        var access = new AuthorPreviewLocalService(local());
        assertThat(access.allowed(author())).isTrue();
        assertThat(access.allowed(new AccountPrincipal(UUID.randomUUID(), AuthorPreviewLocalService.USERNAME, "Author", true))).isFalse();
        assertThat(access.allowed(new AccountPrincipal(AuthorPreviewLocalService.ACCOUNT_ID, "learner01", "Author", true))).isFalse();
        assertThat(access.allowed(new AccountPrincipal(AuthorPreviewLocalService.ACCOUNT_ID, AuthorPreviewLocalService.USERNAME, "Author", false))).isFalse();
        assertThat(access.allowed(null)).isFalse();
    }
    @Test void capabilityIsDisabledOutsideExplicitLocalDevelopment() {
        var env = local(); var access = new AuthorPreviewLocalService(env);
        env.setProperty("app.local-test.author-enabled", "false"); assertThat(access.allowed(author())).isFalse();
        env.setProperty("app.local-test.author-enabled", "true");
        env.setProperty("app.deployment-environment", "prod"); assertThat(access.allowed(author())).isFalse();
        env.setProperty("app.deployment-environment", "local");
        env.setActiveProfiles("accounts"); assertThat(access.allowed(author())).isFalse();
        env.setActiveProfiles("accounts", "local-test", "production"); assertThat(access.allowed(author())).isFalse();
    }
}
