package com.lookahead.domain.database;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="DLV921_DATABASE_URL", matches=".+")
class InitialMigrationDatabaseTest {
    @Test void realPostgresRejectsTablesFunctionsAndTypesEvenWithoutRows() throws Exception {
        String url = System.getenv("DLV921_DATABASE_URL");
        assertEquals("jdbc:postgresql://127.0.0.1:4392/lookahead_domain_review", url);
        String password = Files.readString(Path.of(System.getenv("DLV921_DATABASE_PASSWORD_FILE"))).strip();
        try (var connection = DriverManager.getConnection(url, "postgres", password);
             var statement = connection.createStatement()) {
            String schema = "initial_guard_" + UUID.randomUUID().toString().replace("-", "");
            statement.execute("CREATE SCHEMA " + schema);
            try {
                InitialMigrationGuard.requireEmpty(connection, schema);
                for (String ddl : new String[]{"CREATE TABLE " + schema + ".marker(id integer)",
                        "CREATE FUNCTION " + schema + ".marker() RETURNS integer LANGUAGE SQL AS 'SELECT 1'",
                        "CREATE TYPE " + schema + ".marker AS ENUM ('synthetic')"}) {
                    statement.execute(ddl);
                    assertThrows(IllegalStateException.class, () -> InitialMigrationGuard.requireEmpty(connection, schema));
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
                    statement.execute("CREATE SCHEMA " + schema);
                }
                InitialMigrationGuard.requireEmpty(connection, schema);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
            assertThrows(IllegalStateException.class, () -> InitialMigrationGuard.requireEmpty(connection, schema));
        }
    }
}
