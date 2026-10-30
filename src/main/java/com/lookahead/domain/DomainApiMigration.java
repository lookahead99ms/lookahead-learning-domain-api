package com.lookahead.domain;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import org.flywaydb.core.Flyway;

/** Retired launcher; migration configuration and role checks remain shared with administration and fixtures. */
public final class DomainApiMigration {
    private DomainApiMigration() {}
    public static void main(String[] args) {
        throw new IllegalStateException("Domain database migration entrypoint is retired; use Infra Flyway Utils");
    }

    /** SQL migrations live in db/domain; Java migrations are listed here so they are never found twice. */
    public static org.flywaydb.core.api.configuration.FluentConfiguration configure(org.flywaydb.core.api.configuration.FluentConfiguration flyway) {
        return flyway.defaultSchema("public").schemas("public").locations("classpath:db/domain")
                .javaMigrations(new com.lookahead.domain.migration.V5__blaze_catalog_id()).cleanDisabled(true);
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

}
