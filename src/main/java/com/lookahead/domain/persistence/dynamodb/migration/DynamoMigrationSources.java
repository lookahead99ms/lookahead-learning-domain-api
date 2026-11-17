package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.database.DomainDatabaseConnections;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DLV-970: what a data step may read besides the DynamoDB store. Today that is one optional PostgreSQL source, the
 * platform database the Domain served before the cutover, for the one-off account import of step 5. Built once by the
 * entrypoint from process variables: {@code LOOKAHEAD_IMPORT_JDBC_URL} (validated like the runtime's URL: in dev and
 * prod a regional RDS endpoint with verify-full TLS), {@code LOOKAHEAD_IMPORT_DB_USER} (the runtime's read-write role
 * by default; the import only reads) and {@code LOOKAHEAD_IMPORT_DB_PASSWORD} (an ECS secret). Without the URL there is
 * no source: a {@code local} store then has nothing to import, {@code dev} and {@code prod} refuse the step.
 */
public record DynamoMigrationSources(String environment, Optional<DataSource> postgres) {
    public static final String URL_VARIABLE = "LOOKAHEAD_IMPORT_JDBC_URL", USER_VARIABLE = "LOOKAHEAD_IMPORT_DB_USER", PASSWORD_VARIABLE = "LOOKAHEAD_IMPORT_DB_PASSWORD";
    public static final String DEFAULT_USER = "lookahead_platform_app";

    public DynamoMigrationSources {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(postgres, "postgres");
    }

    /** A local store with nothing to import (unit tests, the laptop stack without a PostgreSQL to read). */
    public static DynamoMigrationSources none() { return new DynamoMigrationSources("local", Optional.empty()); }

    /** A source already opened by the caller (tests). */
    public static DynamoMigrationSources of(String environment, DataSource postgres) { return new DynamoMigrationSources(environment, Optional.of(postgres)); }

    public static DynamoMigrationSources fromVariables(Map<String, String> variables, String environment, String region) {
        String url = variables.getOrDefault(URL_VARIABLE, "").trim();
        if (url.isEmpty()) return new DynamoMigrationSources(environment, Optional.empty());
        String user = variables.getOrDefault(USER_VARIABLE, "").trim();
        String password = variables.getOrDefault(PASSWORD_VARIABLE, "");
        if (password.isBlank()) throw new IllegalStateException("The PostgreSQL import source needs " + PASSWORD_VARIABLE);
        DomainDatabaseConnections.validateTarget(environment, region, url);
        return new DynamoMigrationSources(environment, Optional.of(DomainDatabaseConnections.boundedDataSource(url, user.isEmpty() ? DEFAULT_USER : user, password)));
    }

    public boolean local() { return "local".equals(environment); }

    /** The PostgreSQL source a step requires, or the refusal that names the missing variable (never a URL). */
    public DataSource requirePostgres(String purpose) {
        return postgres.orElseThrow(() -> new IllegalStateException(purpose + " needs the PostgreSQL source (" + URL_VARIABLE + ", " + USER_VARIABLE + ", " + PASSWORD_VARIABLE + ")"));
    }
}
