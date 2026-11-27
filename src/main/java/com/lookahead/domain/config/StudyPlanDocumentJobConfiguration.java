package com.lookahead.domain.config;

import com.lookahead.domain.job.MaintenanceJobRegistry;
import com.lookahead.domain.job.StudyPlanDocumentCleanupJob;
import com.lookahead.domain.job.StudyPlanDocumentRetirementJob;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.plan.StudyPlanDocumentCleanupStore;
import com.lookahead.domain.db.store.plan.StudyPlanDocumentRetirementStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the DynamoDB maintenance the sweeps run. Two jobs, each one bounded page per call:
 * {@code study-plan-documents} cleans staged study-plan documents whose upload deadline passed
 * ({@link StudyPlanDocumentCleanupStore#cleanDue}) and {@code retired-study-plan-documents} removes the chunks of
 * retired snapshots ({@link StudyPlanDocumentRetirementStore#cleanPage}). The maintenance classes are built here, not
 * exposed as beans, so a lane that needs its own instance (plan deletion retires through its own) is not tied to these.
 */
@Configuration
public class StudyPlanDocumentJobConfiguration {

    @Bean public MaintenanceJobRegistry.Job studyPlanDocumentCleanupJob(DynamoDbClient client, DynamoTableNames tables) {
        return new StudyPlanDocumentCleanupJob(new StudyPlanDocumentCleanupStore(client, tables, Clock.systemUTC()));
    }

    @Bean public MaintenanceJobRegistry.Job retiredStudyPlanDocumentJob(DynamoDbClient client, DynamoTableNames tables) {
        return new StudyPlanDocumentRetirementJob(new StudyPlanDocumentRetirementStore(client, tables));
    }
}
