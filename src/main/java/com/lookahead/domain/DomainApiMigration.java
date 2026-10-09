package com.lookahead.domain;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** One-shot Domain schema migration. Provisioning databases and roles remains infrastructure-owned. */
public final class DomainApiMigration {
    private DomainApiMigration() {}
    public static void main(String[] args) throws Exception {
        String user = required("SPRING_FLYWAY_USER");
        String url = required("SPRING_FLYWAY_URL");
        validateMigrationTarget(user, url, System.getenv().getOrDefault("LOOKAHEAD_ENVIRONMENT", "local"), System.getenv("AWS_REGION"));
        String file = System.getenv("LOOKAHEAD_MIGRATION_PASSWORD_FILE");
        String injected = System.getenv("SPRING_FLYWAY_PASSWORD");
        if (file != null && injected != null) throw new IllegalStateException("Choose one migration password source");
        String password = injected != null ? injected : Files.readString(Path.of(file != null ? file : "/run/secrets/spring.flyway.password")).stripTrailing();
        if (password.isBlank()) throw new IllegalStateException("Migration password is required");
        verifyMigrationRole(url, user, password);
        Flyway.configure().dataSource(com.lookahead.domain.database.DomainDatabaseConnections.boundedDataSource(url, user, password)).defaultSchema("public").schemas("public")
                .locations("classpath:db/domain").cleanDisabled(true).load().migrate();
    }
    public static void validateMigrationTarget(String user, String url) {
        if (!LegacyStorageNames.MIGRATOR_ROLE.equals(user))
            throw new IllegalStateException("Domain requires its dedicated migration role");
        com.lookahead.learning.content.config.AccountDatabaseConfiguration.validateJdbcUrl(url);
    }

    public static void validateMigrationTarget(String user, String url, String environment, String region) {
        validateMigrationTarget(user, url);
        com.lookahead.domain.database.DomainDatabaseConnections.validateTarget(environment, region, url);
    }

    public static void verifyMigrationRole(String url, String user, String password) throws java.sql.SQLException {
        var source = com.lookahead.domain.database.DomainDatabaseConnections.boundedDataSource(url, user, password);
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement("""
                    SELECT current_user = ? AND session_user=current_user
                    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles
                      WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
                    AND NOT pg_catalog.has_database_privilege(current_user,pg_catalog.current_database(),'CREATE')
                    """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, LegacyStorageNames.MIGRATOR_ROLE);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1))
                    throw new IllegalStateException("Migration database role violates application isolation");
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
