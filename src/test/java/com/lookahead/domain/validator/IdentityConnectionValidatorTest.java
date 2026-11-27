package com.lookahead.domain.validator;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

public class IdentityConnectionValidatorTest {
    public static MockEnvironment local() {
        return new MockEnvironment().withProperty("app.deployment-environment", "local")
                .withProperty("app.identity.issuer", "http://127.0.0.1:4380")
                .withProperty("app.identity.upstream", "http://identity:8080")
                .withProperty("app.identity.gateway-client-id", "lookahead-web-gateway")
                .withProperty("app.identity.verifier-secret", "synthetic-verifier-secret-for-unit-tests");
    }
    @Test void explicitLocalServiceOriginWorksAndSecretsAreRedacted() {
        var connection = IdentityConnectionValidator.validated(local());
        assertThat(connection.upstream()).isEqualTo("http://identity:8080");
        assertThat(connection.toString()).doesNotContain(connection.verifierSecret());
    }
    @Test void incompleteConfigurationAndWeakSecretFailStartup() {
        assertThatThrownBy(() -> IdentityConnectionValidator.validated(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IdentityConnectionValidator.validated(local().withProperty("app.identity.verifier-secret", "short"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IdentityConnectionValidator.validated(local().withProperty("app.identity.gateway-client-id", ""))).isInstanceOf(IllegalStateException.class);
    }
    @Test void nonLocalModesRequireHttpsAndRejectMixedProfiles() {
        assertThatThrownBy(() -> IdentityConnectionValidator.validated(local().withProperty("app.deployment-environment", "prod"))).isInstanceOf(IllegalStateException.class);
        var env = local().withProperty("app.deployment-environment", "prod")
                .withProperty("app.identity.issuer", "https://learning.example.test")
                .withProperty("app.identity.upstream", "https://identity.example.test");
        assertThatCode(() -> IdentityConnectionValidator.validated(env)).doesNotThrowAnyException();
        env.setActiveProfiles("local-test");
        assertThatThrownBy(() -> IdentityConnectionValidator.validated(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void arbitraryHttpOriginsPathsCredentialsAndApplicationRolesAreRejected() {
        for (String origin : new String[]{"http://evil.example", "http://identity/path", "http://user@identity", "http://identity?host=other", "http://identity#fragment"})
            assertThatThrownBy(() -> IdentityConnectionValidator.validated(local().withProperty("app.identity.upstream", origin))).isInstanceOf(IllegalStateException.class);
        for (String profile : new String[]{"gateway", "oauth-server", "prod"}) {
            var env = local(); env.setActiveProfiles(profile);
            assertThatThrownBy(() -> IdentityConnectionValidator.validated(env)).isInstanceOf(IllegalStateException.class);
        }
    }
    /**
     * DLV-1016: the checks moved from IdentityConnection.from in the same order: environment, application roles, profiles,
     * issuer and upstream (each must be set, then each must be an allowed origin), Gateway client ID, verifier secret.
     * Each case makes one more leading setting right and leaves everything after it wrong.
     */
    @Test void whenSeveralSettingsAreWrongTheRefusalsComeInOneFixedOrder() {
        String origins = "Identity origins require HTTPS except explicit local loopback/internal service origins";
        // environment, active profile, issuer, upstream, Gateway client ID, verifier secret -> the refusal
        String[][] steps = {
                {"", "gateway", "", "", "", "", "app.deployment-environment is required"},
                {"test", "gateway", "", "", "", "", "Choose local, dev, or prod deployment environment"},
                {"local", "gateway", "", "", "", "", "Domain cannot activate gateway or authorization-server roles"},
                {"local", "oauth-server", "", "", "", "", "Domain cannot activate gateway or authorization-server roles"},
                {"local", "prod", "", "", "", "", "Local mode cannot activate DEV/PROD profiles"},
                {"dev", "local-test", "", "", "", "", "Local profiles require local mode"},
                {"local", "local-test", "", "", "", "", "app.identity.issuer is required"},
                {"local", "local-test", "http://evil.example", "", "", "", "app.identity.upstream is required"},
                {"local", "local-test", "http://evil.example", "http://evil.example", "", "", origins},
                {"local", "local-test", "http://127.0.0.1:4380", "http://evil.example", "", "", origins},
                {"local", "local-test", "http://identity:8080", "http://identity:8080", "", "", origins},
                {"local", "local-test", "http://127.0.0.1:4380", "http://identity:8080", "", "", "app.identity.gateway-client-id is required"},
                {"local", "local-test", "http://127.0.0.1:4380", "http://identity:8080", "Bad Client", "", "Invalid expected gateway client ID"},
                {"local", "local-test", "http://127.0.0.1:4380", "http://identity:8080", "lookahead-web-gateway", "", "app.identity.verifier-secret is required"},
                {"local", "local-test", "http://127.0.0.1:4380", "http://identity:8080", "lookahead-web-gateway", "short", "Identity verifier secret must contain 32 to 4096 non-control characters"}};
        for (String[] step : steps) {
            var environment = new MockEnvironment().withProperty("app.deployment-environment", step[0]).withProperty("app.identity.issuer", step[2])
                    .withProperty("app.identity.upstream", step[3]).withProperty("app.identity.gateway-client-id", step[4]).withProperty("app.identity.verifier-secret", step[5]);
            environment.setActiveProfiles(step[1]);
            assertThatThrownBy(() -> IdentityConnectionValidator.validated(environment)).as(String.join(" | ", step))
                    .isExactlyInstanceOf(IllegalStateException.class).hasMessage(step[6]);
        }
    }
}
