package com.lookahead.domain.security;

import com.lookahead.domain.DomainFixtureGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class IdentitySettingsTest {
    static MockEnvironment local() {
        return new MockEnvironment().withProperty("app.deployment-environment", "local")
                .withProperty("app.identity.issuer", "http://127.0.0.1:4380")
                .withProperty("app.identity.upstream", "http://identity:8080")
                .withProperty("app.identity.gateway-client-id", "lookahead-web-gateway")
                .withProperty("app.identity.verifier-secret", "synthetic-verifier-secret-for-unit-tests");
    }
    @Test void explicitLocalServiceOriginWorksAndSecretsAreRedacted() {
        var settings = IdentitySettings.from(local());
        assertThat(settings.upstream()).isEqualTo("http://identity:8080");
        assertThat(settings.toString()).doesNotContain(settings.verifierSecret());
    }
    @Test void incompleteConfigurationAndWeakSecretFailStartup() {
        assertThatThrownBy(() -> IdentitySettings.from(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IdentitySettings.from(local().withProperty("app.identity.verifier-secret", "short"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IdentitySettings.from(local().withProperty("app.identity.gateway-client-id", ""))).isInstanceOf(IllegalStateException.class);
    }
    @Test void nonLocalModesRequireHttpsAndRejectMixedProfiles() {
        assertThatThrownBy(() -> IdentitySettings.from(local().withProperty("app.deployment-environment", "prod"))).isInstanceOf(IllegalStateException.class);
        var env = local().withProperty("app.deployment-environment", "prod")
                .withProperty("app.identity.issuer", "https://learning.example.test")
                .withProperty("app.identity.upstream", "https://identity.example.test");
        assertThatCode(() -> IdentitySettings.from(env)).doesNotThrowAnyException();
        env.setActiveProfiles("local-test");
        assertThatThrownBy(() -> IdentitySettings.from(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void arbitraryHttpOriginsPathsCredentialsAndApplicationRolesAreRejected() {
        for (String origin : new String[]{"http://evil.example", "http://identity/path", "http://user@identity", "http://identity?host=other", "http://identity#fragment"})
            assertThatThrownBy(() -> IdentitySettings.from(local().withProperty("app.identity.upstream", origin))).isInstanceOf(IllegalStateException.class);
        for (String profile : new String[]{"gateway", "oauth-server", "prod"}) {
            var env = local(); env.setActiveProfiles(profile);
            assertThatThrownBy(() -> IdentitySettings.from(env)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void fixturePermissionsNeverRequirePasswordSecretsAndCannotEscapeLocal() {
        var env = local().withProperty("app.local-test.seed-enabled", "true").withProperty("app.local-test.author-enabled", "true");
        assertThatThrownBy(() -> new DomainFixtureGuard(env)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("accounts", "local-test");
        assertThatCode(() -> new DomainFixtureGuard(env)).doesNotThrowAnyException();
        env.withProperty("app.deployment-environment", "prod");
        assertThatThrownBy(() -> new DomainFixtureGuard(env)).isInstanceOf(IllegalStateException.class);
    }
}
