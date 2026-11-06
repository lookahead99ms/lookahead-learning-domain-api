package com.lookahead.domain.persistence.dynamodb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic, owned Local tables only; no default credentials or AWS endpoints. */
@EnabledIfEnvironmentVariable(named="DLV970_DYNAMODB_TEST_ENDPOINT", matches=".+")
class DynamoDocumentRetirementDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("prod");
    private static final String JOBS = "WORK#RETIRED-DOCUMENT#00";
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StudyPlanDocumentStore documents = new StudyPlanDocumentStore(client, TABLES, CLOCK);
    private final StudyPlanDocumentRetirement retirement = new StudyPlanDocumentRetirement(client, TABLES);
    private final AuditedAccountStore accounts = new AuditedAccountStore(client, TABLES);
    private final StudyPlanDocumentStore.DocumentId id = new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(), UUID.randomUUID());
    private final AuditedAccountStore.AuditEvent saved = new AuditedAccountStore.AuditEvent("account:synthetic", "plan.saved", "Fixture", NOW);
    private final AuditedAccountStore.AuditEvent deleted = new AuditedAccountStore.AuditEvent("account:synthetic", "plan.deleted", "Fixture", NOW);
    private final UUID publishId = UUID.randomUUID();

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort()<1024
                || endpoint.getPort()>65535 || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null
                || endpoint.getFragment()!=null || !endpoint.getPath().isEmpty()) throw new IllegalArgumentException("Loopback fixture required");
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    private String[] tables() {return new String[]{TABLES.userAccountDetailsTableName(),TABLES.userStudyPlanDetailsTableName(),TABLES.administratorAuditEventsTableName()};}
    @BeforeEach void createTables() {
        for (String table : tables()) client.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
    }
    @AfterEach void removeOwnedTables() {for(String table:tables())client.deleteTable(DeleteTableRequest.builder().tableName(table).build());}
    @AfterAll static void close() {if(client!=null)client.close();}
    private StudyPlanDocumentStore.Stage published(byte[] bytes) {
        var stage=documents.stage(id,bytes,NOW.plusSeconds(60));documents.publish(stage,"plan",publishId,0,saved);return stage;
    }
    private Map<String,AttributeValue> manifest() {return client.getItem(GetItemRequest.builder().tableName(TABLES.userStudyPlanDetailsTableName()).key(key(id.pk(),"MANIFEST")).consistentRead(true).build()).item();}
    private long count(String table,String pk) {return client.query(QueryRequest.builder().tableName(table).keyConditionExpression("pk=:pk").expressionAttributeValues(Map.of(":pk",s(pk))).consistentRead(true).build()).count();}
    private long jobs(){return count(TABLES.userAccountDetailsTableName(),JOBS);}
    private long audits(){return count(TABLES.administratorAuditEventsTableName(),"ACCOUNT#"+id.ownerId());}
    private void patchManifest(String field,AttributeValue value) {
        var item=new HashMap<>(manifest());if(value==null)item.remove(field);else item.put(field,value);
        client.putItem(PutItemRequest.builder().tableName(TABLES.userStudyPlanDetailsTableName()).item(item).build());
    }
    private void putJob(String key,StudyPlanDocumentStore.DocumentId doc,UUID operation) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .item(Map.of("pk",s(JOBS),"sk",s(key),"schemaVersion",n(1),"documentPk",s(doc.pk()),"retirementId",s(operation.toString()))).build());
    }

    @Test void maximumDocumentRetiresAtomicallyAndIsNeverReadableOrRepublishedAfterPurge() {
        byte[] bytes=new byte[8*1024*1024];var stage=published(bytes);UUID operation=UUID.randomUUID();
        assertThat(retirement.retire(stage,"plan",operation,1,deleted)).isEqualTo(new AuditedAccountStore.CommitResult(2,false));
        assertThat(manifest().get("lifecycle").s()).isEqualTo("RETIRED");assertThat(jobs()).isEqualTo(1);assertThat(audits()).isEqualTo(2);
        assertThat(accounts.read(id.ownerId(),"plan").orElseThrow().payload().asUtf8String()).contains("retired-study-plan-document");
        assertThatIllegalStateException().isThrownBy(()->documents.readPublished(id));
        assertThatIllegalStateException().isThrownBy(()->documents.stage(id,bytes,stage.abandonAfter()));
        assertThatIllegalStateException().isThrownBy(()->documents.publish(stage,"plan",publishId,0,saved));
        assertThatThrownBy(()->documents.writeChunk(stage,0,SdkBytes.fromByteArray(new byte[StudyPlanDocumentCodec.CHUNK_BYTES]))).isInstanceOf(TransactionCanceledException.class);
        assertThat(documents.cleanAbandoned(id)).isFalse();
        assertThat(retirement.cleanRetired(id)).isTrue();assertThat(retirement.cleanRetired(id)).isTrue();
        assertThat(count(TABLES.userStudyPlanDetailsTableName(),id.pk())).isEqualTo(1);assertThat(jobs()).isZero();
        assertThat(retirement.retire(stage,"plan",operation,1,deleted).replayed()).isTrue();assertThat(audits()).isEqualTo(2);
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"plan",UUID.randomUUID(),1,deleted));
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"other",operation,1,deleted));
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"plan",operation,2,deleted));
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"plan",operation,1,saved));
    }

    @Test void staleRevisionForeignOwnerWrongRecordAndChangedManifestCannotRetire() {
        var stage=published(new byte[]{1});
        assertThatIllegalArgumentException().isThrownBy(()->retirement.retire(stage,"plan",UUID.randomUUID(),0,deleted));
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"plan",UUID.randomUUID(),2,deleted));
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"missing",UUID.randomUUID(),1,deleted));
        var foreign=new StudyPlanDocumentStore.Stage(new StudyPlanDocumentStore.DocumentId(UUID.randomUUID(),id.documentId()),stage.manifest(),stage.abandonAfter());
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(foreign,"plan",UUID.randomUUID(),1,deleted));
        var changed=new StudyPlanDocumentStore.Stage(id,stage.manifest(),stage.abandonAfter().plusSeconds(1));
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(changed,"plan",UUID.randomUUID(),1,deleted));
        accounts.commit(new AuditedAccountStore.Mutation(id.ownerId(),"other",UUID.randomUUID(),0,SdkBytes.fromUtf8String("different"),saved));
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"other",UUID.randomUUID(),1,deleted));
        assertThat(retirement.cleanRetired(id)).isFalse();assertThat(retirement.cleanRetired(foreign.id())).isFalse();
        assertThat(jobs()).isZero();assertThat(documents.readPublished(id)).containsExactly(1);
    }

    @Test void failedTransactionRollsBackTombstoneAuditReceiptManifestAndJob() {
        var stage=published(new byte[]{1});UUID operation=UUID.randomUUID();putJob(id.pk(),id,UUID.randomUUID());
        assertThatThrownBy(()->retirement.retire(stage,"plan",operation,1,deleted)).isInstanceOf(TransactionCanceledException.class);
        assertThat(accounts.read(id.ownerId(),"plan").orElseThrow().revision()).isEqualTo(1);
        assertThat(manifest().get("lifecycle").s()).isEqualTo("COMMITTED");assertThat(audits()).isEqualTo(1);assertThat(jobs()).isEqualTo(1);
        assertThat(client.getItem(GetItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .key(key("ACCOUNT#"+id.ownerId(),"MUTATION#"+operation)).consistentRead(true).build()).item()).isEmpty();
    }

    @Test void lostRetirementReplyAndInterruptedChunkPurgeRecoverFromDurableState() {
        var stage=published(new byte[StudyPlanDocumentCodec.CHUNK_BYTES+1]);UUID operation=UUID.randomUUID();
        var uncertain=new StudyPlanDocumentRetirement(loseAfter("executeTransaction",1),TABLES);
        assertThat(uncertain.retire(stage,"plan",operation,1,deleted).replayed()).isTrue();assertThat(audits()).isEqualTo(2);
        var interrupted=new StudyPlanDocumentRetirement(loseAfter("transactWriteItems",1),TABLES);
        assertThatThrownBy(()->interrupted.cleanRetired(id)).isInstanceOf(SdkClientException.class);
        assertThat(jobs()).isEqualTo(1);assertThat(count(TABLES.userStudyPlanDetailsTableName(),id.pk())).isEqualTo(2);
        assertThat(retirement.cleanRetired(id)).isTrue();assertThat(jobs()).isZero();assertThat(count(TABLES.userStudyPlanDetailsTableName(),id.pk())).isEqualTo(1);
        assertThat(retirement.retire(stage,"plan",operation,1,deleted).replayed()).isTrue();
    }

    @Test void retirementRacingNewPublicationCannotDeleteTheWinningReference() {
        var stage=published(new byte[]{1});
        var replacement=documents.stage(new StudyPlanDocumentStore.DocumentId(id.ownerId(),UUID.randomUUID()),new byte[]{2},NOW.plusSeconds(60));
        AtomicBoolean inject=new AtomicBoolean(true);
        DynamoDbClient competing=proxy((name,args)->{
            if(name.equals("executeTransaction")&&inject.getAndSet(false)) documents.publish(replacement,"plan",UUID.randomUUID(),1,saved);
        },(name,args)->{});
        assertThatThrownBy(()->new StudyPlanDocumentRetirement(competing,TABLES).retire(stage,"plan",UUID.randomUUID(),1,deleted)).isInstanceOf(TransactionCanceledException.class);
        assertThat(accounts.read(id.ownerId(),"plan").orElseThrow().revision()).isEqualTo(2);
        assertThat(manifest().get("lifecycle").s()).isEqualTo("COMMITTED");assertThat(jobs()).isZero();assertThat(audits()).isEqualTo(2);
        assertThat(documents.readPublished(replacement.id())).containsExactly(2);
    }

    @Test void sameOperationConcurrentRetirementsAndWorkersAreRetryable() throws Exception {
        var stage=published(new byte[StudyPlanDocumentCodec.CHUNK_BYTES+1]);UUID operation=UUID.randomUUID();CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();return retirement.retire(stage,"plan",operation,1,deleted);});
            var b=executor.submit(()->{start.await();return retirement.retire(stage,"plan",operation,1,deleted);});start.countDown();
            assertThat(a.get().revision()).isEqualTo(2);assertThat(b.get().revision()).isEqualTo(2);
            var c=executor.submit(()->retirement.cleanPage(25,null));var d=executor.submit(()->retirement.cleanPage(25,null));
            assertThat(c.get().jobs().size()).isLessThanOrEqualTo(1);assertThat(d.get().jobs().size()).isLessThanOrEqualTo(1);
        }
        retirement.cleanPage(25,null);assertThat(jobs()).isZero();assertThat(audits()).isEqualTo(2);
    }

    @Test void identicalRetirementBetweenManifestAndPointerReadsUsesOnlyDurableReceipt() {
        var stage=published(new byte[]{1});UUID operation=UUID.randomUUID();AtomicBoolean inject=new AtomicBoolean(true);
        DynamoDbClient concurrent=proxy((name,args)->{
            if(name.equals("getItem") && args[0] instanceof GetItemRequest request
                    && request.tableName().equals(TABLES.userAccountDetailsTableName())
                    && request.key().get("sk").s().startsWith("RECORD#") && inject.getAndSet(false)) {
                retirement.retire(stage,"plan",operation,1,deleted);
            }
        },(name,args)->{});
        assertThat(new StudyPlanDocumentRetirement(concurrent,TABLES).retire(stage,"plan",operation,1,deleted).replayed()).isTrue();
        assertThat(audits()).isEqualTo(2);assertThat(jobs()).isEqualTo(1);
    }

    @Test void cleanupFenceChangeCannotDeleteAChunkAndMismatchedJobIsRetained() {
        var stage=published(new byte[]{1});UUID operation=UUID.randomUUID();retirement.retire(stage,"plan",operation,1,deleted);
        putJob(id.pk(),id,UUID.randomUUID());
        assertThat(retirement.cleanPage(25,null).jobs().getFirst().outcome()).isEqualTo(StudyPlanDocumentRetirement.Outcome.FAILED);
        assertThat(count(TABLES.userStudyPlanDetailsTableName(),id.pk())).isEqualTo(2);
        AtomicBoolean inject=new AtomicBoolean(true);
        var changed=new StudyPlanDocumentRetirement(proxy((name,args)->{
            if(name.equals("transactWriteItems")&&inject.getAndSet(false))patchManifest("retirementId",s(UUID.randomUUID().toString()));
        },(name,args)->{}),TABLES);
        assertThatThrownBy(()->changed.cleanRetired(id)).isInstanceOf(TransactionCanceledException.class);
        assertThat(count(TABLES.userStudyPlanDetailsTableName(),id.pk())).isEqualTo(2);assertThat(jobs()).isEqualTo(1);
    }

    @Test void paginationSkipsBadJobsPreservesActiveDocumentsAndContinuesAfterDeletedKeys() {
        var stage=published(new byte[]{1});retirement.retire(stage,"plan",UUID.randomUUID(),1,deleted);
        putJob("!bad",id,UUID.randomUUID());
        var first=retirement.cleanPage(1,null);assertThat(first.jobs().getFirst().outcome()).isEqualTo(StudyPlanDocumentRetirement.Outcome.FAILED);assertThat(first.afterKey()).isNotNull();
        var second=retirement.cleanPage(1,first.afterKey());assertThat(second.jobs().getFirst().outcome()).isEqualTo(StudyPlanDocumentRetirement.Outcome.CLEANED);
        if(second.afterKey()!=null)assertThat(retirement.cleanPage(1,second.afterKey()).jobs()).isEmpty();
        var active=documents.stage(new StudyPlanDocumentStore.DocumentId(id.ownerId(),UUID.randomUUID()),new byte[]{3},NOW.plusSeconds(60));
        documents.publish(active,"active",UUID.randomUUID(),0,saved);putJob(active.id().pk(),active.id(),UUID.randomUUID());
        var missing=new StudyPlanDocumentStore.DocumentId(id.ownerId(),UUID.randomUUID());putJob(missing.pk(),missing,UUID.randomUUID());
        assertThat(retirement.cleanPage(25,null).jobs()).extracting(StudyPlanDocumentRetirement.JobResult::outcome)
                .containsExactlyInAnyOrder(StudyPlanDocumentRetirement.Outcome.FAILED,StudyPlanDocumentRetirement.Outcome.RETAINED,StudyPlanDocumentRetirement.Outcome.RETAINED);
        assertThat(documents.readPublished(active.id())).containsExactly(3);
    }

    @Test void lostFinalJobRemovalDoesNotLoseTheTombstoneOrDuplicateAuditOnRetry() {
        var stage=published(new byte[]{1});UUID operation=UUID.randomUUID();retirement.retire(stage,"plan",operation,1,deleted);
        var uncertain=new StudyPlanDocumentRetirement(loseAfter("transactWriteItems",2),TABLES);
        assertThatThrownBy(()->uncertain.cleanRetired(id)).isInstanceOf(SdkClientException.class);
        assertThat(jobs()).isZero();assertThat(retirement.cleanRetired(id)).isTrue();
        assertThat(retirement.retire(stage,"plan",operation,1,deleted).replayed()).isTrue();assertThat(audits()).isEqualTo(2);
    }

    @Test void missingReceiptDoesNotInventRetirementSuccessAndMalformedStatesFailClosed() {
        var stage=published(new byte[]{1});UUID operation=UUID.randomUUID();retirement.retire(stage,"plan",operation,1,deleted);
        client.deleteItem(DeleteItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).key(key("ACCOUNT#"+id.ownerId(),"MUTATION#"+operation)).build());
        assertThatIllegalStateException().isThrownBy(()->retirement.retire(stage,"plan",operation,1,deleted));
        patchManifest("retirementId",null);assertThatIllegalStateException().isThrownBy(()->retirement.cleanRetired(id));
        assertThat(retirement.cleanPage(25,null).jobs().getFirst().outcome()).isEqualTo(StudyPlanDocumentRetirement.Outcome.FAILED);
        assertThat(count(TABLES.userStudyPlanDetailsTableName(),id.pk())).isEqualTo(2);
    }

    @Test void committedChunkPublicationCannotUseCachedSdkSuccessAfterReceiptLoss() {
        var stage=published(new byte[]{1});
        client.deleteItem(DeleteItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .key(key("ACCOUNT#"+id.ownerId(),"MUTATION#"+publishId)).build());
        assertThatIllegalStateException().isThrownBy(()->documents.publish(stage,"plan",publishId,0,saved));
        assertThat(audits()).isEqualTo(1);assertThat(documents.readPublished(id)).containsExactly(1);
    }

    @Test void deferredS3PublicationAlsoRequiresDurableReceiptAfterCommit() throws Exception {
        try(var fixture=new S3StudyPlanDocumentHttpFixture()) {
            var store=new S3StudyPlanDocumentStore(client,DynamoMetadataTablesTest.names("prod"),fixture.objects(),CLOCK);
            var documentId=new S3StudyPlanDocumentObjects.Id(id.ownerId(),UUID.randomUUID(),UUID.randomUUID());
            var stage=store.stage(documentId,new byte[]{1},NOW.plusSeconds(60));
            store.publish(stage,publishId,0,saved);
            client.deleteItem(DeleteItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).key(key("ACCOUNT#"+id.ownerId(),"MUTATION#"+publishId)).build());
            assertThatIllegalStateException().isThrownBy(()->store.publish(stage,publishId,0,saved));
            assertThat(audits()).isEqualTo(1);assertThat(store.readPublished(documentId)).containsExactly(1);
        }
    }

    private interface Hook {void run(String name,Object[] args);}
    private DynamoDbClient proxy(Hook before,Hook after) {
        return (DynamoDbClient)Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),new Class<?>[]{DynamoDbClient.class},(proxy,method,args)->{
            before.run(method.getName(),args);
            try {var result=method.invoke(client,args);after.run(method.getName(),args);return result;}
            catch(InvocationTargetException wrapped){throw wrapped.getCause();}
        });
    }
    private DynamoDbClient loseAfter(String target,int occurrence) {
        AtomicInteger calls=new AtomicInteger();return proxy((name,args)->{},(name,args)->{
            if(name.equals(target)&&calls.incrementAndGet()==occurrence)throw SdkClientException.create("Synthetic lost response");
        });
    }
    private static Map<String,AttributeValue> key(String pk,String sk){return Map.of("pk",s(pk),"sk",s(sk));}
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
}
