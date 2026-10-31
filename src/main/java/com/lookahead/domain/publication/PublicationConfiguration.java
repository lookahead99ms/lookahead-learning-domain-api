package com.lookahead.domain.publication;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
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
            return new S3PublicationObjects(client, settings);
        }
    }

    /** Owner-checked reads of the selected bucket; listing is bounded to the live prefix a deployment discovers. */
    static final class S3PublicationObjects implements PublicationObjectSource {
        private final S3Client client;
        private final PublicationSettings settings;
        S3PublicationObjects(S3Client client, PublicationSettings settings) { this.client = client; this.settings = settings; }
        @Override public InputStream open(String key) {
            return client.getObject(request -> request.bucket(settings.bucket()).key(key).expectedBucketOwner(settings.accountId()));
        }
        @Override public List<ListedObject> list(String prefix) {
            List<ListedObject> result = new ArrayList<>();
            Set<String> continuationTokens = new HashSet<>();
            String token = null;
            int pages = 0;
            do {
                if (++pages > PublicationBootstrap.MAX_LIVE_OBJECTS + 1)
                    throw new IllegalStateException("Cloud publication listing is incomplete or exceeds its request bound");
                String continuation = token;
                ListObjectsV2Response page = client.listObjectsV2(request -> request.bucket(settings.bucket()).prefix(prefix)
                        .expectedBucketOwner(settings.accountId()).maxKeys(PublicationBootstrap.MAX_LIVE_OBJECTS + 1)
                        .continuationToken(continuation));
                for (S3Object object : page.contents()) result.add(new ListedObject(object.key(), object.lastModified()));
                if (result.size() > PublicationBootstrap.MAX_LIVE_OBJECTS) throw new IllegalStateException("Live prefix holds more objects than releases");
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
                if (Boolean.TRUE.equals(page.isTruncated()) && (token == null || token.isBlank() || !continuationTokens.add(token)))
                    throw new IllegalStateException("Cloud publication listing is incomplete or exceeds its request bound");
            } while (token != null);
            return result;
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
