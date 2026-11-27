package com.lookahead.domain.config;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;

/**
 * DLV-1016: how the two Cognito connections and the SES connection are built (each had its own copy): the regional
 * endpoint written out, the URL-connection transport with a 2 s connect and 3 s read limit, a 5 s call and 3 s attempt
 * limit, and no SDK retry. Building it opens no connection. Two connections are not built here because their limits
 * differ: DynamoDB ({@link DynamoClientFactory}) and the publication's S3 connection ({@code PublicationConfiguration}).
 */
public final class AwsConnectionFactory {
    private AwsConnectionFactory() {}

    /** {@code service} is the endpoint's first label, for example {@code cognito-idp} or {@code email}. */
    public static <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> C regional(B builder, String service, String region, AwsCredentialsProvider credentials) {
        return builder.region(Region.of(region))
                .credentialsProvider(credentials)
                .endpointOverride(URI.create("https://" + service + "." + region + ".amazonaws.com"))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(3)).retryPolicy(RetryPolicy.none()))
                .build();
    }
}
