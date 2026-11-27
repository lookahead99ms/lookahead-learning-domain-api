package com.lookahead.domain.validator;

import com.lookahead.domain.config.PlatformReviewStoreConfigValues;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.PublicationSelection;
import java.util.List;

/**
 * Startup check of the runtime store settings (reviews, support requests, delivery). DLV-1016: these checks were
 * {@code PlatformReviewStoreConfigValues.from} and moved here word for word, in the same order.
 */
public final class PlatformReviewStoreConfigValidator {
    private PlatformReviewStoreConfigValidator() {}

    /** The checked values: cloud storage must be S3, S3 needs explicit cloud configuration, and S3 needs every prefix. */
    public static PlatformReviewStoreConfigValues validated(String directory, String bucket, String kmsKey, String reviews, String requests, String delivery, PublicationSelection publication) {
        boolean configured = !bucket.isBlank() || !kmsKey.isBlank();
        if (publication.cloud() && !directory.isBlank()) throw new IllegalStateException("Cloud review storage must use S3");
        if (configured && (!publication.cloud() || !directory.isBlank())) throw new IllegalStateException("S3 reviews require explicit cloud configuration");
        if (configured) {
            for (var prefix : List.of(new String[] {"app.reviews.prefix (LOOKAHEAD_REVIEWS_PREFIX)", reviews},
                    new String[] {"app.support-requests.prefix (LOOKAHEAD_SUPPORT_REQUESTS_PREFIX)", requests},
                    new String[] {"app.delivery.prefix (LOOKAHEAD_DELIVERY_PREFIX)", delivery}))
                if (prefix[1].isBlank()) throw EventLog.startupRefuse(prefix[0], "SETTING_MISSING", "is not set; every runtime store needs its prefix in the assets bucket",
                        "Runtime store prefixes are required with S3 review storage");
        }
        return new PlatformReviewStoreConfigValues(directory, bucket, kmsKey, reviews, requests, delivery);
    }
}
