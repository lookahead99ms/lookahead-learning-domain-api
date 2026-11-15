package com.lookahead.domain.persistence.dynamodb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Owned loopback fixture only. Tests raw writes to simulate damaged restore, not runtime IAM. */
@EnabledIfEnvironmentVariable(named="DLV970_DYNAMODB_TEST_ENDPOINT", matches=".+")
class DynamoAccountControlDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("prod");
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID accountId=UUID.randomUUID();
    private final DynamoAccountControlStore.Owner owner=new DynamoAccountControlStore.Owner(UUID.randomUUID(),"https://issuer.test/pool","owner-subject");
    private final DynamoAccountControlStore controls=new DynamoAccountControlStore(client,TABLES);

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort()<1024
                || endpoint.getPort()>65535 || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null
                || endpoint.getFragment()!=null || !endpoint.getPath().isEmpty()) throw new IllegalArgumentException("Loopback fixture required");
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    private String[] tables() {return new String[]{TABLES.userAccountDetailsTableName(),TABLES.administratorAuditEventsTableName(),TABLES.protectedOwnerIdentityTableName()};}
    @BeforeEach void createTables() {
        for (String table : tables()) client.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
        put(TABLES.protectedOwnerIdentityTableName(),Map.of("pk",s("OWNER"),"sk",s("ROOT"),"schemaVersion",n(1),"issuer",s(owner.issuer()),"subject",s(owner.subject())));
        put(TABLES.userAccountDetailsTableName(),initial());
    }
    @AfterEach void removeOwnedTables() {for(String table:tables())client.deleteTable(DeleteTableRequest.builder().tableName(table).build());}
    @AfterAll static void close() {if(client!=null)client.close();}

    @Test void disableEnableAndNoOpKeepGenerationsAndOneEventPerRealChange() {
        var disable=command(DynamoAccountControlStore.Action.DISABLE,1);
        var first=controls.transition(disable);assertThat(first.replayed()).isFalse();
        assertThat(first.current().state()).isEqualTo(DynamoAccountControlStore.State.DISABLED);
        assertThat(first.current().authGeneration()).isEqualTo(1);assertThat(events()).isEqualTo(1);
        assertThat(intent(disable).get("providerStatus").s()).isEqualTo("PENDING");
        assertThat(controls.transition(disable).replayed()).isTrue();assertThat(events()).isEqualTo(1);
        var enable=command(DynamoAccountControlStore.Action.ENABLE,2);var second=controls.transition(enable);
        assertThat(second.current().state()).isEqualTo(DynamoAccountControlStore.State.ACTIVE);
        assertThat(second.current().authGeneration()).isEqualTo(1);assertThat(events()).isEqualTo(2);
        var noOp=controls.transition(command(DynamoAccountControlStore.Action.ENABLE,3));
        assertThat(noOp.current().revision()).isEqualTo(4);assertThat(noOp.current().authGeneration()).isEqualTo(1);assertThat(events()).isEqualTo(2);
        var superseded=controls.transition(disable);assertThat(superseded.superseded()).isTrue();
        assertThat(superseded.current().state()).isEqualTo(DynamoAccountControlStore.State.ACTIVE);assertThat(superseded.commandRevision()).isEqualTo(2);
        var again=controls.transition(command(DynamoAccountControlStore.Action.DISABLE,4));
        assertThat(again.current().authGeneration()).isEqualTo(2);
        var noopDisabled=controls.transition(command(DynamoAccountControlStore.Action.DISABLE,5));
        assertThat(noopDisabled.current().authGeneration()).isEqualTo(2);assertThat(events()).isEqualTo(3);
    }

    @Test void removalAtomicallyRecordsTerminalMarkerEventAndErasureBeforeProvider() {
        var remove=command(DynamoAccountControlStore.Action.REMOVE,1);var result=controls.transition(remove);
        assertThat(result.current().state()).isEqualTo(DynamoAccountControlStore.State.REMOVED);
        assertThat(result.current().authGeneration()).isEqualTo(1);assertThat(events()).isEqualTo(1);
        assertThat(intent(remove).get("providerStatus").s()).isEqualTo("AWAITING_ERASURE");
        var job=get(TABLES.userAccountDetailsTableName(),"ACCOUNT_REMOVAL#"+accountId,"JOB");
        assertThat(job.get("phase").s()).isEqualTo("ERASE");assertThat(job.get("subject").s()).isEqualTo("target-subject");
        assertThat(controls.transition(remove).replayed()).isTrue();assertThat(events()).isEqualTo(1);
        assertThatIllegalStateException().isThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.REMOVE,2)));
        delete(TABLES.userAccountDetailsTableName(),"ACCOUNT#"+accountId,"CONTROL");
        assertThat(controls.read(accountId).orElseThrow().state()).isEqualTo(DynamoAccountControlStore.State.REMOVED);
        put(TABLES.userAccountDetailsTableName(),initial());
        assertThat(controls.read(accountId).orElseThrow().state()).isEqualTo(DynamoAccountControlStore.State.REMOVED);
        assertThatIllegalStateException().isThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.ENABLE,1)));
        assertThatIllegalStateException().isThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.DISABLE,1)));
        assertThat(controls.transition(remove).replayed()).isTrue();
    }

    @Test void removedAccountNeverReplaysOldEnableOrDisableAsActive() {
        var disable=command(DynamoAccountControlStore.Action.DISABLE,1);controls.transition(disable);
        var enable=command(DynamoAccountControlStore.Action.ENABLE,2);controls.transition(enable);
        var remove=command(DynamoAccountControlStore.Action.REMOVE,3);controls.transition(remove);
        assertThatIllegalStateException().isThrownBy(()->controls.transition(enable)).withMessageContaining("final");
        assertThatIllegalStateException().isThrownBy(()->controls.transition(disable)).withMessageContaining("final");
        assertThat(controls.read(accountId).orElseThrow().authGeneration()).isEqualTo(2);
    }

    @Test void ownerSelfAuthorManagedAndWaitingGuardsNeverWrite() {
        var badOwner=new DynamoAccountControlStore.Owner(owner.accountId(),owner.issuer(),"someone-else");
        assertThatIllegalStateException().isThrownBy(()->controls.transition(new DynamoAccountControlStore.Command(badOwner,accountId,UUID.randomUUID(),1,DynamoAccountControlStore.Action.DISABLE,NOW)));
        var self=new DynamoAccountControlStore.Owner(accountId,owner.issuer(),owner.subject());
        assertThatIllegalStateException().isThrownBy(()->controls.transition(new DynamoAccountControlStore.Command(self,accountId,UUID.randomUUID(),1,DynamoAccountControlStore.Action.DISABLE,NOW)));
        for(String flag:List.of("author","managedTest","admitted")) {
            patch(flag,AttributeValue.builder().bool(!flag.equals("admitted")).build());
            assertThatIllegalStateException().isThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.DISABLE,1)));
            put(TABLES.userAccountDetailsTableName(),initial());
        }
        patch("subject",s(owner.subject()));assertThatIllegalStateException().isThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.DISABLE,1)));
        assertThat(events()).isZero();assertThat(get(TABLES.userAccountDetailsTableName(),"ACCOUNT#"+accountId,"CONTROL").get("revision").n()).isEqualTo("1");
    }

    @Test void conflictingJobRollsBackControlMarkerEventAndCommand() {
        put(TABLES.userAccountDetailsTableName(),Map.of("pk",s("ACCOUNT_REMOVAL#"+accountId),"sk",s("JOB")));
        var remove=command(DynamoAccountControlStore.Action.REMOVE,1);
        assertThatThrownBy(()->controls.transition(remove)).isInstanceOf(DynamoWrites.Conflict.class);
        assertThat(controls.read(accountId).orElseThrow().state()).isEqualTo(DynamoAccountControlStore.State.ACTIVE);
        assertThat(intent(remove)).isEmpty();assertThat(events()).isZero();assertThat(marker()).isEmpty();
    }

    @Test void sameOperationConcurrentCallsConvergeAndLostReplyDoesNotDuplicateEvent() throws Exception {
        var disable=command(DynamoAccountControlStore.Action.DISABLE,1);CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();return controls.transition(disable);});
            var b=executor.submit(()->{start.await();return controls.transition(disable);});start.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS).commandRevision()).isEqualTo(2);assertThat(b.get(20,TimeUnit.SECONDS).commandRevision()).isEqualTo(2);
        }
        assertThat(events()).isEqualTo(1);
        var uncertain=new DynamoAccountControlStore(proxy((name,args)->{},(name,args)->{if(name.equals("executeTransaction"))throw SdkClientException.create("lost reply");}),TABLES);
        var enable=command(DynamoAccountControlStore.Action.ENABLE,2);
        assertThat(uncertain.transition(enable).replayed()).isTrue();assertThat(events()).isEqualTo(2);
    }

    @Test void staleRevisionAndCompetingTransitionCannotOverwriteWinner() {
        assertThatIllegalStateException().isThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.DISABLE,2)));
        AtomicBoolean once=new AtomicBoolean(true);
        var loser=new DynamoAccountControlStore(proxy((name,args)->{
            if(name.equals("executeTransaction")&&once.getAndSet(false))controls.transition(command(DynamoAccountControlStore.Action.DISABLE,1));
        },(name,args)->{}),TABLES);
        var enable=command(DynamoAccountControlStore.Action.ENABLE,1);
        assertThatThrownBy(()->loser.transition(enable)).isInstanceOf(DynamoWrites.Conflict.class);
        assertThat(intent(enable)).isEmpty();assertThat(controls.read(accountId).orElseThrow().state()).isEqualTo(DynamoAccountControlStore.State.DISABLED);
    }

    @Test void fingerprintBindsActorActionTimeAndExpectedRevision() {
        var original=command(DynamoAccountControlStore.Action.DISABLE,1);controls.transition(original);
        for(var changed:List.of(
                new DynamoAccountControlStore.Command(owner,accountId,original.operationId(),1,DynamoAccountControlStore.Action.ENABLE,NOW),
                new DynamoAccountControlStore.Command(owner,accountId,original.operationId(),2,original.action(),NOW),
                new DynamoAccountControlStore.Command(owner,accountId,original.operationId(),1,original.action(),NOW.plusSeconds(1)),
                new DynamoAccountControlStore.Command(new DynamoAccountControlStore.Owner(UUID.randomUUID(),owner.issuer(),owner.subject()),accountId,original.operationId(),1,original.action(),NOW)))
            assertThatIllegalStateException().isThrownBy(()->controls.transition(changed)).withMessageContaining("mismatch");
        assertThat(events()).isEqualTo(1);
    }

    @Test void receiptLossOrRestoredControlCannotInventSuccess() {
        var command=command(DynamoAccountControlStore.Action.DISABLE,1);
        var lostReceipt=new DynamoAccountControlStore(proxy((name,args)->{},(name,args)->{
            if(name.equals("executeTransaction"))delete(TABLES.userAccountDetailsTableName(),"ACCOUNT_COMMAND#"+accountId,"OP#"+command.operationId());
        }),TABLES);
        assertThatIllegalStateException().isThrownBy(()->lostReceipt.transition(command)).withMessageContaining("No durable");
        assertThatIllegalStateException().isThrownBy(()->controls.transition(command)).withMessageContaining("revision conflict");
        var enable=command(DynamoAccountControlStore.Action.ENABLE,2);controls.transition(enable);put(TABLES.userAccountDetailsTableName(),initial());
        assertThatIllegalStateException().isThrownBy(()->controls.transition(enable)).withMessageContaining("behind");
    }

    @Test void providerIntentReadFailurePreservesWriteFailureForRetry() {
        AtomicBoolean committed=new AtomicBoolean();var operation=command(DynamoAccountControlStore.Action.DISABLE,1);
        var uncertain=new DynamoAccountControlStore(proxy((name,args)->{
            if(name.equals("getItem")&&committed.get())throw SdkClientException.create("read lost");
        },(name,args)->{if(name.equals("executeTransaction")){committed.set(true);throw SdkClientException.create("write reply lost");}}),TABLES);
        assertThatThrownBy(()->uncertain.transition(operation)).isInstanceOf(DynamoWrites.Unavailable.class).hasMessage("DynamoDB write unavailable: SdkClientException")
                .satisfies(error->{assertThat(error.getCause()).hasMessage("write reply lost");assertThat(error.getSuppressed()).hasSize(1);});
        assertThat(controls.transition(operation).replayed()).isTrue();assertThat(events()).isEqualTo(1);
    }

    @Test void identityOrGuardChangeBetweenReadAndWriteFailsTransaction() {
        AtomicBoolean once=new AtomicBoolean(true);
        var raced=new DynamoAccountControlStore(proxy((name,args)->{
            if(name.equals("executeTransaction")&&once.getAndSet(false))patch("managedTest",AttributeValue.builder().bool(true).build());
        },(name,args)->{}),TABLES);
        assertThatThrownBy(()->raced.transition(command(DynamoAccountControlStore.Action.REMOVE,1))).isInstanceOf(DynamoWrites.Conflict.class);
        assertThat(marker()).isEmpty();assertThat(events()).isZero();
    }

    @Test void missingMalformedAndOverflowStateNeverWrites() {
        assertThat(controls.read(UUID.randomUUID())).isEmpty();
        delete(TABLES.userAccountDetailsTableName(),"ACCOUNT#"+accountId,"CONTROL");
        assertThatIllegalStateException().isThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.DISABLE,1)));
        for(String field:List.of("schemaVersion","issuer","admitted","revision","authGeneration")) {
            var row=initial();row.remove(field);put(TABLES.userAccountDetailsTableName(),row);
            assertThatThrownBy(()->controls.read(accountId)).isInstanceOf(RuntimeException.class);
        }
        put(TABLES.userAccountDetailsTableName(),initial());patch("accountState",s("REMOVED"));
        assertThatIllegalStateException().isThrownBy(()->controls.read(accountId)).withMessageContaining("no terminal");
        put(TABLES.userAccountDetailsTableName(),initial());patch("authGeneration",n(Long.MAX_VALUE));
        assertThatThrownBy(()->controls.transition(command(DynamoAccountControlStore.Action.DISABLE,1))).isInstanceOf(ArithmeticException.class);
        assertThat(events()).isZero();
    }

    @Test void malformedRemovalMarkerAndReceiptFailClosed() {
        var remove=command(DynamoAccountControlStore.Action.REMOVE,1);controls.transition(remove);
        var original=marker();var altered=new HashMap<>(original);altered.put("accountState",s("ACTIVE"));put(TABLES.administratorAuditEventsTableName(),altered);
        assertThatIllegalStateException().isThrownBy(()->controls.read(accountId));
        put(TABLES.administratorAuditEventsTableName(),original);
        var receipt=new HashMap<>(intent(remove));receipt.put("revision",n(77));put(TABLES.userAccountDetailsTableName(),receipt);
        assertThatIllegalStateException().isThrownBy(()->controls.transition(remove)).withMessageContaining("command revision");
        receipt.put("revision",n(2));put(TABLES.userAccountDetailsTableName(),receipt);
        altered=new HashMap<>(original);altered.put("operationId",s(UUID.randomUUID().toString()));put(TABLES.administratorAuditEventsTableName(),altered);
        assertThatIllegalStateException().isThrownBy(()->controls.transition(remove)).withMessageContaining("matching terminal");
    }

    private DynamoAccountControlStore.Command command(DynamoAccountControlStore.Action action,long revision){return new DynamoAccountControlStore.Command(owner,accountId,UUID.randomUUID(),revision,action,NOW);}
    private Map<String,AttributeValue> initial(){var row=new HashMap<String,AttributeValue>();row.putAll(Map.of("pk",s("ACCOUNT#"+accountId),"sk",s("CONTROL"),"schemaVersion",n(1),"revision",n(1),"authGeneration",n(0),"accountState",s("ACTIVE"),"issuer",s(owner.issuer()),"subject",s("target-subject"),"username",s("Google_synthetic")));row.putAll(Map.of("admitted",AttributeValue.builder().bool(true).build(),"author",AttributeValue.builder().bool(false).build(),"managedTest",AttributeValue.builder().bool(false).build()));return row;}
    private Map<String,AttributeValue> marker(){return get(TABLES.administratorAuditEventsTableName(),"ACCOUNT#"+accountId,"REMOVED");}
    private Map<String,AttributeValue> intent(DynamoAccountControlStore.Command c){return get(TABLES.userAccountDetailsTableName(),"ACCOUNT_COMMAND#"+accountId,"OP#"+c.operationId());}
    private void patch(String field,AttributeValue value){var row=new HashMap<>(get(TABLES.userAccountDetailsTableName(),"ACCOUNT#"+accountId,"CONTROL"));row.put(field,value);put(TABLES.userAccountDetailsTableName(),row);}
    private long events(){return client.query(QueryRequest.builder().tableName(TABLES.administratorAuditEventsTableName()).consistentRead(true).keyConditionExpression("pk=:pk AND begins_with(sk,:sk)").expressionAttributeValues(Map.of(":pk",s("ACCOUNT#"+accountId),":sk",s("ADMISSION#"))).build()).count();}
    private Map<String,AttributeValue> get(String table,String pk,String sk){return client.getItem(GetItemRequest.builder().tableName(table).key(key(pk,sk)).consistentRead(true).build()).item();}
    private void put(String table,Map<String,AttributeValue> item){client.putItem(PutItemRequest.builder().tableName(table).item(item).build());}
    private void delete(String table,String pk,String sk){client.deleteItem(DeleteItemRequest.builder().tableName(table).key(key(pk,sk)).build());}
    private static Map<String,AttributeValue> key(String pk,String sk){return Map.of("pk",s(pk),"sk",s(sk));}
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
    private interface Hook{void call(String name,Object[] args);}
    private DynamoDbClient proxy(Hook before,Hook after){return (DynamoDbClient)Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),new Class<?>[]{DynamoDbClient.class},(p,method,args)->{
        before.call(method.getName(),args);
        try{Object result=method.invoke(client,args);after.call(method.getName(),args);return result;}catch(InvocationTargetException failure){throw failure.getCause();}
    });}
}
