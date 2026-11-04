package com.lookahead.domain.database;

import com.lookahead.domain.StartupChecks;
import com.lookahead.learning.content.config.AccountDatabaseConfiguration;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared transport contract for runtime, migrations and one-shot account administration. */
public final class DomainDatabaseConnections {
    private DomainDatabaseConnections() { }

    /** The setting that carries the JDBC URL: the runtime's, or the migration's. */
    public static final String URL_SETTING = "the database JDBC URL (LOOKAHEAD_DOMAIN_JDBC_URL, or SPRING_FLYWAY_URL for migrations)";

    public static void validateTarget(String environment, String region, String url) {
        if (!Set.of("local", "dev", "prod").contains(environment == null ? "" : environment))
            throw StartupChecks.refuse("app.deployment-environment (LOOKAHEAD_ENVIRONMENT)", "ENVIRONMENT_INVALID", "must be local, dev or prod",
                    "Domain database requires explicit local, dev or prod environment");
        AccountDatabaseConfiguration.validateJdbcUrl(url);
        if (environment.equals("local")) return;
        // DLV-969 (owner, 2026-11-04): the refusal names the part that is wrong; never the URL, host, path or parser message.
        String setting = URL_SETTING, problem = "could not be parsed";
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            if (region == null || !region.matches("[a-z]{2}-[a-z]+-\\d")) { setting = "AWS_REGION (app.publication.region)"; problem = "is not an AWS region name"; throw new IllegalArgumentException(); }
            problem = "does not point at an RDS endpoint in AWS_REGION";
            if (!uri.getHost().endsWith("." + region + ".rds.amazonaws.com")) throw new IllegalArgumentException();
            problem = "does not name the lookahead_platform database";
            if (!uri.getPath().equals("/lookahead_platform")) throw new IllegalArgumentException();
            problem = "has no sslmode and sslrootcert parameters";
            var query = new HashMap<String, String>();
            for (String field : uri.getRawQuery().split("&")) {
                String[] pair = field.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            problem = "does not set sslmode=verify-full";
            if (!"verify-full".equals(query.get("sslmode"))) throw new IllegalArgumentException();
            problem = "has no sslrootcert, or its CA bundle file is not absolute, missing, unreadable, a symbolic link or larger than 1 MB";
            Path certificate = Path.of(query.get("sslrootcert"));
            if (!certificate.isAbsolute() || !Files.isRegularFile(certificate) || !Files.isReadable(certificate)
                    || Files.isSymbolicLink(certificate) || Files.size(certificate) > 1024 * 1024)
                throw new IllegalArgumentException();
            problem = "has an sslrootcert CA bundle that holds no certificate, or one that is expired, not yet valid or not a CA";
            try (var input = Files.newInputStream(certificate)) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty()) throw new IllegalArgumentException();
                for (var parsed : certificates) {
                    var authority = (X509Certificate) parsed;
                    authority.checkValidity();
                    if (authority.getBasicConstraints() < 0) throw new IllegalArgumentException();
                }
            }
        } catch (Exception invalid) {
            // Never expose an operator URL, password, certificate path or parser exception.
            StartupChecks.refused(setting, "DATABASE_TARGET_INVALID", problem, invalid);
            throw new IllegalStateException("Cloud database requires lookahead_platform at a regional RDS endpoint and verify-full TLS with a valid CA bundle");
        }
    }

    public static Properties timeoutProperties() {
        var properties = new Properties();
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        properties.setProperty("cancelSignalTimeout", "2");
        return properties;
    }

    public static DriverManagerDataSource boundedDataSource(String url, String user, String password) {
        AccountDatabaseConfiguration.validateJdbcUrl(url);
        var source = new DriverManagerDataSource(url, user, password);
        source.setConnectionProperties(timeoutProperties());
        return source;
    }
}
