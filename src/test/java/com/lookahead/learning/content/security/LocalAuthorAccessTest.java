package com.lookahead.learning.content.security;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class LocalAuthorAccessTest {
    private MockEnvironment local() {
        var env = new MockEnvironment().withProperty("app.local-test.author-enabled", "true")
                .withProperty("app.deployment-environment", "local");
        env.setActiveProfiles("accounts", "local-test"); return env;
    }
    private AccountPrincipal author() {
        return new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, LocalAuthorAccess.USERNAME, "Author", true);
    }
    @Test void onlyTheReservedLocalAccountHasTheCapability() {
        var access = new LocalAuthorAccess(local());
        assertThat(access.allowed(author())).isTrue();
        assertThat(access.allowed(new AccountPrincipal(UUID.randomUUID(), LocalAuthorAccess.USERNAME, "Author", true))).isFalse();
        assertThat(access.allowed(new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, "learner01", "Author", true))).isFalse();
        assertThat(access.allowed(new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, LocalAuthorAccess.USERNAME, "Author", false))).isFalse();
        assertThat(access.allowed(null)).isFalse();
    }
    @Test void capabilityIsDisabledOutsideExplicitLocalDevelopment() {
        var env = local(); var access = new LocalAuthorAccess(env);
        env.setProperty("app.local-test.author-enabled", "false"); assertThat(access.allowed(author())).isFalse();
        env.setProperty("app.local-test.author-enabled", "true");
        env.setProperty("app.deployment-environment", "prod"); assertThat(access.allowed(author())).isFalse();
        env.setProperty("app.deployment-environment", "local");
        env.setActiveProfiles("accounts"); assertThat(access.allowed(author())).isFalse();
        env.setActiveProfiles("accounts", "local-test", "production"); assertThat(access.allowed(author())).isFalse();
    }
}
