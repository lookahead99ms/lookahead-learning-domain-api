package com.lookahead.domain.persistence.dynamodb.migration;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The optional PostgreSQL source of a data step, built from process variables and refused without its password or with a bad target. */
class DynamoMigrationSourcesTest {
    static final String URL = "jdbc:postgresql://127.0.0.1:4393/lookahead_domain_cloud_auth";

    @Test void withoutAUrlThereIsNoSourceAndOnlyALocalStoreMayGoOn() {
        var none = DynamoMigrationSources.fromVariables(Map.of(), "dev", "us-east-2");
        assertThat(none.postgres()).isEmpty();
        assertThat(none.local()).isFalse();
        assertThatThrownBy(() -> none.requirePostgres("Data step 5")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOOKAHEAD_IMPORT_JDBC_URL").hasMessageContaining("LOOKAHEAD_IMPORT_DB_PASSWORD");
        assertThat(DynamoMigrationSources.none().local()).isTrue();
        assertThat(DynamoMigrationSources.fromVariables(Map.of("LOOKAHEAD_IMPORT_JDBC_URL", "  "), "local", null).postgres()).isEmpty();
    }

    @Test void aUrlNeedsItsPasswordAndALocalLoopbackSourceOpensWithTheDefaultUser() {
        assertThatThrownBy(() -> DynamoMigrationSources.fromVariables(Map.of("LOOKAHEAD_IMPORT_JDBC_URL", URL), "local", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LOOKAHEAD_IMPORT_DB_PASSWORD").hasMessageNotContaining("127.0.0.1");
        var sources = DynamoMigrationSources.fromVariables(Map.of("LOOKAHEAD_IMPORT_JDBC_URL", URL, "LOOKAHEAD_IMPORT_DB_PASSWORD", "synthetic"), "local", null);
        assertThat(sources.postgres()).isPresent();
        assertThat(sources.postgres().get()).isInstanceOfSatisfying(org.springframework.jdbc.datasource.DriverManagerDataSource.class, source -> {
            assertThat(source.getUsername()).isEqualTo("lookahead_platform_app");
            assertThat(source.getUrl()).isEqualTo(URL);
            assertThat(source.getConnectionProperties()).containsEntry("connectTimeout", "3");
        });
        var named = DynamoMigrationSources.fromVariables(Map.of("LOOKAHEAD_IMPORT_JDBC_URL", URL, "LOOKAHEAD_IMPORT_DB_USER", "reader", "LOOKAHEAD_IMPORT_DB_PASSWORD", "synthetic"), "local", null);
        assertThat(((org.springframework.jdbc.datasource.DriverManagerDataSource) named.postgres().get()).getUsername()).isEqualTo("reader");
    }

    @Test void devAndProdRefuseAnythingButARegionalRdsTargetWithVerifiedTls() {
        // The runtime's own rule (DomainDatabaseConnections.validateTarget): a loopback URL is not an RDS endpoint in the region.
        assertThatThrownBy(() -> DynamoMigrationSources.fromVariables(Map.of("LOOKAHEAD_IMPORT_JDBC_URL", URL, "LOOKAHEAD_IMPORT_DB_PASSWORD", "synthetic"), "dev", "us-east-2"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("127.0.0.1");
        assertThatThrownBy(() -> new DynamoMigrationSources(null, Optional.empty())).isInstanceOf(NullPointerException.class);
    }
}
