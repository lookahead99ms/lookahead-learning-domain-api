package com.lookahead.domain.config;

import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.db.store.review.PlatformReviewObjects;
import com.lookahead.domain.job.DeliveryPlanReleaseImportJob;
import com.lookahead.domain.model.PublicationLiveRelease;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationSelection;
import com.lookahead.domain.db.store.review.PlatformReviewStatusHistory;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.delivery.DeliveryPlanService;
import com.lookahead.domain.db.store.delivery.DeliveryPlanStore;
import com.lookahead.domain.validator.DeliveryPlanFileValidator;
import com.lookahead.domain.validator.DeliveryPlanItemValidator;
import com.lookahead.domain.validator.DeliveryPlanRequestValidator;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/** The delivery plan beans: its store in the assets bucket (or the Local directory), its service and the import of the released plan. */
@Configuration
@Profile("accounts")
public class DeliveryPlanConfiguration {
    @Bean DeliveryPlanStore deliveryPlanStore(@Qualifier("deliveryObjects") PlatformReviewObjects deliveryObjects, ObjectMapper mapper) {
        return new DeliveryPlanStore(deliveryObjects, mapper);
    }

    @Bean DeliveryPlanFileValidator deliveryPlanFileValidator(ObjectMapper mapper) {
        return new DeliveryPlanFileValidator(mapper);
    }

    /** The service gets the environment stamp of every ticket: {@code dev} when the cloud policy is on, else {@code local}. */
    @Bean DeliveryPlanService deliveryPlanService(DeliveryPlanStore store, DeliveryPlanFileValidator files,
            StaffAccessService access, PlatformReviewStatusHistory history, PublicationSelection publication,
            DeliveryPlanItemValidator itemValidator, Environment environment,
            DeliveryPlanRequestValidator shapes) {
        return new DeliveryPlanService(store, files, access, history, publication.ticketEnvironment(), Clock.systemUTC(), itemValidator, environment, shapes);
    }

    /** DLV-998: the published plan seeded or merged at each start, on DEV and local only. */
    @Bean DeliveryPlanReleaseImportJob deliveryPlanReleaseImportJob(DeliveryPlanStore store, DeliveryPlanFileValidator files, DeliveryPlanService plans,
            PublicationLocation location, PublicationLiveRelease release,
            Environment environment, ObjectMapper mapper, PublicationFileStore publicationFiles) {
        return new DeliveryPlanReleaseImportJob(store, publicationFiles, files, plans, location, release, environment, mapper, Clock.systemUTC());
    }
}
