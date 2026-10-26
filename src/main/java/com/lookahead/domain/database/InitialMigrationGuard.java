package com.lookahead.domain.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Holds a session lock while the explicitly reviewed initial cloud migration runs. */
public final class InitialMigrationGuard implements AutoCloseable {
    private static final long LOCK_KEY = 91481015L;
    private final Connection connection;

    private InitialMigrationGuard(Connection connection) {
        this.connection = connection;
    }

    public static boolean requested(String value, String environment) {
        if (value == null || "false".equals(value)) return false;
        if (!"true".equals(value) || !"dev".equals(environment))
            throw new IllegalStateException("Initial migration requires explicit DEV mode");
        return true;
    }

    public static InitialMigrationGuard acquire(DataSource source, boolean requested) throws SQLException {
        if (!requested) return new InitialMigrationGuard(null);
        Connection connection = source.getConnection();
        try {
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setQueryTimeout(5);
                statement.setLong(1, LOCK_KEY);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1))
                        throw new IllegalStateException("Another initial migration is active");
                }
            }
            requireEmpty(connection, "public");
            return new InitialMigrationGuard(connection);
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    static void requireEmpty(Connection connection, String schema) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (SELECT FROM pg_namespace WHERE nspname=?)
                 AND NOT EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=?)
                 AND NOT EXISTS (SELECT FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=?)
                 AND NOT EXISTS (SELECT FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname=?)
                """)) {
            statement.setQueryTimeout(5);
            for (int index = 1; index <= 4; index++) statement.setString(index, schema);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1))
                    throw new IllegalStateException("Initial migration requires an empty application schema; preserve and review existing objects");
            }
        }
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) connection.close(); // Closing the session releases its advisory lock.
    }
}
