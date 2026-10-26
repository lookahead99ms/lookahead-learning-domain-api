package com.lookahead.domain.database;

import java.net.URI;
import java.sql.SQLException;
import java.util.Set;
import javax.sql.DataSource;

/** Migrator-only Local fixture grant, deliberately outside the common Flyway history. */
public final class LocalAuditProvisioning {
    private LocalAuditProvisioning() {}

    public static boolean requested(String flag, String environment, String url) {
        if (flag == null || "false".equals(flag)) return false;
        if (!"true".equals(flag)) throw new IllegalStateException("Local audit purge must be explicitly true or false");
        com.lookahead.learning.content.config.AccountDatabaseConfiguration.validateJdbcUrl(url);
        URI target = URI.create(url.substring("jdbc:".length()));
        if (!"local".equals(environment)
                || !Set.of("127.0.0.1", "localhost", "[::1]", "domain-api-db").contains(target.getHost())
                || !"/lookahead_platform".equals(target.getPath()))
            throw new IllegalStateException("Local audit purge requires the explicit Local database target");
        return true;
    }

    public static void provision(DataSource source) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            // V12's row trigger still limits this grant to eligible synthetic purchases.
            statement.execute("GRANT DELETE ON public.membership_audit TO lookahead_platform_app");
        }
    }
}
