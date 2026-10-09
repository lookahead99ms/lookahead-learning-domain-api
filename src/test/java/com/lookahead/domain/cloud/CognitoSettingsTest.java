package com.lookahead.domain.cloud;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CognitoSettingsTest {
    private MockEnvironment valid(String mode) {
        return new MockEnvironment().withProperty("app.deployment-environment", mode)
                .withProperty("app.cognito.issuer", "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example")
                .withProperty("app.cognito.region", "us-east-2")
                .withProperty("app.cognito.client-id", "syntheticclient123456")
                .withProperty("app.cognito.scope-prefix", "lookahead")
                .withProperty("app.cognito.gateway-secret", "synthetic-internal-gateway-secret-for-settings-tests");
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void bothCloudModesRequireTheirExplicitPoolClientAndSecret(String mode) {
        var environment = valid(mode);
        var settings = CognitoSettings.from(environment);
        assertThat(settings.issuer()).isEqualTo(environment.getProperty("app.cognito.issuer"));
        assertThat(settings.toString()).doesNotContain(settings.gatewaySecret());
        for (String key : new String[]{"issuer", "region", "client-id", "scope-prefix", "gateway-secret"}) {
            var missing = valid(mode).withProperty("app.cognito." + key, "");
            assertThatThrownBy(() -> CognitoSettings.from(missing)).isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void regionIssuerAndClientBoundaryCannotBeRedirectedOrWeakened(String mode) {
        for (String issuer : new String[]{
                "http://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example",
                "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Example",
                "https://cognito-idp.us-east-2.amazonaws.com/us-east-1_Example",
                "https://cognito-idp.us-east-2.amazonaws.com.attacker.invalid/us-east-2_Example",
                "https://user@cognito-idp.us-east-2.amazonaws.com/us-east-2_Example",
                "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example?pool=other"}) {
            assertThatThrownBy(() -> CognitoSettings.from(valid(mode).withProperty("app.cognito.issuer", issuer)))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (var invalid : Map.of("client-id", "client-with-punctuation", "scope-prefix", "lookahead/",
                "gateway-secret", "synthetic-secret-containing-a-control\n").entrySet()) {
            assertThatThrownBy(() -> CognitoSettings.from(valid(mode).withProperty("app.cognito." + invalid.getKey(), invalid.getValue())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void cloudRequiresInjectedSecretValuesWithoutDisclosingReferences(String mode) {
        for (String secret : new String[]{
                "arn:aws:secretsmanager:us-east-2:123456789012:secret:synthetic",
                "${SYNTHETIC_UNRESOLVED_GATEWAY_SECRET}",
                "synthetic-prefix-${SYNTHETIC_UNRESOLVED_GATEWAY_SECRET}"}) {
            assertThatThrownBy(() -> CognitoSettings.from(valid(mode).withProperty("app.cognito.gateway-secret", secret)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(secret).hasNoCause();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void cloudCannotActivateLocalOrAnotherApplicationRole(String mode) {
        for (String profile : new String[]{"local", "local-test", "gateway", "oauth-server"}) {
            var environment = valid(mode);
            environment.setActiveProfiles(profile);
            assertThatThrownBy(() -> CognitoSettings.from(environment)).isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"local", "", "test", "production"})
    void configuredCloudCredentialsDoNotSelectAnUnsupportedEnvironment(String mode) {
        assertThatThrownBy(() -> CognitoSettings.from(valid(mode))).isInstanceOf(IllegalStateException.class);
    }
}
