package com.lookahead.domain.cloud;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * DLV-970 lane A database tests: the owned loopback DynamoDB Local fixture with the Local table names. Tables are
 * created only when absent and never deleted; each test class purges the key prefixes it writes.
 */
public final class DynamoLocalFixture {
    public static final DynamoTableNames TABLES = DynamoTableNames.local();
    private DynamoLocalFixture() {}

    public static DynamoDbClient connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("Loopback fixture required");
        var client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        for (String name : TABLES.all()) createTable(client, name);
        return client;
    }
    public static void createTable(DynamoDbClient client, String name) {
        try { client.describeTable(DescribeTableRequest.builder().tableName(name).build()); return; } catch (ResourceNotFoundException absent) { /* create it */ }
        client.createTable(CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
    }
    /** Deletes every item of {@code table} whose partition key starts with one of {@code prefixes}. */
    public static void purge(DynamoDbClient client, String table, String... prefixes) {
        for (String prefix : prefixes) {
            Map<String, AttributeValue> start = null;
            do {
                var request = ScanRequest.builder().tableName(table).consistentRead(true).filterExpression("begins_with(pk, :p)")
                        .expressionAttributeValues(Map.of(":p", AttributeValue.builder().s(prefix).build()));
                if (start != null) request.exclusiveStartKey(start);
                var scan = client.scan(request.build());
                for (var item : scan.items())
                    client.deleteItem(DeleteItemRequest.builder().tableName(table).key(Map.of("pk", item.get("pk"), "sk", item.get("sk"))).build());
                start = scan.hasLastEvaluatedKey() && !scan.lastEvaluatedKey().isEmpty() ? scan.lastEvaluatedKey() : null;
            } while (start != null);
        }
    }
    /** The owner binding the control store and the registry read ({@code OWNER / ROOT}); a raw fixture write, never runtime code. */
    public static void seedOwner(DynamoDbClient client, String issuer, String subject) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.protectedOwnerIdentityTableName()).item(Map.of(
                "pk", AttributeValue.builder().s("OWNER").build(), "sk", AttributeValue.builder().s("ROOT").build(),
                "schemaVersion", AttributeValue.builder().n("1").build(), "issuer", AttributeValue.builder().s(issuer).build(),
                "subject", AttributeValue.builder().s(subject).build())).build());
    }
    /** Every lane A prefix in the three writable tables. */
    public static void purgeLaneA(DynamoDbClient client) {
        purge(client, TABLES.userAccountDetailsTableName(), "ACCOUNT#", "IDENTITY#", "EMAIL#", "FAMILY#", "DIRECTORY#", "WAITING", "STAFFACCESS", "INVITE#", "MERGE#", "ACCOUNT_REMOVAL#", "ACCOUNT_COMMAND#", "WORK#ACCOUNT-ERASURE");
        purge(client, TABLES.administratorAuditEventsTableName(), "ACCOUNT#", "STAFF#", "STAFF_ALIAS#");
    }
}
