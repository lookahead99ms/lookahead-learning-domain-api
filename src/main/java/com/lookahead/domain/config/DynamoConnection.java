package com.lookahead.domain.config;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.validator.CloudRuntimeValidator;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.regions.Region;

/**
 * DLV-970: how this process reaches DynamoDB. Local talks to a loopback DynamoDB Local container with synthetic
 * credentials and must name its endpoint; DEV and PROD talk to the Region's service with the task role's
 * credentials and must not name an endpoint. The four table names come from {@link DynamoTableNames}.
 * Nothing here creates a table.
 */
// Exception, decided 2026-11-25 (DLV-1016): this record reads and checks its own settings instead of leaving that to a
// validator class. The data migration entry point (DomainDynamoMigration, in the package persistence.dynamodb.migration,
// which is not changed) calls DynamoConnection.fromVariables, so that method cannot leave this class; the Spring path
// (from) shares its checks, so both entry points refuse with the same lines in the same order.
public record DynamoConnection(String environment, Region region, URI endpoint, DynamoTableNames tables) {
    public static final String ENDPOINT_SETTING = "app.dynamodb.endpoint (LOOKAHEAD_DYNAMODB_ENDPOINT)";
    public static final String REGION_SETTING = "AWS_REGION (app.dynamodb.region)";
    public static final String ENVIRONMENT_SETTING = "app.deployment-environment (LOOKAHEAD_ENVIRONMENT)";
    public DynamoConnection {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(tables, "tables");
        if ("local".equals(environment) == (endpoint == null)) throw new IllegalArgumentException("Local needs an endpoint; DEV and PROD must not have one");
    }

    public boolean local() { return "local".equals(environment); }

    public static DynamoConnection from(Environment environment) {
        return from(environment.getProperty("app.deployment-environment", ""),
                environment.getProperty("app.dynamodb.region", ""), environment.getProperty("app.dynamodb.endpoint", ""),
                DynamoTableNames.select(environment));
    }

    /** For the migration entrypoint, which has no Spring environment: the same rules from process variables. */
    public static DynamoConnection fromVariables(Map<String, String> variables) {
        String mode = variables.getOrDefault("LOOKAHEAD_ENVIRONMENT", "");
        requireEnvironment(mode);
        return from(mode, variables.getOrDefault("AWS_REGION", ""), variables.getOrDefault("LOOKAHEAD_DYNAMODB_ENDPOINT", ""),
                DynamoTableNames.fromVariables(mode, variables));
    }

    private static void requireEnvironment(String mode) {
        if (!CloudRuntimeValidator.ENVIRONMENTS.contains(mode))
            throw EventLog.startupRefuse(ENVIRONMENT_SETTING, "ENVIRONMENT_INVALID", "must be local, dev or prod",
                    "DynamoDB requires an explicit local, dev or prod environment");
    }

    static DynamoConnection from(String mode, String region, String endpoint, DynamoTableNames tables) {
        requireEnvironment(mode);
        boolean local = "local".equals(mode);
        URI target = null;
        if (local) {
            // DLV-970: the refusal names the rule broken, never the configured value.
            String problem = "must be http://127.0.0.1:<port> with a port of 1024 or more and nothing else";
            try {
                if (endpoint == null || endpoint.isBlank()) problem = "is required when the store is dynamodb and the environment is local";
                else target = URI.create(endpoint.trim());
                if (target == null || !"http".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost())
                        || target.getPort() < 1024 || target.getPort() > 65535 || target.getUserInfo() != null
                        || !target.getPath().isEmpty() || target.getQuery() != null || target.getFragment() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw EventLog.startupRefuse(ENDPOINT_SETTING, "DYNAMODB_ENDPOINT_INVALID", problem, "Local DynamoDB requires a loopback endpoint");
            }
            return new DynamoConnection(mode, Region.US_EAST_2, target, tables);
        }
        if (endpoint != null && !endpoint.isBlank())
            throw EventLog.startupRefuse(ENDPOINT_SETTING, "DYNAMODB_ENDPOINT_INVALID", "must be empty in dev and prod; the Region's service is used",
                    "Cloud DynamoDB does not accept an endpoint override");
        if (region == null || !region.matches(CloudRuntimeValidator.AWS_REGION))
            throw EventLog.startupRefuse(REGION_SETTING, "REGION_INVALID", "is not an AWS region name", "Cloud DynamoDB requires AWS_REGION");
        return new DynamoConnection(mode, Region.of(region), null, tables);
    }

    @Override public String toString() { return "DynamoConnection[" + environment + ", " + region + "]"; }
}
