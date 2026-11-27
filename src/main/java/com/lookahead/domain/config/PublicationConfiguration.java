package com.lookahead.domain.config;

import com.lookahead.domain.db.store.content.ProtectedContentFileStore;
import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationSelection;
import com.lookahead.domain.client.PublicationObjectClient;
import com.lookahead.domain.client.PublicationS3Client;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.service.content.PublicationLoadService;
import com.lookahead.domain.model.PublicationLiveRelease;
import com.lookahead.domain.health.PublicationHealthIndicator;
import com.lookahead.domain.service.content.PublicationLiveLoadService;
import com.lookahead.domain.validator.CloudRuntimeValidator;
import com.lookahead.domain.validator.PublicationSelectionValidator;
import com.lookahead.domain.validator.StudyPlanSnapshotValidator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
    @Bean PublicationSelection publicationSelection(Environment environment) { return PublicationSelectionValidator.validated(environment); }

    @Configuration
    @ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
    static class Cloud {
        @Bean static BeanFactoryPostProcessor cloudRuntimeValidator(Environment environment) {
            return factory -> CloudRuntimeValidator.validate(environment);
        }
        @Bean(destroyMethod = "close") ContainerCredentialsProvider publicationCredentials() {
            return ContainerCredentialsProvider.builder().build();
        }
        @Bean(destroyMethod = "close") S3Client publicationS3(PublicationSelection selection, ContainerCredentialsProvider credentials) {
            return S3Client.builder().region(Region.of(selection.region())).credentialsProvider(credentials)
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(30)))
                    .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofMinutes(5)).apiCallAttemptTimeout(Duration.ofMinutes(2)))
                    .build();
        }
        @Bean PublicationObjectClient publicationObjects(S3Client client, PublicationSelection selection) {
            return new PublicationS3Client(client, selection);
        }
    }

    /**
     * DLV-971: with {@code app.publication.live-prefix} set (DEV release/live/ after the AWS lane's cutover) the single
     * tar under it is served; empty keeps today's live/<tag>/ discovery or the pinned release.
     */
    @Bean public PublicationSourceConfigValues publicationSourceConfigValues(Environment environment) { return PublicationSourceConfigValues.from(environment); }
    /** DLV-1016: the loader is a bean and the bean method below only returns what it loaded (it used to read four settings and build the loader itself). */
    @Bean public PublicationLoadService publicationLoadService(ObjectMapper mapper, PublicationFileStore files) { return new PublicationLoadService(mapper, files); }
    @Bean public PublicationLiveLoadService.Loaded publicationLoaded(PublicationLoadService loader, PublicationSelection selection, PublicationSourceConfigValues values,
            ObjectProvider<PublicationObjectClient> source) {
        return loader.load(selection, values, source::getObject);
    }
    @Bean public PublicationLocation publicationLocation(PublicationLiveLoadService.Loaded loaded) { return loaded.location(); }
    @Bean public PublicationLiveRelease publicationLiveRelease(PublicationLiveLoadService.Loaded loaded) { return loaded.release(); }
    /** The release's content files: the folder and version come from the loaded allowlist (DLV-1016: the store no longer asks the policy itself). */
    @Bean public ProtectedContentFileStore protectedContentFileStore(ProtectedContentManifestService policy, PublicationLocation location) {
        return new ProtectedContentFileStore(policy.root(), policy.version(), location);
    }
    // Exception, decided 2026-11-25 (DLV-1016): the parameter `catalog` is not read. It stays because it makes the snapshot
    // validator, and with it the account catalog (which refuses start-up when it cannot be loaded), load before this
    // indicator, which keeps the order of the start-up refusals.
    @Bean("contentPublication") public HealthIndicator contentPublication(PublicationLocation location,
            ProtectedContentManifestService policy,
            StudyPlanSnapshotValidator catalog, PublicationLiveRelease release,
            PublicationFileStore files) {
        return new PublicationHealthIndicator(location, policy, release, files, Clock.systemUTC());
    }
}
