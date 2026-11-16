package com.lookahead.domain.sweeps;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.StudyPlanDocumentMaintenance;
import com.lookahead.domain.persistence.dynamodb.StudyPlanDocumentRetirement;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: the DynamoDB maintenance the sweeps run (dynamodb mode only). Two jobs, each one bounded page per call:
 * {@code study-plan-documents} cleans staged study-plan documents whose upload deadline passed
 * ({@link StudyPlanDocumentMaintenance#cleanDue}) and {@code retired-study-plan-documents} removes the chunks of
 * retired snapshots ({@link StudyPlanDocumentRetirement#cleanPage}). The maintenance classes are built here, not
 * exposed as beans, so a lane that needs its own instance (plan deletion retires through its own) is not tied to these.
 */
@Configuration
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoMaintenanceJobs {
    static final String DOCUMENTS = "study-plan-documents", RETIRED = "retired-study-plan-documents";

    @Bean MaintenanceJobs.Job studyPlanDocumentMaintenanceJob(DynamoDbClient client, DynamoTableNames tables) {
        return new DocumentJob(new StudyPlanDocumentMaintenance(client, tables, Clock.systemUTC()));
    }

    @Bean MaintenanceJobs.Job retiredStudyPlanDocumentJob(DynamoDbClient client, DynamoTableNames tables) {
        return new RetiredJob(new StudyPlanDocumentRetirement(client, tables));
    }

    /** Abandoned uploads: the cursor keeps the first page's cutoff for the whole sweep, as the maintenance requires. */
    static final class DocumentJob implements MaintenanceJobs.Job {
        private final StudyPlanDocumentMaintenance maintenance;
        private StudyPlanDocumentMaintenance.Cursor cursor;
        DocumentJob(StudyPlanDocumentMaintenance maintenance) { this.maintenance = Objects.requireNonNull(maintenance, "maintenance"); }
        @Override public String name() { return DOCUMENTS; }
        @Override public synchronized MaintenanceJobs.Page run(boolean afterFirstPage) {
            if (!afterFirstPage) cursor = null;
            var batch = maintenance.cleanDue(StudyPlanDocumentMaintenance.MAX_JOBS, cursor);
            cursor = batch.next();
            int cleaned = 0, retained = 0, failed = 0;
            for (var job : batch.jobs()) {
                switch (job.outcome()) { case CLEANED -> cleaned++; case RETAINED -> retained++; case FAILED -> failed++; }
            }
            return new MaintenanceJobs.Page(cleaned, retained, failed, cursor != null);
        }
    }

    /** Retired snapshots: a plain key continuation within one sweep. */
    static final class RetiredJob implements MaintenanceJobs.Job {
        private final StudyPlanDocumentRetirement retirement;
        private String afterKey;
        RetiredJob(StudyPlanDocumentRetirement retirement) { this.retirement = Objects.requireNonNull(retirement, "retirement"); }
        @Override public String name() { return RETIRED; }
        @Override public synchronized MaintenanceJobs.Page run(boolean afterFirstPage) {
            if (!afterFirstPage) afterKey = null;
            var page = retirement.cleanPage(StudyPlanDocumentRetirement.MAX_JOBS, afterKey);
            afterKey = page.afterKey();
            int cleaned = 0, retained = 0, failed = 0;
            for (var job : page.jobs()) {
                switch (job.outcome()) { case CLEANED -> cleaned++; case RETAINED -> retained++; case FAILED -> failed++; }
            }
            return new MaintenanceJobs.Page(cleaned, retained, failed, afterKey != null);
        }
    }
}
