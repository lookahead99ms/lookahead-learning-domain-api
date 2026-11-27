package com.lookahead.domain.config;

import com.lookahead.domain.db.store.review.PlatformReviewRecordNumberStore;
import com.lookahead.domain.db.store.review.PlatformReviewStatusHistoryStore;
import com.lookahead.domain.model.PublicationSelection;
import com.lookahead.domain.model.PlatformReviewModels;
import com.lookahead.domain.service.review.PlatformReviewService;
import com.lookahead.domain.client.PlatformReviewS3Client;
import com.lookahead.domain.db.store.review.PlatformReviewFileSystemObjectStore;
import com.lookahead.domain.db.store.review.PlatformReviewMissingObjectStore;
import com.lookahead.domain.db.store.review.PlatformReviewObjects;
import com.lookahead.domain.db.store.review.PlatformReviewS3ObjectStore;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.lookahead.domain.db.store.review.PlatformReviewRecordStore;
import com.lookahead.domain.db.store.review.PlatformReviewRecordNumber;
import com.lookahead.domain.db.store.review.PlatformReviewStatusHistory;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.validator.PlatformReviewStoreConfigValidator;
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
    @Bean PlatformReviewStoreConfigValues reviewStoreConfigValues(@Value("${app.platform-reviews.directory:}") String directory,
            @Value("${app.platform-reviews.bucket:}") String bucket, @Value("${app.platform-reviews.kms-key:}") String kmsKey,
            @Value("${app.reviews.prefix:reviews/}") String reviews, @Value("${app.support-requests.prefix:support-requests/}") String requests,
            @Value("${app.delivery.prefix:delivery/}") String delivery,
            PublicationSelection publication) {
        return PlatformReviewStoreConfigValidator.validated(directory, bucket, kmsKey, reviews, requests, delivery, publication);
    }

    // DLV-970: the two record stores on DynamoDB (record numbers in user-account-details, status history in administrator-audit-events).
    @Bean PlatformReviewRecordNumber platformReviewRecordNumberStore(DynamoDbClient client, DynamoTableNames tables) { return new PlatformReviewRecordNumberStore(client, tables); }
    @Bean PlatformReviewStatusHistory platformReviewStatusHistoryStore(DynamoDbClient client, DynamoTableNames tables) { return new PlatformReviewStatusHistoryStore(client, tables, Clock.systemUTC()); }

    /**
     * DLV-1016: the object store of each runtime root is a bean, and which kind an environment gets is decided by the
     * condition on one of the three nested configurations below (it was a factory in the store package called three times).
     */
    static final String S3_STORES = "!'${app.platform-reviews.bucket:}'.isBlank()";
    static final String DIRECTORY_STORES = "'${app.platform-reviews.bucket:}'.isBlank() && !'${app.platform-reviews.directory:}'.isBlank()";
    static final String NO_STORES = "'${app.platform-reviews.bucket:}'.isBlank() && '${app.platform-reviews.directory:}'.isBlank()";

    /** Cloud: one prefix of the assets bucket per root; every store goes through the one S3 client of that bucket, on the publication's S3 connection. */
    @Configuration @Profile("accounts") @ConditionalOnExpression(S3_STORES)
    public static class S3 {
        @Bean PlatformReviewS3Client platformReviewS3Client(PlatformReviewStoreConfigValues values, PublicationSelection publication, S3Client s3) {
            return new PlatformReviewS3Client(s3, values.bucket(), publication.accountId(), values.kmsKey());
        }
        @Bean PlatformReviewObjects reviewObjects(PlatformReviewS3Client client, PlatformReviewStoreConfigValues values) { return new PlatformReviewS3ObjectStore(client, values.reviews()); }
        @Bean PlatformReviewObjects requestObjects(PlatformReviewS3Client client, PlatformReviewStoreConfigValues values) { return new PlatformReviewS3ObjectStore(client, values.requests()); }
        @Bean PlatformReviewObjects deliveryObjects(PlatformReviewS3Client client, PlatformReviewStoreConfigValues values) { return new PlatformReviewS3ObjectStore(client, values.delivery()); }
    }

    /** Local: one sub-folder of the review directory per root. */
    @Configuration @Profile("accounts") @ConditionalOnExpression(DIRECTORY_STORES)
    public static class Directory {
        @Bean PlatformReviewObjects reviewObjects(PlatformReviewStoreConfigValues values) { return PlatformReviewFileSystemObjectStore.under(values.directory(), values.reviews()); }
        @Bean PlatformReviewObjects requestObjects(PlatformReviewStoreConfigValues values) { return PlatformReviewFileSystemObjectStore.under(values.directory(), values.requests()); }
        @Bean PlatformReviewObjects deliveryObjects(PlatformReviewStoreConfigValues values) { return PlatformReviewFileSystemObjectStore.under(values.directory(), values.delivery()); }
    }

    /** Nothing configured: every store answers 503. The values are still read first, so a wrong setting is refused as before. */
    @Configuration @Profile("accounts") @ConditionalOnExpression(NO_STORES)
    public static class Missing {
        @Bean PlatformReviewObjects reviewObjects(PlatformReviewStoreConfigValues values) { return new PlatformReviewMissingObjectStore(); }
        @Bean PlatformReviewObjects requestObjects(PlatformReviewStoreConfigValues values) { return new PlatformReviewMissingObjectStore(); }
        @Bean PlatformReviewObjects deliveryObjects(PlatformReviewStoreConfigValues values) { return new PlatformReviewMissingObjectStore(); }
    }

    @Bean PlatformReviewService.Stores reviewStores(@Qualifier("reviewObjects") PlatformReviewObjects reviewObjects, @Qualifier("requestObjects") PlatformReviewObjects requestObjects,
            ObjectMapper mapper, PlatformReviewRecordNumber recordNumbers) {
        return new PlatformReviewService.Stores(
                new PlatformReviewRecordStore(reviewObjects, PlatformReviewModels.KIND_REVIEW, mapper, recordNumbers),
                new PlatformReviewRecordStore(requestObjects, PlatformReviewModels.KIND_REQUEST, mapper, recordNumbers));
    }
}
