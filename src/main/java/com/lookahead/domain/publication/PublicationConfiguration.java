package com.lookahead.domain.publication;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("accounts")
public class PublicationConfiguration {
    @Bean PublicationSettings publicationSettings(Environment environment) { return PublicationSettings.from(environment); }

    @Configuration
    @ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
    static class Cloud {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor cloudRuntimeGuard(Environment environment) {
            return factory -> CloudRuntimeGuard.validate(environment);
        }
        @Bean(destroyMethod = "close") ContainerCredentialsProvider publicationCredentials() {
            return ContainerCredentialsProvider.builder().build();
        }
        @Bean(destroyMethod = "close") S3Client publicationS3(PublicationSettings settings, ContainerCredentialsProvider credentials) {
            return S3Client.builder().region(Region.of(settings.region())).credentialsProvider(credentials)
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(30)))
                    .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofMinutes(5)).apiCallAttemptTimeout(Duration.ofMinutes(2)))
                    .build();
        }
        @Bean PublicationObjectSource publicationObjects(S3Client client, PublicationSettings settings) {
            return key -> client.getObject(request -> request.bucket(settings.bucket()).key(key).expectedBucketOwner(settings.accountId()));
        }
    }
    @Bean PublicationLocation publicationLocation(PublicationSettings settings, Environment environment,
            org.springframework.beans.factory.ObjectProvider<PublicationObjectSource> source, ObjectMapper mapper) {
        if (settings.cloud()) return new PublicationBootstrap(mapper).load(settings, source.getObject());
        return new PublicationLocation(environment.getProperty("app.content.publication-path", ""),
                environment.getProperty("app.content.root", ""), environment.getProperty("app.accounts.catalog-path", ""), false, 0, 1);
    }
    @Bean("contentPublication") HealthIndicator contentPublication(PublicationLocation location,
            com.lookahead.learning.content.service.ProtectedContentPolicy policy,
            com.lookahead.learning.content.validator.SnapshotValidator catalog) {
        return () -> location.cloud() && (policy.version().equals("disabled")
                || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(location.manifest()))
                || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(location.accountCatalog()))
                || !java.nio.file.Files.isDirectory(java.nio.file.Path.of(location.contentRoot())))
                ? Health.down().build() : Health.up().build();
    }
}
