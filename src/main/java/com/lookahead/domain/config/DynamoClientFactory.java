package com.lookahead.domain.config;

import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Builds the DynamoDB connection from the checked connection values: the one place it is built (DLV-1016). The
 * configuration's bean method returns its result, and the data migration entry point, which runs outside Spring, calls
 * it directly. Local: the configured endpoint with synthetic credentials; cloud: the default credentials chain.
 */
public final class DynamoClientFactory {
    /** A whole call, including the SDK's own retries, and one attempt. Readiness sets tighter bounds per request. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5), ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    private DynamoClientFactory() {}

    /** A synchronous client over the pinned URL-connection transport; building it opens no connection. */
    public static DynamoDbClient create(DynamoConnection connection) {
        var builder = DynamoDbClient.builder().region(connection.region()).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(CALL_TIMEOUT).apiCallAttemptTimeout(ATTEMPT_TIMEOUT).build());
        if (connection.local()) {
            builder.endpointOverride(connection.endpoint())
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}
