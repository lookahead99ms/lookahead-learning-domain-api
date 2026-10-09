package com.lookahead.domain;

import java.util.Map;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DomainApiBootstrapTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="DOMAIN_BOOTSTRAP_TEST_URL", matches=".+")
    void restrictedPostgresBootstrapIsIdempotentAndRejectsUnsafeExistingState() throws Exception {
        String url = System.getenv("DOMAIN_BOOTSTRAP_TEST_URL");
        String master = "synthetic-master-password-123";
        String app = "synthetic-app-password-123";
        String migrator = "synthetic-migrator-password-123";
        String admin = java.nio.file.Files.readString(java.nio.file.Path.of(System.getenv("DOMAIN_BOOTSTRAP_TEST_PASSWORD_FILE"))).strip();
        try (var connection = com.lookahead.domain.database.DomainDatabaseConnections.boundedDataSource(url, "postgres", admin).getConnection();
             var statement = connection.createStatement()) {
            try (var state = statement.executeQuery("SELECT count(*) FROM pg_roles WHERE rolname LIKE 'lookahead_platform_%' OR rolname='lookahead_bootstrap'")) {
                state.next(); assertThat(state.getInt(1)).as("dedicated fresh bootstrap fixture").isZero();
            }
            statement.execute("CREATE ROLE lookahead_bootstrap LOGIN CREATEDB CREATEROLE NOSUPERUSER NOREPLICATION NOBYPASSRLS PASSWORD 'synthetic-master-password-123'");
            statement.execute("ALTER DATABASE lookahead_platform OWNER TO lookahead_bootstrap");
        }
        DomainApiBootstrap.bootstrap(url, master, app, migrator);
        DomainApiBootstrap.bootstrap(url, master, app, migrator);
        assertThatThrownBy(() -> DomainApiBootstrap.bootstrap(url, master, "synthetic-wrong-password-123", migrator)).isInstanceOf(java.sql.SQLException.class);
        DomainApiBootstrap.bootstrap(url, master, app, migrator);
        try (var connection = com.lookahead.domain.database.DomainDatabaseConnections.boundedDataSource(url, "lookahead_bootstrap", master).getConnection();
             var statement = connection.createStatement()) {
            statement.execute("ALTER ROLE lookahead_platform_app CREATEDB");
            assertThatIllegalStateException().isThrownBy(() -> DomainApiBootstrap.bootstrap(url, master, app, migrator));
            statement.execute("ALTER ROLE lookahead_platform_app NOCREATEDB");
        }
        DomainApiBootstrap.bootstrap(url, master, app, migrator);
    }
    @Test void acceptsExactRegionalCloudTargetAndRejectsEndpointSubstitutes() throws Exception {
        String url = "jdbc:postgresql://instance.token.us-east-1.rds.amazonaws.com:5432/lookahead_platform?sslmode=verify-full&sslrootcert="
            + java.nio.file.Path.of("tools/container/trust/rds-global-bundle.pem").toAbsolutePath();
        var environment = new java.util.HashMap<>(Map.of("LOOKAHEAD_ENVIRONMENT", "dev", "AWS_REGION", "us-east-1", "SPRING_FLYWAY_URL", url,
            "LOOKAHEAD_BOOTSTRAP_PASSWORD", "synthetic-master-password-123", "LOOKAHEAD_APP_PASSWORD", "synthetic-app-password-123", "LOOKAHEAD_MIGRATOR_PASSWORD", "synthetic-migrator-password-123"));
        assertThatCode(() -> DomainApiBootstrap.validateConfiguration(environment)).doesNotThrowAnyException();
        for (String changed : new String[]{url.replace(":5432", ":9999"), url.replace("instance.token.", "instance.extra.token."), url.replace("us-east-1.rds", "us-west-2.rds")}) {
            environment.put("SPRING_FLYWAY_URL", changed);
            assertThatIllegalStateException().isThrownBy(() -> DomainApiBootstrap.validateConfiguration(environment));
        }
    }
    @Test void rejectsLocalOrImplicitEnvironmentBeforeConnecting() {
        for (String environment : new String[]{"local", "", "DEV"})
            assertThatIllegalStateException().isThrownBy(() -> DomainApiBootstrap.validateConfiguration(Map.of("LOOKAHEAD_ENVIRONMENT", environment)));
        assertThatIllegalStateException().isThrownBy(() -> DomainApiBootstrap.validateConfiguration(Map.of()));
    }
    @Test void rejectsRedirectedOrUnverifiedDatabaseWithoutExposingSecrets() {
        for (String url : new String[]{"jdbc:postgresql://localhost:5432/lookahead_platform", "jdbc:postgresql://instance.token.us-east-1.rds.amazonaws.com:5432/lookahead_platform?sslmode=require",
                "jdbc:postgresql://instance.token.us-east-1.rds.amazonaws.com:5432/lookahead_platform?password=secret-value"})
            assertThatIllegalStateException().isThrownBy(() -> DomainApiBootstrap.validateConfiguration(Map.of(
                "LOOKAHEAD_ENVIRONMENT", "dev", "AWS_REGION", "us-east-1", "SPRING_FLYWAY_URL", url)))
                .withMessageNotContaining("secret-value");
    }
    @Test void acceptsOnlySecretsSupportedWithoutSaslprepAmbiguity() {
        assertThatCode(() -> DomainApiBootstrap.validatePassword("synthetic-0123456789!'")).doesNotThrowAnyException();
        for (String password : new String[]{"short", "synthetic password 1234", "synthetic-password-é", "x".repeat(129), "synthetic-0123456789\n"})
            assertThatIllegalStateException().isThrownBy(() -> DomainApiBootstrap.validatePassword(password));
    }
    @Test void scramVerifierMatchesIndependentPostgresCompatibleVector() throws Exception {
        assertThat(DomainApiBootstrap.scramVerifier("synthetic-test-password-123", HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f")))
            .isEqualTo("SCRAM-SHA-256$4096:AAECAwQFBgcICQoLDA0ODw==$LaEVRzoZnxnkcfzKkhlhLM34qwgnsON/HRVhYZIPYwA=:C6f+2v5C8z8s2Vevs/wMQjuRpl04UcyEkOfhQkHrBt0=");
        assertThatIllegalArgumentException().isThrownBy(() -> DomainApiBootstrap.scramVerifier("synthetic-test-password-123", new byte[2]));
    }
}
