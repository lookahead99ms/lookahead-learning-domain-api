package com.lookahead.domain.database;

import com.lookahead.domain.DomainApiMigration;
import com.lookahead.domain.compatibility.LegacyStorageNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DomainDatabaseConnectionsTest {
    @TempDir Path directory;

    Path certificate() throws Exception {
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

    String cloudUrl(Path certificate) {
        return "jdbc:postgresql://synthetic.abc.us-east-2.rds.amazonaws.com:5432/lookahead_platform?sslmode=verify-full&sslrootcert=" + certificate;
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void runtimeAndMigrationShareTheSameExplicitCloudTarget(String mode) throws Exception {
        String url = cloudUrl(certificate());
        assertThatCode(() -> DomainDatabaseConnections.validateTarget(mode, "us-east-2", url)).doesNotThrowAnyException();
        assertThatCode(() -> DomainApiMigration.validateMigrationTarget(LegacyStorageNames.MIGRATOR_ROLE, url, mode, "us-east-2"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DomainApiMigration.validateMigrationTarget(LegacyStorageNames.RUNTIME_ROLE, url, mode, "us-east-2"))
                .hasMessage("Domain requires its dedicated migration role");
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void cloudRejectsTlsDowngradesWrongDatabaseRegionAndMissingTrustMaterial(String mode) throws Exception {
        String valid = cloudUrl(certificate());
        for (String url : List.of(valid.replace("lookahead_platform", "identity"),
                valid.replace("us-east-2.rds", "us-west-2.rds"),
                valid.replace("synthetic.abc.us-east-2.rds.amazonaws.com", "127.0.0.1"),
                valid.replace("rds.amazonaws.com", "rds.amazonaws.com.attacker.invalid"),
                valid.substring(0, valid.indexOf('?')),
                valid.replace("verify-full", "disable"), valid.replace("verify-full", "require"),
                valid.replace("verify-full", "verify-ca"), valid.substring(0, valid.indexOf("&sslrootcert")),
                cloudUrl(directory.resolve("missing.pem")))) {
            assertThatThrownBy(() -> DomainApiMigration.validateMigrationTarget(LegacyStorageNames.MIGRATOR_ROLE, url, mode, "us-east-2"))
                    .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining("synthetic.abc");
        }
        for (String region : new String[]{null, "", "us-east-1", "us-east-2.attacker"})
            assertThatThrownBy(() -> DomainDatabaseConnections.validateTarget(mode, region, valid)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"dev", "prod"})
    void untrustedCertificateFilesCannotPassCloudPreflight(String mode) throws Exception {
        Path empty = directory.resolve("empty.pem"); Files.writeString(empty, "");
        Path malformed = directory.resolve("invalid.pem"); Files.writeString(malformed, "not a certificate");
        Path large = directory.resolve("large.pem"); Files.write(large, new byte[1024 * 1024 + 1]);
        Path link = directory.resolve("link.pem"); Files.createSymbolicLink(link, certificate());
        for (Path path : List.of(empty, malformed, large, link, directory))
            assertThatThrownBy(() -> DomainDatabaseConnections.validateTarget(mode, "us-east-2", cloudUrl(path)))
                    .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test void localRetainsMountedDatabaseOptionsAndUnknownModesNeverFallBack() {
        String local = "jdbc:postgresql://platform-db:5432/local_database?sslmode=disable";
        assertThatCode(() -> DomainDatabaseConnections.validateTarget("local", null, local)).doesNotThrowAnyException();
        assertThatCode(() -> DomainApiMigration.validateMigrationTarget(LegacyStorageNames.MIGRATOR_ROLE, local, "local", null)).doesNotThrowAnyException();
        for (String mode : new String[]{null, "", "production", "DEV"})
            assertThatThrownBy(() -> DomainDatabaseConnections.validateTarget(mode, null, local))
                    .hasMessage("Domain database requires explicit local, dev or prod environment");
    }

    @Test void everyOperatorConnectionKeepsBoundedPropertiesAndDriverCredentials() throws Exception {
        String url = "jdbc:postgresql://transport-test.invalid/lookahead_platform";
        var connections = new ArrayList<Properties>();
        var queryTimeouts = new ArrayList<Integer>();
        var queries = new ArrayList<String>();
        var roleBindings = new ArrayList<String>();
        var closed = new HashMap<String, Integer>();
        Driver driver = new Driver() {
            public boolean acceptsURL(String candidate) { return url.equals(candidate); }
            public Connection connect(String candidate, Properties properties) throws SQLException {
                if (!acceptsURL(candidate)) return null;
                connections.add((Properties) properties.clone());
                boolean[] next = {true};
                ResultSet result = jdbcProxy(ResultSet.class, (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> { boolean available = next[0]; next[0] = false; yield available; }
                    case "getBoolean" -> { assertThat(arguments[0]).isEqualTo(1); yield true; }
                    case "close" -> { closed.merge("result", 1, Integer::sum); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
                PreparedStatement statement = jdbcProxy(PreparedStatement.class, (proxy, method, arguments) -> switch (method.getName()) {
                    case "setQueryTimeout" -> { queryTimeouts.add((Integer) arguments[0]); yield null; }
                    case "setString" -> { assertThat(arguments[0]).isEqualTo(1); roleBindings.add((String) arguments[1]); yield null; }
                    case "executeQuery" -> { assertThat(arguments).isNull(); yield result; }
                    case "close" -> { closed.merge("statement", 1, Integer::sum); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
                return jdbcProxy(Connection.class, (proxy, method, arguments) -> switch (method.getName()) {
                    case "prepareStatement" -> { queries.add((String) arguments[0]); yield statement; }
                    case "close" -> { closed.merge("connection", 1, Integer::sum); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            }
            public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; } public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; } public Logger getParentLogger() { return Logger.getGlobal(); }
        };
        // Register before pgJDBC so no network request can occur for the synthetic target.
        var registered = Collections.list(DriverManager.getDrivers());
        for (Driver existing : registered) DriverManager.deregisterDriver(existing);
        DriverManager.registerDriver(driver);
        try {
            DomainApiMigration.verifyMigrationRole(url, LegacyStorageNames.MIGRATOR_ROLE, "synthetic-password");
            var source = DomainDatabaseConnections.boundedDataSource(url, LegacyStorageNames.MIGRATOR_ROLE, "synthetic-password");
            try (var first = source.getConnection(); var second = source.getConnection()) { assertThat(first).isNotSameAs(second); }
            assertThat(connections).hasSize(3);
            assertThat(queryTimeouts).containsExactly(5);
            assertThat(queries).singleElement().asString().contains("current_user = ?", "session_user=current_user", "rolsuper", "rolcreatedb", "rolcreaterole", "rolreplication", "rolbypassrls", "has_database_privilege");
            assertThat(roleBindings).containsExactly(LegacyStorageNames.MIGRATOR_ROLE);
            assertThat(closed).containsEntry("connection", 3).containsEntry("statement", 1).containsEntry("result", 1);
            for (Properties properties : connections) {
                assertThat(properties).containsEntry("connectTimeout", "3").containsEntry("socketTimeout", "5")
                        .containsEntry("cancelSignalTimeout", "2").containsEntry("user", LegacyStorageNames.MIGRATOR_ROLE)
                        .containsEntry("password", "synthetic-password");
            }
        } finally {
            DriverManager.deregisterDriver(driver);
            for (Driver existing : registered) DriverManager.registerDriver(existing);
        }
    }
    private static <T> T jdbcProxy(Class<T> contract, java.lang.reflect.InvocationHandler handler) {
        return contract.cast(java.lang.reflect.Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "toString" -> "Recording " + contract.getSimpleName();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                    return handler.invoke(proxy, method, arguments);
                }));
    }
}
