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
class DynamoStaffAliasDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("prod");
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DynamoStaffAliasStore store = new DynamoStaffAliasStore(client, TABLES, CLOCK);
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
        for(String role:List.of("co-author","admin","reviewer","tester","learner")) seed(role,0,0);
    }
    @AfterEach void removeOwnedTables() {for(String table:tables())client.deleteTable(DeleteTableRequest.builder().tableName(table).build());}
    @AfterAll static void close() {if(client!=null)client.close();}

    @Test void permanentAssignmentsKeepIndependentRoleSequencesAndSurviveAccountErasure() {
        var first=store.assign(accountId,"co-author");
        assertThat(first).isEqualTo(new DynamoStaffAliasStore.Assignment(accountId,"co-author",1,NOW));
        assertThat(store.assign(UUID.randomUUID(),"co-author").number()).isEqualTo(2);
        for(String role:List.of("admin","reviewer","tester","learner")) assertThat(store.assign(accountId,role).number()).isEqualTo(1);
        // Ordinary account payload removal cannot remove alias partitions in the audit table.
        put(TABLES.userAccountDetailsTableName(),Map.of("pk",s("ACCOUNT#"+accountId),"sk",s("PROFILE")));
        delete(TABLES.userAccountDetailsTableName(),"ACCOUNT#"+accountId,"PROFILE");
        assertThat(store.assign(accountId,"co-author")).isEqualTo(first);
        assertThat(counter("co-author").get("lastAssigned").n()).isEqualTo("2");
        assertThat(store.read(UUID.randomUUID(),"admin")).isEmpty();
    }

    @Test void concurrentSameAccountRequestsReturnOneDurableNumber() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(8)) {
            List<Future<DynamoStaffAliasStore.Assignment>> results=new ArrayList<>();
            for(int i=0;i<8;i++)results.add(executor.submit(()->{start.await();return store.assign(accountId,"admin");}));
            start.countDown();
            for(var result:results)assertThat(result.get(20,TimeUnit.SECONDS).number()).isEqualTo(1);
        }
        assertThat(counter("admin").get("lastAssigned").n()).isEqualTo("1");
        assertThat(query("STAFF_ROLE#admin").size()).isEqualTo(2); // initialization + one claim
    }

    @Test void differentAccountsContendWithoutSharingNumbers() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<4;i++)results.add(executor.submit(()->{start.await();return store.assign(UUID.randomUUID(),"reviewer").number();}));
            start.countDown();Set<Integer> numbers=new HashSet<>();
            for(var result:results)numbers.add(result.get(20,TimeUnit.SECONDS));
            assertThat(numbers).containsExactlyInAnyOrder(1,2,3,4);
        }
        assertThat(counter("reviewer").get("lastAssigned").n()).isEqualTo("4");
    }

    @Test void lostSuccessfulReplyReconcilesImmutableAssignmentWithoutAdvancingAgain() {
        AtomicBoolean once=new AtomicBoolean(true);
        var uncertain=new DynamoStaffAliasStore(proxy((name,args)->{},(name,args)->{
            if(name.equals("executeTransaction")&&once.getAndSet(false))throw SdkClientException.create("lost reply");
        }),TABLES,CLOCK);
        assertThat(uncertain.assign(accountId,"learner").number()).isEqualTo(1);
        assertThat(store.assign(accountId,"learner").number()).isEqualTo(1);
        assertThat(counter("learner").get("lastAssigned").n()).isEqualTo("1");
    }

    @Test void counterRaceReReadsBeforeRetryAndCannotSkipOrDuplicateClaims() {
        AtomicBoolean once=new AtomicBoolean(true);AtomicInteger waits=new AtomicInteger();
        var competing=new DynamoStaffAliasStore(proxy((name,args)->{
            if(name.equals("executeTransaction")&&once.getAndSet(false))store.assign(UUID.randomUUID(),"admin");
        },(name,args)->{}),TABLES,CLOCK,attempt->waits.incrementAndGet());
        assertThat(competing.assign(accountId,"admin").number()).isEqualTo(2);assertThat(waits).hasValue(1);
        assertThat(counter("admin").get("revision").n()).isEqualTo("2");
    }

    @Test void rolledBackCounterCannotReuseRetainedClaimAndEveryFailedAttemptIsAtomic() {
        UUID old=UUID.randomUUID();store.assign(old,"admin");seed("admin",0,0);
        AtomicInteger waits=new AtomicInteger();AtomicInteger writes=new AtomicInteger();
        var corrupt=new DynamoStaffAliasStore(proxy((name,args)->{if(name.equals("executeTransaction"))writes.incrementAndGet();},(name,args)->{}),TABLES,CLOCK,attempt->waits.incrementAndGet());
        assertThatThrownBy(()->corrupt.assign(accountId,"admin")).isInstanceOf(TransactionCanceledException.class);
        assertThat(writes).hasValue(1);assertThat(waits).hasValue(0);
        assertThat(counter("admin").get("lastAssigned").n()).isEqualTo("0");
        assertThat(store.read(accountId,"admin")).isEmpty();assertThat(store.read(old,"admin").orElseThrow().number()).isEqualTo(1);
    }

    @Test void missingCounterAndBootstrapMismatchFailClosedButExistingAssignmentRemainsReadable() {
        var existing=store.assign(accountId,"admin");delete(TABLES.userAccountDetailsTableName(),"STAFF_ROLE#admin","COUNTER");
        assertThat(store.assign(accountId,"admin")).isEqualTo(existing);
        assertThatIllegalStateException().isThrownBy(()->store.assign(UUID.randomUUID(),"admin"));
        delete(TABLES.administratorAuditEventsTableName(),"STAFF_ROLE#tester","INITIALIZED");
        assertThatIllegalStateException().isThrownBy(()->store.assign(accountId,"tester"));
        put(TABLES.administratorAuditEventsTableName(),Map.of("pk",s("STAFF_ROLE#learner"),"sk",s("INITIALIZED"),"schemaVersion",n(1),"initializationId",s(UUID.randomUUID().toString())));
        assertThatIllegalStateException().isThrownBy(()->store.assign(accountId,"learner"));
        assertThat(store.read(accountId,"learner")).isEmpty();
    }

    @Test void integerLimitsAndMalformedCountersCannotAllocate() {
        seed("admin",Integer.MAX_VALUE-1,7);
        assertThat(store.assign(accountId,"admin").number()).isEqualTo(Integer.MAX_VALUE);
        assertThatIllegalStateException().isThrownBy(()->store.assign(UUID.randomUUID(),"admin"));
        for(var value:List.of(n(-1),n(Integer.MAX_VALUE),s("broken"),AttributeValue.builder().n("1.5").build())) {
            patchCounter("tester","lastAssigned",value);
            assertThatIllegalStateException().isThrownBy(()->store.assign(accountId,"tester"));
        }
        seed("tester",0,Long.MAX_VALUE);assertThatIllegalStateException().isThrownBy(()->store.assign(accountId,"tester"));
        seed("tester",0,0);patchCounter("tester","revision",null);assertThatIllegalStateException().isThrownBy(()->store.assign(accountId,"tester"));
        for(String invalid:List.of("bad","1-1-1-1-1",initializationId.toString().toUpperCase(Locale.ROOT))) {
            patchCounter("learner","initializationId",s(invalid));assertThatIllegalStateException().isThrownBy(()->store.assign(accountId,"learner"));
        }
    }

    @Test void malformedAssignmentsAndMissingOrForeignClaimsFailClosed() {
        store.assign(accountId,"admin");String pk="STAFF_ALIAS#"+accountId;
        var original=get(TABLES.administratorAuditEventsTableName(),pk,"ROLE#admin");
        for(String field:List.of("schemaVersion","number","assignedAt")) {
            var item=new HashMap<>(original);item.remove(field);put(TABLES.administratorAuditEventsTableName(),item);
            assertThatIllegalStateException().isThrownBy(()->store.read(accountId,"admin"));
        }
        for(String value:List.of("invalid","1969-12-31T00:00:00Z","+10000-01-01T00:00:00Z")) {
            var item=new HashMap<>(original);item.put("assignedAt",s(value));put(TABLES.administratorAuditEventsTableName(),item);
            assertThatIllegalStateException().isThrownBy(()->store.read(accountId,"admin"));
        }
        put(TABLES.administratorAuditEventsTableName(),original);
        String claimPk="STAFF_ROLE#admin",claimSk="NUMBER#0000000001";
        var claim=new HashMap<>(get(TABLES.administratorAuditEventsTableName(),claimPk,claimSk));
        claim.put("accountId",s(UUID.randomUUID().toString()));put(TABLES.administratorAuditEventsTableName(),claim);
        assertThatIllegalStateException().isThrownBy(()->store.assign(accountId,"admin"));
        claim.put("accountId",s(accountId.toString()));claim.put("assignedAt",s(NOW.plusSeconds(1).toString()));put(TABLES.administratorAuditEventsTableName(),claim);
        assertThatIllegalStateException().isThrownBy(()->store.read(accountId,"admin"));
        delete(TABLES.administratorAuditEventsTableName(),claimPk,claimSk);
        assertThatIllegalStateException().isThrownBy(()->store.read(accountId,"admin"));
    }

    @Test void unavailableWriteDoesNotInventAssignmentAndCallerCanRetrySameIdentity() {
        var offline=new DynamoStaffAliasStore(proxy((name,args)->{
            if(name.equals("executeTransaction"))throw SdkClientException.create("before send");
        },(name,args)->{}),TABLES,CLOCK);
        assertThatThrownBy(()->offline.assign(accountId,"tester")).isInstanceOf(SdkClientException.class);
        assertThat(counter("tester").get("lastAssigned").n()).isEqualTo("0");
        assertThat(store.assign(accountId,"tester").number()).isEqualTo(1);
    }

    @Test void unreadableReconciliationPreservesOriginalFailureAndRetryRecovers() {
        AtomicBoolean committed=new AtomicBoolean();
        var uncertain=new DynamoStaffAliasStore(proxy((name,args)->{
            if(name.equals("getItem")&&committed.get())throw SdkClientException.create("read unavailable");
        },(name,args)->{
            if(name.equals("executeTransaction")){committed.set(true);throw SdkClientException.create("lost reply");}
        }),TABLES,CLOCK);
        assertThatThrownBy(()->uncertain.assign(accountId,"tester")).isInstanceOf(SdkClientException.class)
                .hasMessage("lost reply").satisfies(error->assertThat(error.getSuppressed()).hasSize(1));
        assertThat(store.assign(accountId,"tester").number()).isEqualTo(1);
    }

    @Test void successfulAcknowledgmentWithoutAssignmentIsNotProofOfAllocation() {
        var damaged=new DynamoStaffAliasStore(proxy((name,args)->{},(name,args)->{
            if(name.equals("executeTransaction"))delete(TABLES.administratorAuditEventsTableName(),"STAFF_ALIAS#"+accountId,"ROLE#tester");
        }),TABLES,CLOCK);
        assertThatIllegalStateException().isThrownBy(()->damaged.assign(accountId,"tester"))
                .withMessageContaining("no durable assignment");
        assertThat(counter("tester").get("lastAssigned").n()).isEqualTo("1");
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
