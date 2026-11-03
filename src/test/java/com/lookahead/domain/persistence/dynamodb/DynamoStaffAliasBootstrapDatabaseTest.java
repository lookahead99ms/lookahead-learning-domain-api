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
class DynamoStaffAliasBootstrapDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("prod");
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DynamoStaffAliasStore store = new DynamoStaffAliasStore(client, TABLES, CLOCK);
    private final DynamoStaffAliasBootstrap bootstrap = new DynamoStaffAliasBootstrap(client, TABLES);
    private final UUID accountId = UUID.randomUUID();
    private final UUID initializationId = UUID.randomUUID();

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort()<1024
                || endpoint.getPort()>65535 || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null
                || endpoint.getFragment()!=null || !endpoint.getPath().isEmpty()) throw new IllegalArgumentException("Loopback fixture required");
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    private String[] tables() {return new String[]{TABLES.userAccountDetailsTableName(),TABLES.administratorAuditEventsTableName()};}
    @BeforeEach void createTables() {
        for (String table : tables()) client.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
    }
    @AfterEach void removeOwnedTables() {for(String table:tables())client.deleteTable(DeleteTableRequest.builder().tableName(table).build());}
    @AfterAll static void close() {if(client!=null)client.close();}

    @Test void tenRecordsInitializeTogetherAndRetryNeverResetsAllocatedNumbers() {
        assertThat(bootstrap.initialize(initializationId,NOW,100)).isFalse();
        assertThat(new DynamoStaffAliasVerifier(client,TABLES).verify(new DynamoStaffAliasVerifier.Limits(10,100,100)).assignments()).isZero();
        assertThat(store.assign(accountId,"admin").number()).isEqualTo(1);
        assertThat(bootstrap.initialize(initializationId,NOW,100)).isTrue();
        assertThat(store.assign(UUID.randomUUID(),"admin").number()).isEqualTo(2);
        assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(UUID.randomUUID(),NOW,100));
        assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(initializationId,NOW.plusSeconds(1),100));
        assertThat(counter("admin").get("lastAssigned").n()).isEqualTo("2");
    }

    @Test void loneCounterMarkerOrLegacyFactPreventsEmptyBootstrap() {
        for(String table:tables()) {
            String sk=table.equals(TABLES.userAccountDetailsTableName())?"COUNTER":"INITIALIZED";
            var item=new HashMap<>(Map.of("pk",s("STAFF_ROLE#admin"),"sk",s(sk),"schemaVersion",n(1),"initializationId",s(initializationId.toString()),"initializedAt",s(NOW.toString()),"lastAssigned",n(0),"revision",n(0)));
            put(table,item);assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(initializationId,NOW,100)).withMessageContaining("Partial");
            delete(table,"STAFF_ROLE#admin",sk);
        }
        for(String pk:List.of("STAFF_ALIAS#"+accountId,"STAFF_ROLE#admin")) {
            put(TABLES.administratorAuditEventsTableName(),Map.of("pk",s(pk),"sk",s("legacy")));
            assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(initializationId,NOW,100)).withMessageContaining("not empty");
            delete(TABLES.administratorAuditEventsTableName(),pk,"legacy");
        }
        assertThat(counter("admin")).isEmpty();
    }

    @Test void aLateCounterCollisionRollsBackAllOtherInserts() {
        AtomicBoolean once=new AtomicBoolean(true);
        var conflicting=new DynamoStaffAliasBootstrap(proxy((name,args)->{
            if(name.equals("executeTransaction")&&once.getAndSet(false))put(TABLES.userAccountDetailsTableName(),Map.of("pk",s("STAFF_ROLE#admin"),"sk",s("COUNTER"),"schemaVersion",n(1),"initializationId",s(initializationId.toString()),"lastAssigned",n(9),"revision",n(3)));
        },(name,args)->{}),TABLES);
        assertThatThrownBy(()->conflicting.initialize(initializationId,NOW,100)).isInstanceOf(TransactionCanceledException.class);
        assertThat(counter("admin").get("lastAssigned").n()).isEqualTo("9");
        for(String role:List.of("co-author","reviewer","tester","learner"))assertThat(counter(role)).isEmpty();
        for(String role:List.of("co-author","admin","reviewer","tester","learner"))assertThat(query("STAFF_ROLE#"+role)).isEmpty();
    }

    @Test void lostSuccessfulReplyReconcilesAllTenRecords() {
        var uncertain=new DynamoStaffAliasBootstrap(proxy((name,args)->{},(name,args)->{
            if(name.equals("executeTransaction"))throw SdkClientException.create("lost reply");
        }),TABLES);
        assertThat(uncertain.initialize(initializationId,NOW,100)).isTrue();
        assertThat(bootstrap.initialize(initializationId,NOW,100)).isTrue();
        assertThat(store.assign(accountId,"learner").number()).isEqualTo(1);
    }

    @Test void preWriteFailureAndUnreadableReplyNeverInventCompletion() {
        var offline=new DynamoStaffAliasBootstrap(proxy((name,args)->{
            if(name.equals("executeTransaction"))throw SdkClientException.create("before write");
        },(name,args)->{}),TABLES);
        assertThatThrownBy(()->offline.initialize(initializationId,NOW,100)).isInstanceOf(SdkClientException.class);
        assertThat(counter("admin")).isEmpty();
        AtomicBoolean committed=new AtomicBoolean();
        var uncertain=new DynamoStaffAliasBootstrap(proxy((name,args)->{
            if(name.equals("transactGetItems")&&committed.get())throw SdkClientException.create("read lost");
        },(name,args)->{if(name.equals("executeTransaction")){committed.set(true);throw SdkClientException.create("write reply lost");}}),TABLES);
        assertThatThrownBy(()->uncertain.initialize(initializationId,NOW,100)).hasMessage("write reply lost")
                .satisfies(error->assertThat(error.getSuppressed()).hasSize(1));
        assertThat(bootstrap.initialize(initializationId,NOW,100)).isTrue();
    }

    @Test void damagedExistingInitializationIsNotAutomaticallyRepaired() {
        bootstrap.initialize(initializationId,NOW,100);
        delete(TABLES.administratorAuditEventsTableName(),"STAFF_ROLE#admin","INITIALIZED");
        assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(initializationId,NOW,100)).withMessageContaining("Partial");
        assertThat(get(TABLES.administratorAuditEventsTableName(),"STAFF_ROLE#admin","INITIALIZED")).isEmpty();
    }

    @Test void filteredEmptyPagesRequireCompleteScanBudget() {
        for(int i=0;i<101;i++)put(TABLES.administratorAuditEventsTableName(),Map.of("pk",s("ACCOUNT#"+i),"sk",s("EVENT")));
        assertThatIllegalStateException().isThrownBy(()->bootstrap.initialize(initializationId,NOW,1)).withMessageContaining("budget");
        assertThat(counter("admin")).isEmpty();assertThat(bootstrap.initialize(initializationId,NOW,10)).isFalse();
    }

    @Test void simultaneousSameInitializationTransactionsConverge() throws Exception {
        CyclicBarrier barrier=new CyclicBarrier(2);
        var competing=new DynamoStaffAliasBootstrap(proxy((name,args)->{
            if(name.equals("executeTransaction"))try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception failure){throw new RuntimeException(failure);}
        },(name,args)->{}),TABLES);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->competing.initialize(initializationId,NOW,100));
            var b=executor.submit(()->competing.initialize(initializationId,NOW,100));
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(false,true);
        }
        assertThat(bootstrap.initialize(initializationId,NOW,100)).isTrue();
    }

    private void seed(String role,long last,long revision) {
        put(TABLES.userAccountDetailsTableName(),Map.of("pk",s("STAFF_ROLE#"+role),"sk",s("COUNTER"),"schemaVersion",n(1),"lastAssigned",n(last),"revision",n(revision),"initializationId",s(initializationId.toString())));
        put(TABLES.administratorAuditEventsTableName(),Map.of("pk",s("STAFF_ROLE#"+role),"sk",s("INITIALIZED"),"schemaVersion",n(1),"initializationId",s(initializationId.toString())));
    }
    private Map<String,AttributeValue> counter(String role){return get(TABLES.userAccountDetailsTableName(),"STAFF_ROLE#"+role,"COUNTER");}
    private void patchCounter(String role,String field,AttributeValue value){var item=new HashMap<>(counter(role));if(value==null)item.remove(field);else item.put(field,value);put(TABLES.userAccountDetailsTableName(),item);}
    private List<Map<String,AttributeValue>> query(String pk){return client.query(QueryRequest.builder().tableName(TABLES.administratorAuditEventsTableName()).keyConditionExpression("pk=:pk").expressionAttributeValues(Map.of(":pk",s(pk))).consistentRead(true).build()).items();}
    private Map<String,AttributeValue> get(String table,String pk,String sk){return client.getItem(GetItemRequest.builder().tableName(table).key(key(pk,sk)).consistentRead(true).build()).item();}
    private void put(String table,Map<String,AttributeValue> item){client.putItem(PutItemRequest.builder().tableName(table).item(item).build());}
    private void delete(String table,String pk,String sk){client.deleteItem(DeleteItemRequest.builder().tableName(table).key(key(pk,sk)).build());}
    private static Map<String,AttributeValue> key(String pk,String sk){return Map.of("pk",s(pk),"sk",s(sk));}
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
    private interface Hook{void call(String name,Object[] args);}
    private DynamoDbClient proxy(Hook before,Hook after){return (DynamoDbClient)Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),new Class<?>[]{DynamoDbClient.class},(p,method,args)->{
        before.call(method.getName(),args);
        try {Object result=method.invoke(client,args);after.call(method.getName(),args);return result;}
        catch(InvocationTargetException failure){throw failure.getCause();}
    });}
}
