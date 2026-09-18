package com.lookahead.learning.content.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeDatabaseConfigurationTest {
    private static final String BASE = "jdbc:postgresql://localhost:5432/platform_test";
    private HikariDataSource configured(String url) {
        return new AccountDatabaseConfiguration().accountDataSource(url, "lookahead_platform_app",
                "synthetic-password", 2, 1000, 500);
    }

    @Test void acceptsExplicitSingleHostUrlsAndOnlyBoundedTlsOptions() {
        for (String url : List.of(BASE, "jdbc:postgresql://platform-db/platform_test",
                "jdbc:postgresql://[::1]:5432/platform_test", BASE + "?sslmode=verify-full&sslrootcert=%2Frun%2Fsecrets%2Fca.crt")) {
            try (var source = configured(url)) {
                assertThat(source.getJdbcUrl()).isEqualTo(url);
                assertThat(source.getDataSourceProperties()).containsEntry("connectTimeout", "3")
                        .containsEntry("socketTimeout", "5").containsEntry("cancelSignalTimeout", "2");
                assertThat(source.getConnectionInitSql()).isNotBlank();
            }
        }
    }

    @Test void rejectsDriverParametersThatOverrideCredentialsRoleSchemaOrTimeouts() {
        for (String query : List.of("user=lookahead_platform_migrator", "password=sensitive-value",
                "us%65r=postgres", "options=-c%20role%3Dpostgres", "currentSchema=other",
                "connectTimeout=0", "socketTimeout=0", "cancelSignalTimeout=0", "loginTimeout=0",
                "service=another-service", "sslmode=verify-full&sslmode=disable", "sslmode=unsafe", "ssl%6dode=verify-full",
                "sslrootcert=relative.crt", "sslrootcert=%2Fbad%0Apath", "", "sslmode=verify-full&")) {
            assertThatThrownBy(() -> configured(BASE + "?" + query)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("sensitive-value").hasNoCause();
        }
    }

    @Test void rejectsAmbiguousHostsAndImplicitOrNonPostgresqlDestinations() {
        for (String url : List.of("jdbc:postgresql:platform_test", "jdbc:h2:mem:test", "jdbc:postgresql://localhost/",
                "jdbc:postgresql://localhost:0/platform_test", "jdbc:postgresql://localhost:65536/platform_test",
                "jdbc:postgresql://localhost,other/platform_test", "jdbc:postgresql://postgres:secret@localhost/platform_test",
                BASE + "#user=postgres", "jdbc:postgresql://localhost/platform%2Fother")) {
            assertThatThrownBy(() -> configured(url)).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
    }

    @Test void everyNewPhysicalConnectionMustPassTheRuntimeGuardBeforeItCanBeBorrowed() throws Exception {
        var upstream = mock(DataSource.class);
        var first = physicalConnection(); var second = physicalConnection();
        when(upstream.getConnection(anyString(), anyString())).thenReturn(first, second);
        try (var source = configured(BASE)) {
            source.setDataSource(upstream); source.setMinimumIdle(1);
            try (var one = source.getConnection(); var two = source.getConnection()) {
                verify(first.createStatement()).execute(source.getConnectionInitSql());
                verify(second.createStatement()).execute(source.getConnectionInitSql());
                assertThat(one).isNotNull(); assertThat(two).isNotNull();
            }
        }
    }

    @Test void aRejectedActualDatabaseIdentityNeverEntersTheConnectionPool() throws Exception {
        var upstream = mock(DataSource.class); var connection = physicalConnection();
        when(upstream.getConnection(anyString(), anyString())).thenReturn(connection);
        when(connection.createStatement().execute(anyString())).thenThrow(
                new SQLException("Runtime database role violates application isolation", "42501"));
        try (var source = configured(BASE)) {
            source.setDataSource(upstream);
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class)
                    .hasMessage("Runtime database role violates application isolation");
            verify(connection).close();
        }
    }

    private static Connection physicalConnection() throws Exception {
        var connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.isValid(anyInt())).thenReturn(true);
        return connection;
    }
}
