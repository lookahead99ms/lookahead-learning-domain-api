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
class DynamoStaffAliasVerificationDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("prod");
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DynamoStaffAliasStore store = new DynamoStaffAliasStore(client, TABLES, CLOCK);
    private final DynamoStaffAliasVerifier verifier = new DynamoStaffAliasVerifier(client, TABLES);
    private final DynamoStaffAliasVerifier.Limits limits = new DynamoStaffAliasVerifier.Limits(2, 100, 100);
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

    @Test void completePairsAcrossManyPagesPassWithoutChangingData() {
        for(String role:List.of("co-author","admin","reviewer","tester","learner"))store.assign(accountId,role);
        store.assign(UUID.randomUUID(),"admin");
        put(TABLES.administratorAuditEventsTableName(),Map.of("pk",s("ACCOUNT#unrelated"),"sk",s("EVENT#one")));
        var before=counter("admin");var result=verifier.verify(limits);
        assertThat(result.assignments()).isEqualTo(6);assertThat(result.pages()).isGreaterThan(1);
        assertThat(result.assignedPerRole()).containsEntry("admin",2).containsEntry("learner",1);
        assertThat(counter("admin")).isEqualTo(before);assertThat(store.assign(accountId,"admin").number()).isEqualTo(1);
        assertThatThrownBy(()->result.assignedPerRole().put("admin",9)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void missingAliasWithRetainedClaimCannotPassRestoreVerification() {
        store.assign(accountId,"admin");
        delete(TABLES.administratorAuditEventsTableName(),"STAFF_ALIAS#"+accountId,"ROLE#admin");
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("no matching alias");
        assertThat(query("STAFF_ROLE#admin")).hasSize(2); // inspector does not repair or delete the retained claim
    }

    @Test void missingClaimAndWrongAccountOrTimestampAreRejected() {
        store.assign(accountId,"admin");String pk="STAFF_ROLE#admin",sk="NUMBER#0000000001";
        var claim=get(TABLES.administratorAuditEventsTableName(),pk,sk);delete(TABLES.administratorAuditEventsTableName(),pk,sk);
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("no matching number claim");
        var changed=new HashMap<>(claim);changed.put("accountId",s(UUID.randomUUID().toString()));put(TABLES.administratorAuditEventsTableName(),changed);
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits));
        changed=new HashMap<>(claim);changed.put("assignedAt",s(NOW.plusSeconds(1).toString()));put(TABLES.administratorAuditEventsTableName(),changed);
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits));
        put(TABLES.administratorAuditEventsTableName(),claim);assertThat(verifier.verify(limits).assignments()).isEqualTo(1);
    }

    @Test void twoNumbersForOneAccountRoleAndBehindCountersFail() {
        store.assign(accountId,"admin");
        var extra=new HashMap<>(get(TABLES.administratorAuditEventsTableName(),"STAFF_ROLE#admin","NUMBER#0000000001"));
        extra.put("sk",s("NUMBER#0000000002"));put(TABLES.administratorAuditEventsTableName(),extra);seed("admin",2,2);
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("no matching alias");
        delete(TABLES.administratorAuditEventsTableName(),"STAFF_ROLE#admin","NUMBER#0000000002");seed("admin",0,0);
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("behind assigned");
        seed("admin",Integer.MAX_VALUE,Long.MAX_VALUE);
        assertThat(verifier.verify(limits).assignments()).isEqualTo(1); // exhausted namespaces can be intact, though they cannot allocate
    }

    @Test void missingCounterAndMissingOrMismatchedInitializationBlockReadiness() {
        delete(TABLES.userAccountDetailsTableName(),"STAFF_ROLE#admin","COUNTER");
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits));seed("admin",0,0);
        delete(TABLES.administratorAuditEventsTableName(),"STAFF_ROLE#admin","INITIALIZED");
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("initialization");
        put(TABLES.administratorAuditEventsTableName(),Map.of("pk",s("STAFF_ROLE#admin"),"sk",s("INITIALIZED"),"schemaVersion",n(1),"initializationId",s(UUID.randomUUID().toString())));
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("initialization");
    }

    @Test void boundedPageAndAssignmentBudgetsNeverReturnPartialSuccess() {
        store.assign(accountId,"admin");store.assign(UUID.randomUUID(),"tester");
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(new DynamoStaffAliasVerifier.Limits(1,1,100))).withMessageContaining("page budget");
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(new DynamoStaffAliasVerifier.Limits(100,100,1))).withMessageContaining("Assignment budget");
        assertThat(verifier.verify(limits).assignments()).isEqualTo(2);
    }

    @Test void allocationDuringScanAndInterruptedScanNeverCertifyTheTarget() {
        AtomicBoolean once=new AtomicBoolean(true);
        var changing=new DynamoStaffAliasVerifier(proxy((name,args)->{},(name,args)->{
            if(name.equals("scan")&&once.getAndSet(false))store.assign(accountId,"admin");
        }),TABLES);
        assertThatIllegalStateException().isThrownBy(()->changing.verify(limits)).withMessageContaining("changed during");
        var unavailable=new DynamoStaffAliasVerifier(proxy((name,args)->{
            if(name.equals("scan"))throw SdkClientException.create("scan interrupted");
        },(name,args)->{}),TABLES);
        assertThatThrownBy(()->unavailable.verify(limits)).isInstanceOf(SdkClientException.class);
        assertThat(verifier.verify(limits).assignments()).isEqualTo(1);
    }

    @Test void initializedEmptyNamespacePassesAndUnknownAliasNamespacesFail() {
        assertThat(verifier.verify(limits).assignments()).isZero();
        put(TABLES.administratorAuditEventsTableName(),Map.of("pk",s("STAFF_ROLE#owner"),"sk",s("INITIALIZED"),"schemaVersion",n(1),"initializationId",s(initializationId.toString())));
        assertThatIllegalStateException().isThrownBy(()->verifier.verify(limits)).withMessageContaining("Unsupported");
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
