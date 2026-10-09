package com.lookahead.domain.database;

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

    public static void validateTarget(String environment, String region, String url) {
        if (!Set.of("local", "dev", "prod").contains(environment == null ? "" : environment))
            throw new IllegalStateException("Domain database requires explicit local, dev or prod environment");
        AccountDatabaseConfiguration.validateJdbcUrl(url);
        if (environment.equals("local")) return;
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            if (region == null || !region.matches("[a-z]{2}-[a-z]+-\\d")
                    || !uri.getHost().endsWith("." + region + ".rds.amazonaws.com")
                    || !uri.getPath().equals("/lookahead_platform")) throw new IllegalArgumentException();
            var query = new HashMap<String, String>();
            for (String field : uri.getRawQuery().split("&")) {
                String[] pair = field.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            if (!"verify-full".equals(query.get("sslmode"))) throw new IllegalArgumentException();
            Path certificate = Path.of(query.get("sslrootcert"));
            if (!certificate.isAbsolute() || !Files.isRegularFile(certificate) || !Files.isReadable(certificate)
                    || Files.isSymbolicLink(certificate) || Files.size(certificate) > 1024 * 1024)
                throw new IllegalArgumentException();
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
