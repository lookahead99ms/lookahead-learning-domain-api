package com.lookahead.domain.review.platform;

import com.lookahead.domain.StartupChecks;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.domain.delivery.DeliveryPlanStore;
import com.lookahead.domain.publication.PublicationSettings;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-971 (owner, 2026-11-06): the runtime stores in the assets bucket, one object store per root: reviews/,
 * support-requests/ and delivery/. Local uses the named-volume directory with the same roots as sub-folders;
 * cloud uses explicitly configured private S3 storage with the publication's KMS key. Unconfigured storage answers
 * 503 instead of silently saving ephemeral data.
 */
@Configuration
@Profile("accounts")
public class PlatformReviewConfiguration {
    /** The settings of every store: the bucket and key shared by all, one prefix per root. */
    record StoreSettings(String directory, String bucket, String kmsKey, String reviews, String requests, String delivery) {}

    @Bean StoreSettings reviewStoreSettings(@Value("${app.platform-reviews.directory:}") String directory,
            @Value("${app.platform-reviews.bucket:}") String bucket, @Value("${app.platform-reviews.kms-key:}") String kmsKey,
            @Value("${app.reviews.prefix:reviews/}") String reviews, @Value("${app.support-requests.prefix:support-requests/}") String requests,
            @Value("${app.delivery.prefix:delivery/}") String delivery,
            PublicationSettings publication) {
        boolean configured = !bucket.isBlank() || !kmsKey.isBlank();
        if (publication.cloud() && !directory.isBlank()) throw new IllegalStateException("Cloud review storage must use S3");
        if (configured && (!publication.cloud() || !directory.isBlank())) throw new IllegalStateException("S3 reviews require explicit cloud configuration");
        if (configured) {
            for (var prefix : List.of(new String[] {"app.reviews.prefix (LOOKAHEAD_REVIEWS_PREFIX)", reviews},
                    new String[] {"app.support-requests.prefix (LOOKAHEAD_SUPPORT_REQUESTS_PREFIX)", requests},
                    new String[] {"app.delivery.prefix (LOOKAHEAD_DELIVERY_PREFIX)", delivery}))
                if (prefix[1].isBlank()) throw StartupChecks.refuse(prefix[0], "SETTING_MISSING", "is not set; every runtime store needs its prefix in the assets bucket",
                        "Runtime store prefixes are required with S3 review storage");
        }
        return new StoreSettings(directory, bucket, kmsKey, reviews, requests, delivery);
    }

    /** One object store for a root. */
    static PlatformReviewObjects objects(StoreSettings settings, PublicationSettings publication, ObjectProvider<S3Client> clients, String root) {
        if (!settings.bucket().isBlank())
            return new S3PlatformReviewObjects(clients.getObject(), settings.bucket(), root, publication.accountId(), settings.kmsKey());
        if (!settings.directory().isBlank())
            return new FileSystemPlatformReviewObjects(Path.of(settings.directory()).resolve(root.substring(0, root.length() - 1)));
        return new PlatformReviewObjects() {
            private UncheckedIOException missing() { return new UncheckedIOException(new java.io.IOException("Platform review storage is not configured")); }
            public Optional<Stored> read(String key) { throw missing(); }
            public boolean write(String key, byte[] bytes, String expectedVersion) { throw missing(); }
            public void put(String key, byte[] bytes) { throw missing(); }
            public List<String> list(String prefix) { throw missing(); }
            public List<String> listAll(String prefix) { throw missing(); }
            public void delete(String key) { throw missing(); }
        };
    }

    // DLV-970: the two record stores on DynamoDB (record numbers in user-account-details, status history in administrator-audit-events).
    @Bean ReviewRecordNumbers reviewRecordNumbers(DynamoDbClient client, DynamoTableNames tables) { return new ReviewRecordNumbers.Dynamo(client, tables); }
    @Bean ReviewStatusHistory reviewStatusHistory(DynamoDbClient client, DynamoTableNames tables) { return new ReviewStatusHistory.Dynamo(client, tables, java.time.Clock.systemUTC()); }

    @Bean PlatformReviewService.Stores reviewStores(StoreSettings settings, PublicationSettings publication, ObjectProvider<S3Client> clients,
            ObjectMapper mapper, ReviewRecordNumbers numbers) {
        return new PlatformReviewService.Stores(
                new RecordStore(objects(settings, publication, clients, settings.reviews()), PlatformReviewModels.KIND_REVIEW, mapper, numbers),
                new RecordStore(objects(settings, publication, clients, settings.requests()), PlatformReviewModels.KIND_REQUEST, mapper, numbers));
    }

    @Bean DeliveryPlanStore deliveryPlanStore(StoreSettings settings, PublicationSettings publication, ObjectProvider<S3Client> clients, ObjectMapper mapper) {
        return new DeliveryPlanStore(objects(settings, publication, clients, settings.delivery()), mapper);
    }

    /** The environment stamp on every ticket: {@code dev} when the cloud policy is on, else {@code local}. */
    @Bean DeliveryPlanService deliveryPlanService(DeliveryPlanStore store, com.lookahead.domain.access.StaffAccessService access,
            ReviewStatusHistory history, PublicationSettings publication) {
        return new DeliveryPlanService(store, access, history, publication.cloud() ? "dev" : "local", java.time.Clock.systemUTC());
    }

    /** DLV-998: the published plan seeded or merged at each start, on DEV and local only. */
    @Bean com.lookahead.domain.delivery.ReleasePlanImport releasePlanImport(DeliveryPlanStore store, DeliveryPlanService plans,
            com.lookahead.domain.publication.PublicationLocation location, com.lookahead.domain.publication.LiveRelease release,
            org.springframework.core.env.Environment environment, ObjectMapper mapper) {
        return new com.lookahead.domain.delivery.ReleasePlanImport(store, plans, location, release, com.lookahead.domain.DevOnly.allowed(environment), mapper, java.time.Clock.systemUTC());
    }
}
