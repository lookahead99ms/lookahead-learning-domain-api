package com.lookahead.domain.review.platform;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;

/** DLV-970: ReviewRecordsDatabaseTest on DynamoDB Local: two counters numbering each kind from one, and append-only status history. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoReviewRecordsDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNames.local();
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    @TempDir Path directory;

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("Loopback fixture required");
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    @BeforeEach void prepareTables() {
        for (String name : TABLES.all()) com.lookahead.domain.review.DynamoAuthorReviewDatabaseTestSupport.createTable(client, name);
        com.lookahead.domain.review.DynamoAuthorReviewDatabaseTestSupport.purge(client, TABLES.userAccountDetailsTableName(), "SEQUENCE#");
        com.lookahead.domain.review.DynamoAuthorReviewDatabaseTestSupport.purge(client, TABLES.administratorAuditEventsTableName(), "RECORD#");
    }
    @AfterAll static void close() { if (client != null) client.close(); }

    @Test void countersNumberEachKindFromOneAndTheRecordStoreFormatsTheNextId() {
        var numbers = new ReviewRecordNumbers.Dynamo(client, TABLES);
        assertThat(numbers.next("review")).isEqualTo(1);
        assertThat(numbers.next("review")).isEqualTo(2);
        assertThat(numbers.next("request")).isEqualTo(1);
        var store = new RecordStore(new FileSystemPlatformReviewObjects(directory), "request", new tools.jackson.databind.json.JsonMapper(), numbers);
        assertThat(store.nextId()).isEqualTo("SUP-000002");
        var counter = client.getItem(GetItemRequest.builder().tableName(TABLES.userAccountDetailsTableName()).consistentRead(true)
                .key(DynamoItems.key("SEQUENCE#review-record", "COUNTER")).build()).item();
        assertThat(DynamoItems.number(counter, "lastAssigned")).isEqualTo(2);
        assertThat(DynamoItems.number(counter, "schemaVersion")).isEqualTo(1);
        // A counter that already holds a value (a later continuation step may seed the PostgreSQL sequence's last value) continues from it.
        client.putItem(PutItemRequest.builder().tableName(TABLES.userAccountDetailsTableName())
                .item(DynamoItems.item("SEQUENCE#support-request", "COUNTER", 1, Map.of("lastAssigned", DynamoItems.n(41)))).build());
        assertThat(numbers.next("request")).isEqualTo(42);
    }

    @Test void statusEventsAreAppendedInOrderWithTheActorOrSystemAndNeverThrow() {
        var clock = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var history = new ReviewStatusHistory.Dynamo(client, TABLES, new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clock.get(); }
        });
        UUID actor = UUID.randomUUID();
        history.record("REV-000001", "review", actor, "new", "accepted", true);
        clock.set(NOW.plusSeconds(1));
        history.record("REV-000001", "review", null, "accepted", "closed", false);
        history.record("DLV-42", "ticket", actor, null, "backlog", false);
        var rows = client.query(QueryRequest.builder().tableName(TABLES.administratorAuditEventsTableName()).consistentRead(true).keyConditionExpression("pk=:pk")
                .expressionAttributeValues(Map.of(":pk", DynamoItems.s(ReviewStatusHistory.Dynamo.partition("review", "REV-000001")))).build()).items();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> DynamoItems.string(row, "toStatus")).containsExactly("accepted", "closed");
        assertThat(rows).extracting(row -> DynamoItems.string(row, "actor")).containsExactly(actor.toString(), "system");
        assertThat(rows.get(0).get("sk").s()).startsWith("STATUS#" + DynamoItems.time(NOW) + "#");
        assertThat(DynamoItems.string(rows.get(0), "fromStatus")).isEqualTo("new");
        assertThat(rows.get(1)).doesNotContainKey("actorId");
        assertThat(DynamoItems.flag(rows.get(0), "noteSet")).isTrue();
        var ticket = client.query(QueryRequest.builder().tableName(TABLES.administratorAuditEventsTableName()).consistentRead(true).keyConditionExpression("pk=:pk")
                .expressionAttributeValues(Map.of(":pk", DynamoItems.s(ReviewStatusHistory.Dynamo.partition("ticket", "DLV-42")))).build()).items();
        assertThat(ticket).singleElement().satisfies(row -> { assertThat(row).doesNotContainKey("fromStatus"); assertThat(DynamoItems.string(row, "action")).isEqualTo("review.status"); });
        // A history that cannot be written (the table is gone) is logged and the call still returns.
        var unreachable = new ReviewStatusHistory.Dynamo(DynamoDbClient.builder().endpointOverride(URI.create("http://127.0.0.1:9")).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic"))).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.retryStrategy(software.amazon.awssdk.retries.DefaultRetryStrategy.doNotRetry())).build(), TABLES, Clock.systemUTC());
        try (var log = com.lookahead.domain.LogCapture.start()) {
            assertThatCode(() -> unreachable.record("DLV-43", "ticket", null, null, "backlog", false)).doesNotThrowAnyException();
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("platform-review.history"))).containsEntry("reason", "HISTORY_NOT_WRITTEN");
            log.containsNone("127.0.0.1:9");
        }
        assertThat(List.of(rows.size(), ticket.size())).containsExactly(2, 1);
    }
}
