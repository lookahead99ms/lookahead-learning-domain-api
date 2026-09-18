package com.lookahead.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** One-shot Platform schema migration. Provisioning databases and roles remains infrastructure-owned. */
public final class PlatformMigration {
    private PlatformMigration() {}
    public static void main(String[] args) throws Exception {
        String user = required("SPRING_FLYWAY_USER");
        String url = required("SPRING_FLYWAY_URL");
        validateMigrationTarget(user, url);
        String file = System.getenv("LOOKAHEAD_MIGRATION_PASSWORD_FILE");
        String injected = System.getenv("SPRING_FLYWAY_PASSWORD");
        if (file != null && injected != null) throw new IllegalStateException("Choose one migration password source");
        String password = injected != null ? injected : Files.readString(Path.of(file != null ? file : "/run/secrets/spring.flyway.password")).stripTrailing();
        if (password.isBlank()) throw new IllegalStateException("Migration password is required");
        verifyMigrationRole(url, user, password);
        Flyway.configure().dataSource(url, user, password).defaultSchema("public").schemas("public")
                .locations("classpath:db/platform").cleanDisabled(true).load().migrate();
    }
    static void validateMigrationTarget(String user, String url) {
        if (!"lookahead_platform_migrator".equals(user))
            throw new IllegalStateException("Platform requires its dedicated migration role");
        com.lookahead.learning.content.config.AccountDatabaseConfiguration.validateJdbcUrl(url);
    }

    private static void verifyMigrationRole(String url, String user, String password) throws java.sql.SQLException {
        var properties = new java.util.Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        try (var connection = java.sql.DriverManager.getConnection(url, properties);
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery("SELECT current_user='lookahead_platform_migrator' "
                    + "AND session_user=current_user AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles "
                    + "WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) "
                    + "AND NOT pg_catalog.has_database_privilege(current_user,pg_catalog.current_database(),'CREATE')")) {
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
