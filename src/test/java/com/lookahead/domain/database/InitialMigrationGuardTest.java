package com.lookahead.domain.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockSettings;

class InitialMigrationGuardTest {
    private static final MockSettings INTERFACE = withSettings().mockMaker(org.mockito.MockMakers.PROXY);

    @Test void optInIsExplicitAndDevOnly() throws Exception {
        assertFalse(InitialMigrationGuard.requested(null, "local"));
        assertFalse(InitialMigrationGuard.requested("false", "dev"));
        assertTrue(InitialMigrationGuard.requested("true", "dev"));
        for (String environment : new String[]{"local", "prod", "DEV"})
            assertThrows(IllegalStateException.class, () -> InitialMigrationGuard.requested("true", environment));
        assertThrows(IllegalStateException.class, () -> InitialMigrationGuard.requested("yes", "dev"));
        DataSource source = mock(DataSource.class, INTERFACE);
        try (var guard = InitialMigrationGuard.acquire(source, false)) { verifyNoInteractions(source); }
    }

    @Test void lockAndEmptySchemaStayHeldUntilMigrationCompletes() throws Exception {
        DataSource source = mock(DataSource.class, INTERFACE);
        Connection connection = mock(Connection.class, INTERFACE);
        PreparedStatement statement = mock(PreparedStatement.class, INTERFACE);
        ResultSet result = mock(ResultSet.class, INTERFACE);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getBoolean(1)).thenReturn(true);
        try (var guard = InitialMigrationGuard.acquire(source, true)) {
            verify(connection, never()).close();
            verify(statement).setLong(1, 91481015L);
            for (int index = 1; index <= 4; index++) verify(statement).setString(index, "public");
        }
        verify(connection).close();
    }

    @Test void busyLockPopulatedSchemaAndSqlFailureCloseTheSession() throws Exception {
        for (String scenario : new String[]{"busy", "populated", "missing", "sql"}) {
            DataSource source = mock(DataSource.class, INTERFACE);
            Connection connection = mock(Connection.class, INTERFACE);
            PreparedStatement statement = mock(PreparedStatement.class, INTERFACE);
            ResultSet result = mock(ResultSet.class, INTERFACE);
            when(source.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(result);
            when(result.next()).thenReturn(!scenario.equals("missing"));
            when(result.getBoolean(1)).thenReturn(!scenario.equals("busy"), !scenario.equals("populated"));
            if (scenario.equals("sql")) when(statement.executeQuery()).thenThrow(new SQLException("synthetic"));
            assertThrows(Exception.class, () -> InitialMigrationGuard.acquire(source, true), scenario);
            verify(connection).close();
        }
    }
}
