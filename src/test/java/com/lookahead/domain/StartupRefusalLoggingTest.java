package com.lookahead.domain;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.cloud.CognitoSettings;
import com.lookahead.domain.database.DomainDatabaseConnections;
import com.lookahead.domain.publication.CloudRuntimeGuard;
import com.lookahead.domain.publication.PublicationSettings;
import com.lookahead.domain.review.ReviewArtifactCatalog;
import com.lookahead.learning.content.config.AccountDatabaseConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-969 (owner, 2026-11-04): before startup is refused, one {@code lookahead.startup} WARN names the setting, secret
 * or file by its name and says what is wrong; never its value, and the exception keeps its fixed text.
 */
class StartupRefusalLoggingTest {
    static final String SECRET = "hunter2-synthetic-secret-value";
    @TempDir Path directory;

    private static String refusal(LogCapture log) {
        var line = log.only("startup.check");
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getLoggerName()).isEqualTo("lookahead.startup");
        return line.getFormattedMessage();
    }

    private static MockEnvironment publication() {
        return new MockEnvironment().withProperty("app.deployment-environment", "dev")
                .withProperty("app.publication.region", "us-east-2").withProperty("app.publication.account-id", "123456789012")
                .withProperty("app.publication.bucket", "synthetic-publication").withProperty("app.publication.directory", "/tmp/lookahead-publications");
    }

    @Test void publicationSettingsNameTheSettingAndItsVariable() {
        var cases = new java.util.LinkedHashMap<MockEnvironment, String>();
        cases.put(publication().withProperty("app.publication.bucket", "Bad_Bucket"),
                "Startup refused: app.publication.bucket (LOOKAHEAD_PUBLICATION_BUCKET) is not a valid S3 bucket name; the app stops before it takes traffic");
        cases.put(publication().withProperty("app.publication.account-id", ""),
                "Startup refused: app.publication.account-id (LOOKAHEAD_AWS_ACCOUNT_ID) is not set; the app stops before it takes traffic");
        cases.put(publication().withProperty("app.publication.directory", "/var/lookahead"),
                "Startup refused: app.publication.directory (LOOKAHEAD_PUBLICATION_DIRECTORY) must be an absolute, normalized folder inside /tmp;"
                        + " the app stops before it takes traffic");
        cases.put(publication().withProperty("app.publication.cache-entries", "0"),
                "Startup refused: app.publication.cache-entries (LOOKAHEAD_CONTENT_CACHE_ENTRIES) must be 1 to 1024; the app stops before it takes traffic");
        cases.put(publication().withProperty("app.publication.release-id", "v2026.11.04.19.00"),
                "Startup refused: LOOKAHEAD_PUBLICATION_RELEASE_ID, LOOKAHEAD_PUBLICATION_MANIFEST_SHA256 and LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256"
                        + " must be all three set (a pinned release) or all three unset (the live release); only some are set; the app stops before it takes traffic");
        cases.put(publication().withProperty("app.publication.release-id", "release-1").withProperty("app.publication.manifest-sha256", "a".repeat(64))
                .withProperty("app.publication.archive-sha256", "b".repeat(64)),
                "Startup refused: app.publication.release-id (LOOKAHEAD_PUBLICATION_RELEASE_ID) is not a Content calendar release tag vYYYY.MM.DD.HH.NN;"
                        + " the app stops before it takes traffic");
        cases.put(publication().withProperty("app.deployment-environment", "staging"),
                "Startup refused: app.deployment-environment (LOOKAHEAD_ENVIRONMENT) must be local, dev or prod and agree with the active profiles"
                        + " (local and local-test only in local; dev, prod and production never in local); the app stops before it takes traffic");
        cases.forEach((environment, expected) -> {
            try (var log = LogCapture.start("lookahead.startup")) {
                assertThatThrownBy(() -> PublicationSettings.from(environment)).isInstanceOf(IllegalStateException.class);
                assertThat(refusal(log)).isEqualTo(expected);
            }
        });
        for (String field : new String[]{"region", "account-id", "bucket", "release-id", "manifest-sha256", "archive-sha256", "directory", "cache-bytes"}) {
            try (var log = LogCapture.start("lookahead.startup")) {
                var environment = publication().withProperty("app.publication.release-id", "v2026.11.04.19.00")
                        .withProperty("app.publication.manifest-sha256", "a".repeat(64)).withProperty("app.publication.archive-sha256", "b".repeat(64))
                        .withProperty("app.publication." + field, field.equals("cache-bytes") ? "-1" : field.equals("directory") ? "/tmp" : "INVALID VALUE");
                assertThatThrownBy(() -> PublicationSettings.from(environment)).hasMessage("Invalid cloud publication configuration");
                assertThat(refusal(log)).startsWith("Startup refused: app.publication." + field + " (").doesNotContain("INVALID VALUE");
            }
        }
    }

    private Path certificate() throws Exception {
        var store = KeyStore.getInstance(Path.of(System.getProperty("java.home"), "lib", "security", "cacerts").toFile(), "changeit".toCharArray());
        for (var aliases = store.aliases(); aliases.hasMoreElements();) {
            var certificate = (X509Certificate) store.getCertificate(aliases.nextElement());
            try { certificate.checkValidity(); } catch (java.security.cert.CertificateException expired) { continue; }
            if (certificate.getBasicConstraints() < 0) continue;
            Path pem = directory.resolve("ca.pem");
            Files.writeString(pem, "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n");
            return pem;
        }
        throw new AssertionError("Java runtime has no current trusted CA fixture");
    }

    private MockEnvironment guard() throws Exception {
        return new MockEnvironment().withProperty("app.deployment-environment", "dev").withProperty("app.publication.region", "us-east-2")
                .withProperty("server.address", "127.0.0.1").withProperty("spring.datasource.password", SECRET)
                .withProperty("spring.datasource.url", "jdbc:postgresql://synthetic.abc.us-east-2.rds.amazonaws.com/lookahead_platform?sslmode=verify-full&sslrootcert=" + certificate());
    }

    @Test void theCloudRuntimeGuardNamesTheSettingItRefused() throws Exception {
        var cases = new java.util.LinkedHashMap<MockEnvironment, String>();
        cases.put(guard().withProperty("AWS_SECRET_ACCESS_KEY", SECRET), "AWS_SECRET_ACCESS_KEY is set; the cloud app uses only the ECS task role");
        cases.put(guard().withProperty("spring.datasource.password", "arn:aws:secretsmanager:us-east-2:123456789012:secret:synthetic"),
                "spring.datasource.password (LOOKAHEAD_DOMAIN_DB_PASSWORD) is a secret ARN; the task definition must inject the secret's value");
        cases.put(guard().withProperty("spring.datasource.password", "short"), "spring.datasource.password (LOOKAHEAD_DOMAIN_DB_PASSWORD) is empty or shorter than 16 characters");
        cases.put(guard().withProperty("spring.datasource.password", "${UNRESOLVED_PASSWORD}"),
                "spring.datasource.password (LOOKAHEAD_DOMAIN_DB_PASSWORD) could not be read: it holds a ${...} placeholder with no value");
        cases.put(guard().withProperty("server.address", "0.0.0.0"), "server.address (LOOKAHEAD_BIND_ADDRESS) must be 127.0.0.1");
        cases.put(guard().withProperty("spring.datasource.url", guard().getProperty("spring.datasource.url").replace("verify-full", "require")),
                "the database JDBC URL (LOOKAHEAD_DOMAIN_JDBC_URL, or SPRING_FLYWAY_URL for migrations) does not set sslmode=verify-full");
        cases.put(guard().withProperty("spring.datasource.url", "jdbc:postgresql://db.internal.example/lookahead_platform?sslmode=verify-full"),
                "the database JDBC URL (LOOKAHEAD_DOMAIN_JDBC_URL, or SPRING_FLYWAY_URL for migrations) does not point at an RDS endpoint in AWS_REGION");
        cases.put(guard().withProperty("spring.datasource.url", "jdbc:postgresql://synthetic.abc.us-east-2.rds.amazonaws.com/other?sslmode=verify-full"),
                "does not name the lookahead_platform database");
        cases.put(guard().withProperty("spring.datasource.url", "jdbc:postgresql://synthetic.abc.us-east-2.rds.amazonaws.com/lookahead_platform?sslmode=verify-full&sslrootcert=/missing/ca.pem"),
                "its CA bundle file is not absolute, missing, unreadable, a symbolic link or larger than 1 MB (IllegalArgumentException)");
        cases.put(guard().withProperty("spring.datasource.url", "jdbc:postgresql://user:" + SECRET + "@db.example/lookahead_platform"),
                "the database JDBC URL (LOOKAHEAD_DOMAIN_JDBC_URL, or SPRING_FLYWAY_URL for migrations) must name one host, an optional port,"
                        + " a database name and no user or password");
        cases.put(guard().withProperty("app.publication.region", "nowhere"), "AWS_REGION (app.publication.region) is not an AWS region name");
        for (var entry : cases.entrySet()) {
            try (var log = LogCapture.start("lookahead.startup")) {
                assertThatThrownBy(() -> CloudRuntimeGuard.validate(entry.getKey())).isInstanceOf(IllegalStateException.class);
                assertThat(refusal(log)).startsWith("Startup refused: ").contains(entry.getValue()).doesNotContain(SECRET, "db.internal", "/missing/ca.pem", "UNRESOLVED_PASSWORD");
            }
        }
        try (var log = LogCapture.start("lookahead.startup")) {
            var lowerCost = guard();
            lowerCost.setActiveProfiles("lower-cost-dev");
            lowerCost.withProperty("server.ssl.enabled", "true").withProperty("server.ssl.client-auth", "want");
            assertThatThrownBy(() -> CloudRuntimeGuard.validate(lowerCost)).hasMessage("Lower-cost DEV requires application mTLS");
            assertThat(refusal(log)).isEqualTo("Startup refused: server.ssl.client-auth must be need for the lower-cost-dev profile (application mTLS);"
                    + " the app stops before it takes traffic");
        }
    }

    @Test void theJdbcUrlRuleBrokenIsNamedWithoutTheUrl() {
        var cases = new java.util.LinkedHashMap<String, String>();
        cases.put("mysql://db.example/x", "does not start with jdbc:postgresql://");
        cases.put("jdbc:postgresql://db.example/x?password=" + SECRET, "has a query parameter other than one sslmode and one sslrootcert, or one without a value");
        cases.put("jdbc:postgresql://db.example/x?sslmode=sometimes", "has an sslmode other than disable, allow, prefer, require, verify-ca or verify-full");
        cases.put("jdbc:postgresql://db.example/x?sslrootcert=relative.pem", "has an sslrootcert that is not an absolute path");
        cases.put("jdbc:postgresql://db.example/x y", "could not be parsed as a URL");
        cases.forEach((url, problem) -> {
            try (var log = LogCapture.start("lookahead.startup")) {
                assertThatThrownBy(() -> AccountDatabaseConfiguration.validateJdbcUrl(url)).isInstanceOf(IllegalStateException.class);
                assertThat(refusal(log)).isEqualTo("Startup refused: " + DomainDatabaseConnections.URL_SETTING + " " + problem + "; the app stops before it takes traffic")
                        .doesNotContain(SECRET, "db.example");
            }
        });
        try (var log = LogCapture.start("lookahead.startup")) {
            assertThatThrownBy(() -> DomainDatabaseConnections.validateTarget("staging", "us-east-2", "jdbc:postgresql://db.example/x"))
                    .hasMessage("Domain database requires explicit local, dev or prod environment");
            assertThat(refusal(log)).isEqualTo("Startup refused: app.deployment-environment (LOOKAHEAD_ENVIRONMENT) must be local, dev or prod;"
                    + " the app stops before it takes traffic");
        }
    }

    private static MockEnvironment cognito() {
        return new MockEnvironment().withProperty("app.deployment-environment", "dev")
                .withProperty("app.cognito.issuer", "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example").withProperty("app.cognito.region", "us-east-2")
                .withProperty("app.cognito.client-id", "syntheticclient123456").withProperty("app.cognito.scope-prefix", "lookahead")
                .withProperty("app.cognito.gateway-secret", SECRET + "-padded-to-thirty-two");
    }

    @Test void cognitoSettingsNameWhichOfTheClientScopePrefixOrSecretIsWrong() {
        var cases = new java.util.LinkedHashMap<MockEnvironment, String>();
        cases.put(cognito().withProperty("app.cognito.client-id", "short"), "app.cognito.client-id (APP_OAUTH_CLIENT_ID) is not 10 to 128 letters and digits");
        cases.put(cognito().withProperty("app.cognito.scope-prefix", "lookahead/"), "app.cognito.scope-prefix (LOOKAHEAD_COGNITO_SCOPE_PREFIX) is not a resource server identifier");
        cases.put(cognito().withProperty("app.cognito.gateway-secret", "arn:aws:secretsmanager:us-east-2:123456789012:secret:x"),
                "app.cognito.gateway-secret (LOOKAHEAD_DOMAIN_GATEWAY_SECRET) is a secret ARN");
        cases.put(cognito().withProperty("app.cognito.gateway-secret", SECRET), "app.cognito.gateway-secret (LOOKAHEAD_DOMAIN_GATEWAY_SECRET) is shorter than 32 characters");
        cases.put(cognito().withProperty("app.cognito.gateway-secret", ""), "app.cognito.gateway-secret (LOOKAHEAD_DOMAIN_GATEWAY_SECRET) is not set");
        cases.put(cognito().withProperty("app.cognito.gateway-secret", "${MISSING_GATEWAY_SECRET}"),
                "app.cognito.gateway-secret (LOOKAHEAD_DOMAIN_GATEWAY_SECRET) could not be read");
        cases.put(cognito().withProperty("app.cognito.region", "us-west-2"), "app.cognito.issuer (LOOKAHEAD_COGNITO_ISSUER) and app.cognito.region (AWS_REGION) must be");
        cases.put(cognito().withProperty("app.deployment-environment", "local"), "app.deployment-environment (LOOKAHEAD_ENVIRONMENT) must be dev or prod");
        for (var entry : cases.entrySet()) {
            try (var log = LogCapture.start("lookahead.startup")) {
                assertThatThrownBy(() -> CognitoSettings.from(entry.getKey())).isInstanceOf(IllegalStateException.class);
                assertThat(refusal(log)).contains(entry.getValue()).doesNotContain(SECRET, "MISSING_GATEWAY_SECRET");
            }
        }
    }

    @Test void anUnreadableReviewManifestIsNamed() throws Exception {
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, "{\"secret\":\"" + SECRET + "\"}");
        try (var log = LogCapture.start("lookahead.startup")) {
            assertThatThrownBy(() -> new ReviewArtifactCatalog(manifest.toString(), new JsonMapper())).hasMessage("Author review manifest is invalid or unavailable");
            assertThat(refusal(log)).startsWith("Startup refused: app.author-reviews.manifest (LOOKAHEAD_AUTHOR_REVIEW_MANIFEST) could not be read")
                    .endsWith("(IllegalArgumentException); the app stops before it takes traffic").doesNotContain(SECRET, directory.toString());
        }
    }
}
