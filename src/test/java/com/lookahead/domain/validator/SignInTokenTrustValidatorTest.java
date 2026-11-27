package com.lookahead.domain.validator;

import com.lookahead.domain.log.LogCapture;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignInTokenTrustValidatorTest {
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
        var checked = SignInTokenTrustValidator.validated(environment);
        var trust = checked.trust();
        assertThat(trust.issuer()).isEqualTo(environment.getProperty("app.cognito.issuer"));
        assertThat(trust.region()).isEqualTo("us-east-2");
        assertThat(trust.clientId()).isEqualTo("syntheticclient123456");
        assertThat(trust.scopePrefix()).isEqualTo("lookahead");
        // DLV-1016: the Gateway BFF's secret is the other part of the same checked pass; none of the three prints it.
        String secret = environment.getProperty("app.cognito.gateway-secret");
        assertThat(checked.bffSecret().value()).isEqualTo(secret);
        assertThat(trust.toString()).isEqualTo("SignInTokenTrust[redacted]");
        assertThat(checked.bffSecret().toString()).isEqualTo("SignInBffSecret[redacted]");
        assertThat(checked.toString()).isEqualTo("SignInTokenTrust.Checked[redacted]").doesNotContain(secret);
        for (String key : new String[]{"issuer", "region", "client-id", "scope-prefix", "gateway-secret"}) {
            var missing = valid(mode).withProperty("app.cognito." + key, "");
            assertThatThrownBy(() -> SignInTokenTrustValidator.validated(missing)).isInstanceOf(IllegalStateException.class);
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
            assertThatThrownBy(() -> SignInTokenTrustValidator.validated(valid(mode).withProperty("app.cognito.issuer", issuer)))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (var invalid : Map.of("client-id", "client-with-punctuation", "scope-prefix", "lookahead/",
                "gateway-secret", "synthetic-secret-containing-a-control\n").entrySet()) {
            assertThatThrownBy(() -> SignInTokenTrustValidator.validated(valid(mode).withProperty("app.cognito." + invalid.getKey(), invalid.getValue())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void cloudRequiresInjectedSecretValuesWithoutDisclosingReferences(String mode) {
        for (String secret : new String[]{
                "arn:aws:secretsmanager:us-east-2:123456789012:secret:synthetic",
                "${SYNTHETIC_UNRESOLVED_GATEWAY_SECRET}",
                "synthetic-prefix-${SYNTHETIC_UNRESOLVED_GATEWAY_SECRET}"}) {
            assertThatThrownBy(() -> SignInTokenTrustValidator.validated(valid(mode).withProperty("app.cognito.gateway-secret", secret)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(secret).hasNoCause();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void cloudCannotActivateLocalOrAnotherApplicationRole(String mode) {
        for (String profile : new String[]{"local", "local-test", "gateway", "oauth-server"}) {
            var environment = valid(mode);
            environment.setActiveProfiles(profile);
            assertThatThrownBy(() -> SignInTokenTrustValidator.validated(environment)).isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"local", "", "test", "production"})
    void configuredCloudCredentialsDoNotSelectAnUnsupportedEnvironment(String mode) {
        assertThatThrownBy(() -> SignInTokenTrustValidator.validated(valid(mode))).isInstanceOf(IllegalStateException.class);
    }

    /**
     * DLV-1016: the settings are checked in one fixed pass, so when several are wrong the same one is refused first:
     * environment, then issuer and region (each must be set, then both must match), then client ID, scope prefix and
     * Gateway secret (all three must be set before any of their formats is judged), then the three formats in that order.
     * Each case makes one more leading setting right and leaves everything after it wrong.
     */
    @Test void whenSeveralSettingsAreWrongTheRefusalsComeInOneFixedOrder() {
        String issuer = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
        var steps = new ArrayList<String[]>();
        // environment, issuer, region, client ID, scope prefix, Gateway secret -> reason, setting named, exception text
        steps.add(new String[]{"", "", "", "", "", "", "SETTING_MISSING", "app.deployment-environment (LOOKAHEAD_ENVIRONMENT)", "app.deployment-environment is required"});
        steps.add(new String[]{"local", "", "", "", "", "", "ENVIRONMENT_INVALID", "app.deployment-environment (LOOKAHEAD_ENVIRONMENT)", "Cognito requires an explicit cloud Domain environment"});
        steps.add(new String[]{"dev", "", "", "", "", "", "SETTING_MISSING", "app.cognito.issuer (LOOKAHEAD_COGNITO_ISSUER)", "app.cognito.issuer is required"});
        steps.add(new String[]{"dev", "http://wrong", "", "", "", "", "SETTING_MISSING", "app.cognito.region (AWS_REGION)", "app.cognito.region is required"});
        steps.add(new String[]{"dev", "http://wrong", "us-east-2", "", "", "", "COGNITO_ISSUER_INVALID",
                "app.cognito.issuer (LOOKAHEAD_COGNITO_ISSUER) and app.cognito.region (AWS_REGION)", "Cognito issuer must identify the selected regional pool"});
        steps.add(new String[]{"dev", issuer, "us-east-2", "", "", "", "SETTING_MISSING", "app.cognito.client-id (APP_OAUTH_CLIENT_ID)", "app.cognito.client-id is required"});
        steps.add(new String[]{"dev", issuer, "us-east-2", "bad client", "", "", "SETTING_MISSING", "app.cognito.scope-prefix (LOOKAHEAD_COGNITO_SCOPE_PREFIX)", "app.cognito.scope-prefix is required"});
        steps.add(new String[]{"dev", issuer, "us-east-2", "bad client", "bad/", "", "SETTING_MISSING", "app.cognito.gateway-secret (LOOKAHEAD_DOMAIN_GATEWAY_SECRET)", "app.cognito.gateway-secret is required"});
        steps.add(new String[]{"dev", issuer, "us-east-2", "bad client", "bad/", "short", "SETTING_INVALID", "app.cognito.client-id (APP_OAUTH_CLIENT_ID)", SignInTokenTrustValidator.INVALID});
        steps.add(new String[]{"dev", issuer, "us-east-2", "syntheticclient123456", "bad/", "short", "SETTING_INVALID", "app.cognito.scope-prefix (LOOKAHEAD_COGNITO_SCOPE_PREFIX)", SignInTokenTrustValidator.INVALID});
        steps.add(new String[]{"dev", issuer, "us-east-2", "syntheticclient123456", "lookahead", "short", "SECRET_INVALID", "app.cognito.gateway-secret (LOOKAHEAD_DOMAIN_GATEWAY_SECRET)", SignInTokenTrustValidator.INVALID});
        for (String[] step : steps) {
            var environment = new MockEnvironment().withProperty("app.deployment-environment", step[0])
                    .withProperty("app.cognito.issuer", step[1]).withProperty("app.cognito.region", step[2])
                    .withProperty("app.cognito.client-id", step[3]).withProperty("app.cognito.scope-prefix", step[4])
                    .withProperty("app.cognito.gateway-secret", step[5]);
            try (var log = LogCapture.start("lookahead.startup")) {
                assertThatThrownBy(() -> SignInTokenTrustValidator.validated(environment)).isExactlyInstanceOf(IllegalStateException.class).hasMessage(step[8]);
                var line = log.only("startup.check");
                assertThat(LogCapture.keys(line)).containsEntry("reason", step[6]).containsEntry("setting", step[7]);
                assertThat(line.getFormattedMessage()).startsWith("Startup refused: " + step[7] + " ");
            }
        }
    }
}
