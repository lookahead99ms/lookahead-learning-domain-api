package com.lookahead.domain.persistence.dynamodb;

import com.lookahead.domain.StartupChecks;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: how this process reaches DynamoDB. Local talks to a loopback DynamoDB Local container with synthetic
 * credentials and must name its endpoint; DEV and PROD talk to the Region's service with the task role's
 * credentials and must not name an endpoint. The four table names come from {@link DynamoTableNames}.
 * Nothing here creates a table.
 */
public record DynamoSettings(String environment, Region region, URI endpoint, DynamoTableNames tables) {
    public static final String ENDPOINT_SETTING = "app.dynamodb.endpoint (LOOKAHEAD_DYNAMODB_ENDPOINT)";
    public static final String REGION_SETTING = "AWS_REGION (app.dynamodb.region)";
    public static final String ENVIRONMENT_SETTING = "app.deployment-environment (LOOKAHEAD_ENVIRONMENT)";
    /** A whole call, including the SDK's own retries, and one attempt. Readiness sets tighter bounds per request. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5), ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    public DynamoSettings {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(tables, "tables");
        if ("local".equals(environment) == (endpoint == null)) throw new IllegalArgumentException("Local needs an endpoint; DEV and PROD must not have one");
    }

    public boolean local() { return "local".equals(environment); }

    public static DynamoSettings from(Environment environment) {
        return from(environment.getProperty("app.deployment-environment", ""),
                environment.getProperty("app.dynamodb.region", ""), environment.getProperty("app.dynamodb.endpoint", ""),
                DynamoTableNames.select(environment));
    }

    /** For the migration entrypoint, which has no Spring environment: the same rules from process variables. */
    public static DynamoSettings fromVariables(Map<String, String> variables) {
        String mode = variables.getOrDefault("LOOKAHEAD_ENVIRONMENT", "");
        if (!Set.of("local", "dev", "prod").contains(mode))
            throw StartupChecks.refuse(ENVIRONMENT_SETTING, "ENVIRONMENT_INVALID", "must be local, dev or prod",
                    "DynamoDB requires an explicit local, dev or prod environment");
        return from(mode, variables.getOrDefault("AWS_REGION", ""), variables.getOrDefault("LOOKAHEAD_DYNAMODB_ENDPOINT", ""),
                DynamoTableNames.fromVariables(mode, variables));
    }

    static DynamoSettings from(String mode, String region, String endpoint, DynamoTableNames tables) {
        if (!Set.of("local", "dev", "prod").contains(mode))
            throw StartupChecks.refuse(ENVIRONMENT_SETTING, "ENVIRONMENT_INVALID", "must be local, dev or prod",
                    "DynamoDB requires an explicit local, dev or prod environment");
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
                throw StartupChecks.refuse(ENDPOINT_SETTING, "DYNAMODB_ENDPOINT_INVALID", problem, "Local DynamoDB requires a loopback endpoint");
            }
            return new DynamoSettings(mode, Region.US_EAST_2, target, tables);
        }
        if (endpoint != null && !endpoint.isBlank())
            throw StartupChecks.refuse(ENDPOINT_SETTING, "DYNAMODB_ENDPOINT_INVALID", "must be empty in dev and prod; the Region's service is used",
                    "Cloud DynamoDB does not accept an endpoint override");
        if (region == null || !region.matches("[a-z]{2}-[a-z]+-\\d"))
            throw StartupChecks.refuse(REGION_SETTING, "REGION_INVALID", "is not an AWS region name", "Cloud DynamoDB requires AWS_REGION");
        return new DynamoSettings(mode, Region.of(region), null, tables);
    }

    /** A synchronous client over the pinned URL-connection transport; building it opens no connection. */
    public DynamoDbClient client() {
        var builder = DynamoDbClient.builder().region(region).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(CALL_TIMEOUT).apiCallAttemptTimeout(ATTEMPT_TIMEOUT).build());
        if (local()) {
            builder.endpointOverride(endpoint)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }

    @Override public String toString() { return "DynamoSettings[" + environment + ", " + region + "]"; }
}
