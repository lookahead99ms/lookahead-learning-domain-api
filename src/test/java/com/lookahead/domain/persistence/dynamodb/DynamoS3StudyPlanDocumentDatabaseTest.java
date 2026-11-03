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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;

/** Actual SDK + DynamoDB Local transactions, with a finite loopback S3 protocol fixture. */
@EnabledIfEnvironmentVariable(named="DLV970_DYNAMODB_TEST_ENDPOINT",matches=".+")
class DynamoS3StudyPlanDocumentDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoMetadataTables TABLES=DynamoMetadataTablesTest.names("prod");
    private static final Instant NOW=Instant.parse("2026-11-02T12:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final AuditedAccountStore.AuditEvent AUDIT=new AuditedAccountStore.AuditEvent("system:fixture","plan.saved","Synthetic fixture",NOW);
    private S3StudyPlanDocumentHttpFixture s3;
    private S3StudyPlanDocumentStore store;
    private final S3StudyPlanDocumentObjects.Id id=new S3StudyPlanDocumentObjects.Id(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    private final byte[] bytes="{\"steps\":[\"synthetic\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeAll static void connect() {
        URI endpoint=URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if(!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort()<1024
                || endpoint.getPort()>65535 || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !endpoint.getPath().isEmpty())
            throw new IllegalArgumentException("Explicit loopback fixture endpoint required");
        client=DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic","synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    private String[] tables(){return new String[]{TABLES.userAccountDetailsTableName(),TABLES.administratorAuditEventsTableName(),TABLES.protectedOwnerIdentityTableName()};}
    @BeforeEach void setup() throws Exception {
        for(String table:tables()) client.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
        s3=new S3StudyPlanDocumentHttpFixture();store=new S3StudyPlanDocumentStore(client,TABLES,s3.objects(),CLOCK);
    }
    @AfterEach void cleanup(){if(s3!=null)s3.close();for(String table:tables())client.deleteTable(DeleteTableRequest.builder().tableName(table).build());}
    @AfterAll static void disconnect(){if(client!=null)client.close();}
    private S3StudyPlanDocumentStore.Stage stage(){return store.stage(id,bytes,NOW.plusSeconds(60));}
    private Map<String,AttributeValue> manifest(){return get("ACCOUNT#"+id.ownerId(),"S3DOCUMENT#"+id.planId()+"#"+id.documentId());}
    private Map<String,AttributeValue> get(String pk,String sk){return client.getItem(GetItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).key(Map.of("pk",s(pk),"sk",s(sk))).consistentRead(true).build()).item();}
    private long count(String table,String pk){return client.query(QueryRequest.builder().tableName(table).keyConditionExpression("pk=:pk").expressionAttributeValues(Map.of(":pk",s(pk))).consistentRead(true).build()).count();}
    private long jobs(){return count(TABLES.userAccountDetailsTableName(),"WORK#S3DOCUMENT#00");}
    private long audits(){return count(TABLES.administratorAuditEventsTableName(),"ACCOUNT#"+id.ownerId());}
    private AuditedAccountStore accounts(){return new AuditedAccountStore(client,TABLES);}
    private void replaceManifest(String field,AttributeValue value){var item=new HashMap<>(manifest());if(value==null)item.remove(field);else item.put(field,value);client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(item).build());}

    @Test void intentPrecedesS3AndPublicationCommitsPointerAuditReceiptManifestAndJobTogether() {
        AtomicBoolean intentObserved=new AtomicBoolean();
        s3.afterPut=()->intentObserved.set("STAGING".equals(manifest().get("lifecycle").s())&&jobs()==1);
        var staged=stage();assertThat(intentObserved).isTrue();
        assertThatIllegalStateException().isThrownBy(()->store.readPublished(id));
        assertThat(accounts().read(id.ownerId(),S3StudyPlanDocumentStore.recordId(id.planId()))).isEmpty();
        assertThat(store.stage(id,bytes,staged.abandonAfter())).isEqualTo(staged);
        UUID operation=UUID.randomUUID();assertThat(store.publish(staged,operation,0,AUDIT)).isEqualTo(new AuditedAccountStore.CommitResult(1,false));
        assertThat(manifest().get("lifecycle").s()).isEqualTo("COMMITTED");assertThat(jobs()).isZero();assertThat(audits()).isEqualTo(1);
        assertThat(get(staged.pk(),"MUTATION#"+operation)).isNotEmpty();
        assertThat(accounts().read(id.ownerId(),S3StudyPlanDocumentStore.recordId(id.planId())).orElseThrow().payload().asUtf8String())
                .startsWith("s3-document/v1|").contains("fixture/version+1");
        assertThat(store.readPublished(id)).containsExactly(bytes);
        var late=new S3StudyPlanDocumentStore(client,TABLES,s3.objects(),Clock.fixed(NOW.plusSeconds(120),ZoneOffset.UTC));
        assertThat(late.stage(id,bytes,staged.abandonAfter())).isEqualTo(staged);
        assertThat(late.publish(staged,operation,0,AUDIT).replayed()).isTrue();
        assertThatIllegalStateException().isThrownBy(()->store.publish(staged,UUID.randomUUID(),0,AUDIT));
        assertThatIllegalStateException().isThrownBy(()->store.publish(staged,operation,0,new AuditedAccountStore.AuditEvent("system:fixture","plan.changed","Different mutation",NOW)));
        assertThat(audits()).isEqualTo(1);assertThat(s3.putCalls.get()).isEqualTo(1);
        assertThat(new ProtectedOwnerIdentityStore(client,TABLES).matchesVerifiedIdentity("issuer","subject")).isFalse();
        assertThat(s3.requests).allMatch(r->r.method().equals("PUT")||r.method().equals("GET"));
    }
    @Test void lostResponsesAtEveryBoundaryRecoverWithoutDuplicateUploadsOrEvents() {
        s3.loseNextPutResponse=true;
        var uncertain=new S3StudyPlanDocumentStore(loseResponseAfter("transactWriteItems","updateItem","executeTransaction"),TABLES,s3.objects(),CLOCK);
        var staged=uncertain.stage(id,bytes,NOW.plusSeconds(60));UUID operation=UUID.randomUUID();
        assertThat(uncertain.publish(staged,operation,0,AUDIT).replayed()).isTrue();
        assertThat(store.publish(staged,operation,0,AUDIT).replayed()).isTrue();
        assertThat(store.readPublished(id)).containsExactly(bytes);assertThat(audits()).isEqualTo(1);assertThat(jobs()).isZero();assertThat(s3.versions).hasSize(1);
    }
    @Test void failedUploadRetainsIntentAndCanRetryButChangedInputsCannotReuseIdentity() {
        s3.failPut=true;assertThatThrownBy(this::stage).isInstanceOf(SdkException.class);
        assertThat(manifest().get("lifecycle").s()).isEqualTo("STAGING");assertThat(jobs()).isEqualTo(1);
        assertThatIllegalStateException().isThrownBy(()->store.stage(id,new byte[]{9},NOW.plusSeconds(60)));
        assertThatIllegalStateException().isThrownBy(()->store.stage(id,bytes,NOW.plusSeconds(61)));
        assertThat(audits()).isZero();s3.failPut=false;var staged=stage();
        assertThat(store.publish(staged,UUID.randomUUID(),0,AUDIT).revision()).isEqualTo(1);
    }
    @Test void staleRevisionAndCompetingManifestFenceRollBackAllPublicationWrites() {
        var first=stage();store.publish(first,UUID.randomUUID(),0,AUDIT);
        var secondId=new S3StudyPlanDocumentObjects.Id(id.ownerId(),id.planId(),UUID.randomUUID());
        var second=store.stage(secondId,new byte[]{7},NOW.plusSeconds(60));UUID failedOp=UUID.randomUUID();
        assertThatThrownBy(()->store.publish(second,failedOp,0,AUDIT)).isInstanceOf(TransactionCanceledException.class);
        assertThat(get(second.pk(),"MUTATION#"+failedOp)).isEmpty();assertThat(get(second.pk(),second.sk()).get("lifecycle").s()).isEqualTo("UPLOADED");
        assertThat(audits()).isEqualTo(1);assertThat(jobs()).isEqualTo(1);
        DynamoDbClient fenced=(DynamoDbClient)Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),new Class<?>[]{DynamoDbClient.class},(proxy,method,args)->{
            if(method.getName().equals("executeTransaction")) client.updateItem(UpdateItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                    .key(Map.of("pk",s(second.pk()),"sk",s(second.sk()))).updateExpression("SET lifecycle=:state")
                    .expressionAttributeValues(Map.of(":state",s("DELETING"))).build());
            try{return method.invoke(client,args);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}
        });
        var competing=new S3StudyPlanDocumentStore(fenced,TABLES,s3.objects(),CLOCK);
        assertThatThrownBy(()->competing.publish(second,UUID.randomUUID(),1,AUDIT)).isInstanceOf(TransactionCanceledException.class);
        assertThat(accounts().read(id.ownerId(),S3StudyPlanDocumentStore.recordId(id.planId())).orElseThrow().revision()).isEqualTo(1);
        assertThat(store.readPublished(id)).containsExactly(bytes);assertThat(audits()).isEqualTo(1);
    }
    @Test void alreadyMissingAbandonmentJobDoesNotInventAConditionFailureOrLosePublication() {
        var staged=stage();
        client.deleteItem(DeleteItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .key(Map.of("pk",s("WORK#S3DOCUMENT#00"),"sk",s(staged.jobKey()))).build());
        assertThat(store.publish(staged,UUID.randomUUID(),0,AUDIT).revision()).isEqualTo(1);
        assertThat(store.readPublished(id)).containsExactly(bytes);assertThat(audits()).isEqualTo(1);assertThat(jobs()).isZero();
    }
    @Test void concurrentSameDocumentAndOperationProduceOneVersionAndOneAudit() throws Exception {
        CountDownLatch start=new CountDownLatch(1);UUID operation=UUID.randomUUID();
        try(var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(()->{start.await();return stage();});var second=threads.submit(()->{start.await();return stage();});start.countDown();
            var staged=first.get();assertThat(second.get()).isEqualTo(staged);
            var one=threads.submit(()->store.publish(staged,operation,0,AUDIT));var two=threads.submit(()->store.publish(staged,operation,0,AUDIT));
            assertThat(one.get().revision()).isEqualTo(1);assertThat(two.get().revision()).isEqualTo(1);
        }
        assertThat(s3.versions).hasSize(1);assertThat(audits()).isEqualTo(1);assertThat(jobs()).isZero();
    }
    @Test void expiredIntentRejectsLateUploadAndLeavesDurableJobForFutureCleanup() {
        var mutable=org.mockito.Mockito.mock(Clock.class,org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));org.mockito.Mockito.when(mutable.instant()).thenReturn(NOW);
        var timed=new S3StudyPlanDocumentStore(client,TABLES,s3.objects(),mutable);
        s3.afterPut=()->org.mockito.Mockito.when(mutable.instant()).thenReturn(NOW.plusSeconds(60));
        assertThatIllegalStateException().isThrownBy(()->timed.stage(id,bytes,NOW.plusSeconds(60)));
        assertThat(s3.versions).hasSize(1);assertThat(manifest().get("lifecycle").s()).isEqualTo("STAGING");assertThat(jobs()).isEqualTo(1);
        assertThatIllegalStateException().isThrownBy(()->timed.stage(id,bytes,NOW.plusSeconds(60)));
        assertThat(audits()).isZero();assertThat(accounts().read(id.ownerId(),S3StudyPlanDocumentStore.recordId(id.planId()))).isEmpty();
    }
    @Test void missingOrCorruptS3VersionCannotBePublishedAndWrongOwnerCannotRead() {
        var staged=stage();String key=s3.path(id)+"?"+manifest().get("objectVersion").s();var original=s3.versions.remove(key);
        assertThatThrownBy(()->store.publish(staged,UUID.randomUUID(),0,AUDIT)).isInstanceOf(SdkException.class);
        s3.versions.put(key,new S3StudyPlanDocumentHttpFixture.Blob(new byte[bytes.length],original.metadata(),original.version(),original.etag(),original.kms()));
        assertThatIllegalStateException().isThrownBy(()->store.publish(staged,UUID.randomUUID(),0,AUDIT));
        assertThat(audits()).isZero();assertThat(jobs()).isEqualTo(1);assertThat(manifest().get("lifecycle").s()).isEqualTo("UPLOADED");
        s3.versions.put(key,original);store.publish(staged,UUID.randomUUID(),0,AUDIT);
        assertThatIllegalStateException().isThrownBy(()->store.readPublished(new S3StudyPlanDocumentObjects.Id(UUID.randomUUID(),id.planId(),id.documentId())));
        assertThatIllegalStateException().isThrownBy(()->store.readPublished(new S3StudyPlanDocumentObjects.Id(id.ownerId(),UUID.randomUUID(),id.documentId())));
    }
    @Test void corruptManifestsAndDeletingStateFailClosedWithoutPublication() {
        var staged=stage();var original=manifest();
        for(var fault:Map.of("schemaVersion",n(2),"storageKind",s("UNKNOWN"),"lifecycle",s("UNKNOWN"),"byteLength",s("bad"),"sha256",s("bad"),"objectKey",s("wrong"),"bucket",s("wrong")).entrySet()) {
            replaceManifest(fault.getKey(),fault.getValue());assertThatIllegalStateException().isThrownBy(()->store.readPublished(id));
            client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(original).build());
        }
        replaceManifest("objectVersion",null);assertThatIllegalStateException().isThrownBy(()->store.publish(staged,UUID.randomUUID(),0,AUDIT));
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(original).build());
        replaceManifest("lifecycle",s("DELETING"));assertThatIllegalStateException().isThrownBy(()->store.begin(staged));
        assertThatIllegalStateException().isThrownBy(()->store.publish(staged,UUID.randomUUID(),0,AUDIT));
        assertThatIllegalStateException().isThrownBy(this::stage);assertThat(audits()).isZero();
    }
    @Test void expiredAndInvalidConfigurationRejectBeforeIo() {
        var descriptor=S3StudyPlanDocumentObjects.Descriptor.of(id,bytes);
        for(Instant invalid:new Instant[]{Instant.EPOCH.minusSeconds(1),NOW.plusNanos(1),Instant.parse("+10000-01-01T00:00:00Z")})
            assertThatIllegalArgumentException().isThrownBy(()->new S3StudyPlanDocumentStore.Stage(descriptor,invalid));
        assertThatIllegalArgumentException().isThrownBy(()->new S3StudyPlanDocumentStore(client,DynamoMetadataTablesTest.names("dev"),s3.objects(),CLOCK));
        assertThatIllegalStateException().isThrownBy(()->store.stage(id,bytes,NOW));assertThat(manifest()).isEmpty();assertThat(s3.requests).isEmpty();
        var staged=stage();var expired=new S3StudyPlanDocumentStore(client,TABLES,s3.objects(),Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC));
        assertThatIllegalStateException().isThrownBy(()->expired.publish(staged,UUID.randomUUID(),0,AUDIT));assertThat(audits()).isZero();
    }
    private DynamoDbClient loseResponseAfter(String... methodNames) {
        var remaining=java.util.concurrent.ConcurrentHashMap.newKeySet();remaining.addAll(java.util.List.of(methodNames));
        return (DynamoDbClient)Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),new Class<?>[]{DynamoDbClient.class},(proxy,method,args)->{
            try {var result=method.invoke(client,args);if(remaining.remove(method.getName()))throw SdkClientException.create("Synthetic lost "+method.getName()+" response");return result;}
            catch(InvocationTargetException wrapped){throw wrapped.getCause();}
        });
    }
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
}
