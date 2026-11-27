package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.ObjectMapper;

/** Lane C database tests: the owned loopback DynamoDB Local fixture, the catalog seed (the proposed data step) and prefix purges. */
public final class MembershipStoreLocalFixture {
    private MembershipStoreLocalFixture() {}
    public static final DynamoTableNames TABLES = DynamoTableNames.local();
    public static DynamoDbClient connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("Loopback fixture required");
        return DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    /** Creates the four tables when absent; never deletes them. */
    public static void ensureTables(DynamoDbClient client) {
        for (String name : TABLES.all()) {
            try { client.describeTable(DescribeTableRequest.builder().tableName(name).build()); continue; } catch (ResourceNotFoundException absent) { /* create */ }
            client.createTable(CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(), KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
        }
    }
    public static void purge(DynamoDbClient client, String table, String prefix) {
        Map<String, AttributeValue> start = null;
        do {
            var scan = ScanRequest.builder().tableName(table).consistentRead(true).filterExpression("begins_with(pk, :p)")
                    .expressionAttributeValues(Map.of(":p", DynamoItems.s(prefix)));
            if (start != null) scan.exclusiveStartKey(start);
            var page = client.scan(scan.build());
            for (var item : page.items()) client.deleteItem(DeleteItemRequest.builder().tableName(table).key(Map.of("pk", item.get("pk"), "sk", item.get("sk"))).build());
            start = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (start != null);
    }
    /** Every lane C family except per-account items, which tests create under fresh ids (or purge by id). */
    public static void purgeLaneC(DynamoDbClient client) {
        for (String prefix : List.of("CATALOG", "SALES", "PURCHASE#", "PROVIDEREVENT#", "MOCKPAYMENT", "TESTACCOUNTS#", "CREATOR#")) purge(client, TABLES.userAccountDetailsTableName(), prefix);
        for (String prefix : List.of("MEMBERSHIP#", "TEST#")) purge(client, TABLES.administratorAuditEventsTableName(), prefix);
    }
    public static void purgeAccount(DynamoDbClient client, UUID account) {
        purge(client, TABLES.userAccountDetailsTableName(), DynamoItems.account(account));
    }
    /** The catalog data step 3 ({@link MembershipCatalogSeed}): ten {@code CATALOG / PLAN#id} items at revision 1 and {@code CATALOG / META} at revision 1. */
    public static void seedCatalog(DynamoDbClient client, ObjectMapper json, Instant at) {
        String table = TABLES.userAccountDetailsTableName();
        for (var plan : MembershipCatalogSeed.PLANS) client.putItem(PutItemRequest.builder().tableName(table).item(MembershipCatalogSeed.planItem(json, plan, at)).build());
        client.putItem(PutItemRequest.builder().tableName(table).item(MembershipCatalogSeed.metaItem(at)).build());
    }
    /** An account subject: {@code ACCOUNT#id / META} at revision 1, in lane A's shape (revision, lastPlanNumber, createdAt). */
    public static void seedAccount(DynamoDbClient client, UUID id, Instant at) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).item(DynamoItems.item(DynamoItems.account(id), MembershipItems.META_SK, MembershipItems.SCHEMA,
                Map.of("revision", DynamoItems.n(1), "lastPlanNumber", DynamoItems.n(0), "createdAt", DynamoItems.s(at.toString())))).build());
    }
    public static Map<String, AttributeValue> item(DynamoDbClient client, String table, String pk, String sk) {
        return client.getItem(GetItemRequest.builder().tableName(table).consistentRead(true).key(DynamoItems.key(pk, sk)).build()).item();
    }
}
