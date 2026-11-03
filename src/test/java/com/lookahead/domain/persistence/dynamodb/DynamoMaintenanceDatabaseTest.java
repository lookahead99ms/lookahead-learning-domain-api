package com.lookahead.domain.persistence.dynamodb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;

/** Separate synthetic Local tables; never resolves default credentials or cloud endpoints. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoMaintenanceDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("prod");
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StudyPlanDocumentMaintenance maintenance = new StudyPlanDocumentMaintenance(client,TABLES,clock);
    private final StudyPlanDocumentStore expired = new StudyPlanDocumentStore(client,TABLES,clock);
    private final StudyPlanDocumentStore uploading = new StudyPlanDocumentStore(client,TABLES,Clock.fixed(NOW.minusSeconds(60),ZoneOffset.UTC));

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1024 || endpoint.getPort() > 65535 || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null || !endpoint.getPath().isEmpty()) {
            throw new IllegalArgumentException("Only an explicit loopback fixture endpoint is allowed");
        }
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic","synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    private String[] tables() { return new String[]{TABLES.userAccountDetailsTableName(),TABLES.userStudyPlanDetailsTableName(),TABLES.administratorAuditEventsTableName()}; }
    @BeforeEach void createTables() {
        for (String table : tables()) client.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
    }
    @AfterEach void deleteOwnedFixtureTables() { for (String table : tables()) client.deleteTable(DeleteTableRequest.builder().tableName(table).build()); }
    @AfterAll static void close() { if(client!=null)client.close(); }
    private StudyPlanDocumentStore.Stage stage(Instant deadline, byte[] bytes) {
        return uploading.stage(new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(),UUID.randomUUID()),bytes,deadline);
    }
    private long jobs() {
        return client.query(QueryRequest.builder().tableName(TABLES.userAccountDetailsTableName()).keyConditionExpression("pk=:pk")
                .expressionAttributeValues(Map.of(":pk",s("WORK#DOCUMENT#00"))).consistentRead(true).build()).count();
    }
    private Map<String,AttributeValue> manifest(StudyPlanDocumentStore.Stage stage) {
        return client.getItem(GetItemRequest.builder().tableName(TABLES.userStudyPlanDetailsTableName()).key(Map.of("pk",s(stage.id().pk()),"sk",s("MANIFEST")))
                .consistentRead(true).build()).item();
    }

    @Test void pagesResumeAfterDeletedKeysAndDoNotProcessNewlyDueJobsUntilNextSweep() {
        var first=stage(NOW.minusSeconds(1),new byte[]{1});
        var second=stage(NOW,new byte[]{2});
        var future=stage(NOW.plusSeconds(1),new byte[]{3});
        var committed=stage(NOW.plusSeconds(2),new byte[]{4});
        uploading.publish(committed,"plan",UUID.randomUUID(),0,new AuditedAccountStore.AuditEvent("system:test","plan.saved","Synthetic fixture",NOW));
        var page1=maintenance.cleanDue(1,null);
        assertThat(page1.jobs()).extracting(StudyPlanDocumentMaintenance.JobResult::outcome).containsExactly(StudyPlanDocumentMaintenance.Outcome.CLEANED);
        assertThat(page1.next()).isNotNull();
        // Query is not a snapshot: a newly visible earlier key is picked up by the next sweep.
        var behindCursor=stage(NOW.minusSeconds(2),new byte[]{5});
        var later=new StudyPlanDocumentMaintenance(client,TABLES,Clock.fixed(NOW.plusSeconds(5),ZoneOffset.UTC));
        var page2=later.cleanDue(1,page1.next());
        assertThat(page2.cutoff()).isEqualTo(NOW);
        assertThat(page2.jobs()).extracting(StudyPlanDocumentMaintenance.JobResult::outcome).containsExactly(StudyPlanDocumentMaintenance.Outcome.CLEANED);
        if(page2.next()!=null)assertThat(later.cleanDue(1,page2.next()).jobs()).isEmpty();
        assertThat(manifest(first).get("lifecycle").s()).isEqualTo("DELETING");
        assertThat(manifest(second).get("lifecycle").s()).isEqualTo("DELETING");
        assertThat(manifest(future).get("lifecycle").s()).isEqualTo("STAGING");
        assertThat(manifest(behindCursor).get("lifecycle").s()).isEqualTo("STAGING");
        assertThat(jobs()).isEqualTo(2);
        assertThat(later.cleanDue(25,null).jobs()).hasSize(2);
        assertThat(jobs()).isZero();
        assertThat(expired.readPublished(committed.id())).containsExactly(4);
        assertThatThrownBy(()->uploading.writeChunk(first,0,software.amazon.awssdk.core.SdkBytes.fromByteArray(new byte[]{1})))
                .isInstanceOf(TransactionCanceledException.class);
    }

    @Test void corruptedJobCannotStarveNextPageAndInterruptedCleanupRetriesFromDurableFence() {
        String poison="DUE#000000000000#corrupt";
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .item(Map.of("pk",s("WORK#DOCUMENT#00"),"sk",s(poison),"schemaVersion",AttributeValue.builder().n("2").build())).build());
        var stage=stage(NOW,new byte[StudyPlanDocumentCodec.CHUNK_BYTES+1]);
        var page1=maintenance.cleanDue(1,null);
        assertThat(page1.jobs().getFirst().outcome()).isEqualTo(StudyPlanDocumentMaintenance.Outcome.FAILED);
        assertThat(page1.next()).isNotNull();
        AtomicInteger calls=new AtomicInteger();
        DynamoDbClient interrupted=(DynamoDbClient)Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),new Class<?>[]{DynamoDbClient.class},(proxy,method,args)->{
            try {
                var result=method.invoke(client,args);
                if(method.getName().equals("transactWriteItems") && calls.incrementAndGet()==1)throw SdkClientException.create("synthetic lost deletion response");
                return result;
            }catch(InvocationTargetException wrapped){throw wrapped.getCause();}
        });
        var failed=new StudyPlanDocumentMaintenance(interrupted,TABLES,clock).cleanDue(1,page1.next());
        assertThat(failed.jobs().getFirst().outcome()).isEqualTo(StudyPlanDocumentMaintenance.Outcome.FAILED);
        assertThat(manifest(stage).get("lifecycle").s()).isEqualTo("DELETING");
        assertThat(jobs()).isEqualTo(2);
        var retry=maintenance.cleanDue(25,null);
        assertThat(retry.jobs()).extracting(StudyPlanDocumentMaintenance.JobResult::outcome)
                .containsExactly(StudyPlanDocumentMaintenance.Outcome.FAILED,StudyPlanDocumentMaintenance.Outcome.CLEANED);
        assertThat(jobs()).isEqualTo(1);
        assertThat(client.query(QueryRequest.builder().tableName(TABLES.userStudyPlanDetailsTableName()).keyConditionExpression("pk=:pk")
                .expressionAttributeValues(Map.of(":pk",s(stage.id().pk()))).consistentRead(true).build()).items()).hasSize(1);
    }

    @Test void competingWorkersCanRetryWithoutResurrectionOrCommittedDocumentDeletion() throws Exception {
        var stage=stage(NOW,new byte[StudyPlanDocumentCodec.CHUNK_BYTES+1]);
        CountDownLatch start=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(()->{start.await();return maintenance.cleanDue(1,null);});
            var second=threads.submit(()->{start.await();return maintenance.cleanDue(1,null);});
            start.countDown();
            assertThat(first.get().jobs().size()).isLessThanOrEqualTo(1);
            assertThat(second.get().jobs().size()).isLessThanOrEqualTo(1);
        }
        maintenance.cleanDue(25,null);
        assertThat(jobs()).isZero();
        assertThat(manifest(stage).get("lifecycle").s()).isEqualTo("DELETING");
        assertThatIllegalStateException().isThrownBy(()->uploading.begin(stage));
    }
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
}
