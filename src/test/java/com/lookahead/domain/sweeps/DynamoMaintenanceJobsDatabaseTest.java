package com.lookahead.domain.sweeps;

import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.delivery.DeliveryPlanService;
import com.lookahead.domain.persistence.dynamodb.*;
import com.lookahead.domain.review.DynamoAuthorReviewDatabaseTestSupport;
import com.lookahead.domain.review.platform.PlatformReviewService;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-970: the maintenance sweep on DynamoDB Local cleans an abandoned upload and a retired snapshot through the registered jobs. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoMaintenanceJobsDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNames.local();
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("Loopback fixture required");
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    @BeforeEach void prepareTables() {
        for (String name : TABLES.all()) DynamoAuthorReviewDatabaseTestSupport.createTable(client, name);
        DynamoAuthorReviewDatabaseTestSupport.purge(client, TABLES.userAccountDetailsTableName(), "WORK#");
    }
    @AfterAll static void close() { if (client != null) client.close(); }

    long jobs(String partition) {
        return client.query(QueryRequest.builder().tableName(TABLES.userAccountDetailsTableName()).keyConditionExpression("pk=:pk")
                .expressionAttributeValues(Map.of(":pk", DynamoItems.s(partition))).consistentRead(true).build()).count();
    }

    @Test void theSweepRunsBothDocumentJobsAndReportsWhatTheyCleaned() {
        var earlier = Clock.fixed(NOW.minusSeconds(120), ZoneOffset.UTC);
        var uploading = new StudyPlanDocumentStore(client, TABLES, earlier);
        // An upload whose deadline passed, and a published snapshot that was retired.
        uploading.stage(new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(), UUID.randomUUID()), new byte[] {1, 2, 3}, NOW.minusSeconds(1));
        var retiredId = new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(), UUID.randomUUID());
        var stage = uploading.stage(retiredId, new byte[] {4, 5, 6}, NOW.plusSeconds(3600));
        uploading.publish(stage, "plan", UUID.randomUUID(), 0, new AuditedAccountStore.AuditEvent("account:synthetic", "plan.saved", "Fixture", NOW));
        new StudyPlanDocumentRetirement(client, TABLES).retire(stage, "plan", UUID.randomUUID(), 1, new AuditedAccountStore.AuditEvent("account:synthetic", "plan.deleted", "Fixture", NOW));
        assertThat(jobs("WORK#DOCUMENT#00")).isEqualTo(1);
        assertThat(jobs("WORK#RETIRED-DOCUMENT#00")).isEqualTo(1);
        var registry = new RegisteredMaintenanceJobs();
        var configuration = new DynamoMaintenanceJobs();
        registry.register(new DynamoMaintenanceJobs.DocumentJob(new StudyPlanDocumentMaintenance(client, TABLES, Clock.fixed(NOW, ZoneOffset.UTC))));
        registry.register(configuration.retiredStudyPlanDocumentJob(client, TABLES));
        @SuppressWarnings("unchecked") ObjectProvider<CloudAdmissionCleanup> admissions = mock(ObjectProvider.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        var plans = mock(DeliveryPlanService.class);
        when(plans.syncInbox()).thenReturn(new DeliveryPlanService.Sync(false, null, 1, "no inbox file"));
        var sweeps = new RuntimeSweeps(mock(PlatformReviewService.class), plans, null, admissions, registry, Clock.fixed(NOW, ZoneOffset.UTC));
        var result = sweeps.sweep(RuntimeSweeps.Job.MAINTENANCE);
        assertThat(result.maintenance()).containsExactly(
                new RuntimeSweeps.Maintenance("study-plan-documents", 1, 1, 0, 0, false, null),
                new RuntimeSweeps.Maintenance("retired-study-plan-documents", 1, 1, 0, 0, false, null));
        assertThat(jobs("WORK#DOCUMENT#00")).isZero();
        assertThat(jobs("WORK#RETIRED-DOCUMENT#00")).isZero();
        assertThat(client.getItem(GetItemRequest.builder().tableName(TABLES.userStudyPlanDetailsTableName()).consistentRead(true)
                .key(DynamoItems.key("DOC#" + retiredId.ownerId() + "#" + retiredId.documentId(), "CHUNK#00000")).build()).item()).as("retired chunks are gone").isEmpty();
        // Nothing left: the next sweep reports empty pages, and the production job built by the configuration does the same.
        var again = sweeps.sweep(RuntimeSweeps.Job.ALL);
        assertThat(again.maintenance()).extracting(RuntimeSweeps.Maintenance::cleaned).containsExactly(0, 0);
        assertThat(configuration.studyPlanDocumentMaintenanceJob(client, TABLES).run(false)).isEqualTo(MaintenanceJobs.Page.NOTHING);
    }
}
