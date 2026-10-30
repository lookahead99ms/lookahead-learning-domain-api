package com.lookahead.domain.publication;

import com.lookahead.domain.cloud.CognitoSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

/** Load shipped configuration with synthetic deployment inputs; no server or AWS calls. */
class DomainActivationConfigurationTest {
    @TempDir Path directory;

    ApplicationContextRunner configuration(String mode) throws Exception {
        var fixture = new CloudRuntimeGuardTest();
        fixture.directory = directory;
        String jdbc = fixture.valid().getProperty("spring.datasource.url");
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=optional:configtree:/nonexistent-domain-activation-contract/",
                        "LOOKAHEAD_ENVIRONMENT=" + mode,
                        "LOOKAHEAD_DOMAIN_JDBC_URL=" + jdbc,
                        "LOOKAHEAD_DOMAIN_DB_PASSWORD=" + "A".repeat(48),
                        "LOOKAHEAD_DOMAIN_GATEWAY_SECRET=synthetic-domain-gateway-secret-at-least-32",
                        "APP_OAUTH_CLIENT_ID=SyntheticApplicationClient123",
                        "LOOKAHEAD_COGNITO_ISSUER=https://cognito-idp.us-east-2.amazonaws.com/us-east-2_SyntheticPool",
                        "LOOKAHEAD_COGNITO_SCOPE_PREFIX=lookahead-dev",
                        "AWS_REGION=us-east-2",
                        "LOOKAHEAD_AWS_ACCOUNT_ID=123456789012",
                        "LOOKAHEAD_PUBLICATION_BUCKET=synthetic-publication",
                        "LOOKAHEAD_PUBLICATION_RELEASE_ID=v2026.10.30.00.00",
                        "LOOKAHEAD_PUBLICATION_MANIFEST_SHA256=" + "a".repeat(64),
                        "LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256=" + "b".repeat(64));
    }

    @Test void devInputsBindToTheShippedMtlsReadinessAndPinnedPublicationContract() throws Exception {
        configuration("dev").withPropertyValues("spring.profiles.active=lower-cost-dev").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8443);
            assertThat(environment.getProperty("server.ssl.enabled", Boolean.class)).isTrue();
            assertThat(environment.getProperty("server.ssl.client-auth")).isEqualTo("need");
            assertThat(environment.getProperty("server.ssl.bundle")).isEqualTo("serving");
            assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
            assertThat(environment.getProperty("management.server.port", Integer.class)).isEqualTo(8081);
            assertThat(environment.getProperty("management.server.ssl.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                    .isEqualTo("readinessState,domainDatabase,contentPublication");
            assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("app.local-test.seed-enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("app.local-test.author-enabled", Boolean.class)).isFalse();
            assertThatCode(() -> CloudRuntimeGuard.validate(environment)).doesNotThrowAnyException();
            assertThat(CognitoSettings.from(environment).clientId()).isEqualTo("SyntheticApplicationClient123");
            assertThat(CognitoSettings.from(environment).toString()).isEqualTo("CognitoSettings[redacted]");
            var publication = PublicationSettings.from(environment);
            assertThat(publication.cloud()).isTrue();
            assertThat(publication.live()).isFalse();
            assertThat(publication.releaseId()).isEqualTo("v2026.10.30.00.00");
        });
    }

    @ParameterizedTest @ValueSource(strings = {"local", "prod"})
    void devTransportProfileCannotActivateAnotherEnvironment(String mode) throws Exception {
        configuration(mode).withPropertyValues("spring.profiles.active=lower-cost-dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> CloudRuntimeGuard.validate(context.getEnvironment()))
                    .hasMessage("Lower-cost DEV requires application mTLS");
        });
    }

    @Test void ordinaryLocalConfigurationKeepsItsLoopbackPortAndMountedPublicationSelection() throws Exception {
        configuration("local").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
            assertThat(environment.getProperty("server.ssl.enabled", Boolean.class, false)).isFalse();
            assertThat(environment.getProperty("management.server.port")).isNull();
            assertThat(PublicationSettings.from(environment).cloud()).isFalse();
        });
    }
}
