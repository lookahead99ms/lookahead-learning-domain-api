package com.lookahead.domain.db.store.review;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.log.LogCapture;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** DLV-970: the review record numbers on DynamoDB Local: two counters numbering each kind from one, and append-only status history. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class PlatformReviewRecordNumberStoreDatabaseTest {
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
        for (String name : TABLES.all()) AuthorReviewStoreDatabaseTestSupport.createTable(client, name);
        AuthorReviewStoreDatabaseTestSupport.purge(client, TABLES.userAccountDetailsTableName(), "SEQUENCE#");
        AuthorReviewStoreDatabaseTestSupport.purge(client, TABLES.administratorAuditEventsTableName(), "RECORD#");
    }
    @AfterAll static void close() { if (client != null) client.close(); }

    @Test void countersNumberEachKindFromOneAndTheRecordStoreFormatsTheNextId() {
        var numbers = new PlatformReviewRecordNumberStore(client, TABLES);
        assertThat(numbers.next("review")).isEqualTo(1);
        assertThat(numbers.next("review")).isEqualTo(2);
        assertThat(numbers.next("request")).isEqualTo(1);
        var store = new PlatformReviewRecordStore(new PlatformReviewFileSystemObjectStore(directory), "request", new JsonMapper(), numbers);
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
        var clock = new AtomicReference<>(NOW);
        var history = new PlatformReviewStatusHistoryStore(client, TABLES, new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return clock.get(); }
        });
        UUID actor = UUID.randomUUID();
        history.record("REV-000001", "review", actor, "new", "accepted", true);
        clock.set(NOW.plusSeconds(1));
        history.record("REV-000001", "review", null, "accepted", "closed", false);
        history.record("DLV-42", "ticket", actor, null, "backlog", false);
        var rows = client.query(QueryRequest.builder().tableName(TABLES.administratorAuditEventsTableName()).consistentRead(true).keyConditionExpression("pk=:pk")
                .expressionAttributeValues(Map.of(":pk", DynamoItems.s(PlatformReviewStatusHistoryStore.partition("review", "REV-000001")))).build()).items();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> DynamoItems.string(row, "toStatus")).containsExactly("accepted", "closed");
        assertThat(rows).extracting(row -> DynamoItems.string(row, "actor")).containsExactly(actor.toString(), "system");
        assertThat(rows.get(0).get("sk").s()).startsWith("STATUS#" + DynamoItems.time(NOW) + "#");
        assertThat(DynamoItems.string(rows.get(0), "fromStatus")).isEqualTo("new");
        assertThat(rows.get(1)).doesNotContainKey("actorId");
        assertThat(DynamoItems.flag(rows.get(0), "noteSet")).isTrue();
        var ticket = client.query(QueryRequest.builder().tableName(TABLES.administratorAuditEventsTableName()).consistentRead(true).keyConditionExpression("pk=:pk")
                .expressionAttributeValues(Map.of(":pk", DynamoItems.s(PlatformReviewStatusHistoryStore.partition("ticket", "DLV-42")))).build()).items();
        assertThat(ticket).singleElement().satisfies(row -> { assertThat(row).doesNotContainKey("fromStatus"); assertThat(DynamoItems.string(row, "action")).isEqualTo("review.status"); });
        // A history that cannot be written (the store is unreachable) is the store's exception, with no line of its own; the services catch and log it (DLV-1016).
        var unreachable = new PlatformReviewStatusHistoryStore(DynamoDbClient.builder().endpointOverride(URI.create("http://127.0.0.1:9")).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic"))).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.retryStrategy(DefaultRetryStrategy.doNotRetry())).build(), TABLES, Clock.systemUTC());
        try (var log = LogCapture.start()) {
            assertThatThrownBy(() -> unreachable.record("DLV-43", "ticket", null, null, "backlog", false)).isInstanceOf(DynamoWrites.Unavailable.class);
            assertThat(log.lines("platform-review.history")).isEmpty();
            log.containsNone("127.0.0.1:9");
        }
        assertThat(List.of(rows.size(), ticket.size())).containsExactly(2, 1);
    }
}
