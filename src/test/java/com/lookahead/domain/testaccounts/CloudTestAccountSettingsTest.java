package com.lookahead.domain.testaccounts;

import com.lookahead.domain.cloud.CognitoSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class CloudTestAccountSettingsTest {
    static final String ISSUER = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example";
    static final CognitoSettings COGNITO = new CognitoSettings(ISSUER, "us-east-2", "syntheticclient123456", "lookahead",
            "synthetic-gateway-proof-secret-for-unit-tests");
    static MockEnvironment env(String mode, String identity, String pool) {
        var env = new MockEnvironment().withProperty("app.deployment-environment", mode).withProperty("app.test-accounts.identity", identity);
        if (pool != null) env.withProperty("app.test-accounts.pool-id", pool);
        return env;
    }
    @Test void devCognitoWithTheSignInPoolIsAccepted() {
        var settings = CloudTestAccountSettings.from(env("dev", "cognito", "us-east-2_Example"), COGNITO);
        assertThat(settings.poolId()).isEqualTo("us-east-2_Example");
        assertThat(settings.issuer()).isEqualTo(ISSUER);
        assertThat(CloudTestAccountSettings.from(env("dev", "cognito", " us-east-2_Example "), COGNITO).poolId()).isEqualTo("us-east-2_Example");
    }
    @Test void prodAndLocalRefuseBecauseTestAccountsNeverExistThere() {
        for (String mode : new String[]{"prod", "production", "local", ""})
            assertThatThrownBy(() -> CloudTestAccountSettings.from(env(mode, "cognito", "us-east-2_Example"), COGNITO))
                    .as(mode).isInstanceOf(IllegalStateException.class).hasMessageContaining("never exist");
    }
    @Test void poolMustBeValidatedLikeTheIssuerPool() {
        assertThatThrownBy(() -> CloudTestAccountSettings.from(env("dev", "local", "us-east-2_Example"), COGNITO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity=cognito");
        assertThatThrownBy(() -> CloudTestAccountSettings.from(env("dev", "cognito", "us-east-2_Example"), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cloud sign-in configuration");
        for (String pool : new String[]{null, "", "eu-west-1_Example", "us-east-2_", "us-east-2_Exa mple", "us-east-2_Exämple"})
            assertThatThrownBy(() -> CloudTestAccountSettings.from(env("dev", "cognito", pool), COGNITO))
                    .as(String.valueOf(pool)).isInstanceOf(IllegalStateException.class).hasMessageContaining("configured region");
        assertThatThrownBy(() -> CloudTestAccountSettings.from(env("dev", "cognito", "us-east-2_Other"), COGNITO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sign-in pool");
    }
}
