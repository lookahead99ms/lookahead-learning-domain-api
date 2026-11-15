package com.lookahead.domain.review;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Shared DynamoDB Local fixture steps for lane D's database tests: create a table only if absent, never delete it, purge one key prefix. */
public final class DynamoAuthorReviewDatabaseTestSupport {
    private DynamoAuthorReviewDatabaseTestSupport() { }
    public static void createTable(DynamoDbClient client, String name) {
        try { client.describeTable(DescribeTableRequest.builder().tableName(name).build()); return; } catch (ResourceNotFoundException absent) { /* create it */ }
        client.createTable(CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(), KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
    }
    public static void purge(DynamoDbClient client, String table, String prefix) {
        var scan = client.scan(ScanRequest.builder().tableName(table).consistentRead(true).filterExpression("begins_with(pk, :p)")
                .expressionAttributeValues(Map.of(":p", AttributeValue.builder().s(prefix).build())).build());
        for (var item : scan.items()) client.deleteItem(DeleteItemRequest.builder().tableName(table).key(Map.of("pk", item.get("pk"), "sk", item.get("sk"))).build());
    }
}
