package com.lookahead.domain.persistence.dynamodb;

import com.lookahead.domain.LogCapture;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;

/** Local raw writes simulate the future migrator and corrupt restore. They do not prove IAM enforcement. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoReadinessDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNamesTest.names("dev");
    private static final List<String> NAMES = List.of(TABLES.userAccountDetailsTableName(),
            TABLES.administratorAuditEventsTableName(), TABLES.userStudyPlanDetailsTableName(), TABLES.protectedOwnerIdentityTableName());
    private final Set<String> created = new HashSet<>();
    private DynamoReadiness readiness(int minimum) {
        return new DynamoReadiness(client, TABLES, new DynamoDataRequirement(
                java.util.stream.IntStream.rangeClosed(1, minimum).mapToObj(i -> "step-" + i).toList()), Clock.systemUTC());
    }

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() < 1024
                || endpoint.getPort() > 65535 || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !endpoint.getPath().isEmpty()) throw new IllegalArgumentException("Loopback fixture required");
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    @BeforeEach void createTables() {
        for (String name : NAMES) {
            // DLV-970: these cases need empty tables; drop any table an earlier class in this JVM left behind.
            try { client.deleteTable(DeleteTableRequest.builder().tableName(name).build()); } catch (ResourceNotFoundException absent) { /* fresh */ }
            client.createTable(CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
            created.add(name);
        }
    }
    @AfterEach void cleanup() { for (String name : created) client.deleteTable(DeleteTableRequest.builder().tableName(name).build()); }
    @AfterAll static void close() { if (client != null) client.close(); }
    private void put(Map<String, AttributeValue> item) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.protectedOwnerIdentityTableName()).item(item).build());
    }

    @Test void emptyStoreIsBehindThenSameAndHigherVersionsBecomeReadyWithoutReaderWrites() {
        var health = readiness(2);
        try (var logs = LogCapture.start("lookahead.health")) {
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(logs.messages()).anyMatch(message -> message.contains("data is at 0") && message.contains("needs 2"));
        }
        for (String version : List.of("1", "2", "3")) {
            put(DynamoDataVersionTest.head(version));
            assertThat(health.health().getStatus()).isEqualTo(version.equals("1") ? Status.DOWN : Status.UP);
            assertThat(new DynamoDataVersionReader(client, TABLES).currentVersion()).isEqualTo(Long.parseLong(version));
        }
        for (String name : NAMES) {
            assertThat(client.scan(ScanRequest.builder().tableName(name).consistentRead(true).build()).count())
                    .isEqualTo(name.equals(TABLES.protectedOwnerIdentityTableName()) ? 1 : 0);
        }
    }

    @Test void oldReleaseAcceptsNewDataWhileNewReleaseRefusesOldData() {
        put(DynamoDataVersionTest.head("2"));
        assertThat(readiness(1).health().getStatus()).isEqualTo(Status.UP);
        assertThat(readiness(2).health().getStatus()).isEqualTo(Status.UP);
        assertThat(readiness(3).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test void malformedHeadRefusesTrafficAndValidRestorationRecovers() {
        var health = readiness(1);
        var malformed = new HashMap<>(DynamoDataVersionTest.head("1"));
        malformed.put("version", DynamoDataVersionTest.s("private-secret"));
        put(malformed);
        try (var logs = LogCapture.start("lookahead.health")) {
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(LogCapture.keys(logs.only("readiness"))).containsEntry("reason", "STORE_UNAVAILABLE");
            logs.containsNone("private-secret");
        }
        put(DynamoDataVersionTest.head("1"));
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test void protectedOwnerBindingIsIndependentAndCannotStandInForTheLedger() {
        put(Map.of("pk", DynamoDataVersionTest.s("OWNER"), "sk", DynamoDataVersionTest.s("ROOT"),
                "schemaVersion", DynamoDataVersionTest.n("1"), "issuer", DynamoDataVersionTest.s("https://issuer.test"),
                "subject", DynamoDataVersionTest.s("owner-subject")));
        var owner = new ProtectedOwnerIdentityStore(client, TABLES);
        assertThat(owner.matchesVerifiedIdentity("https://issuer.test", "owner-subject")).isTrue();
        assertThat(readiness(1).health().getStatus()).isEqualTo(Status.DOWN);
        put(DynamoDataVersionTest.head("1"));
        assertThat(readiness(1).health().getStatus()).isEqualTo(Status.UP);
        assertThat(owner.matchesVerifiedIdentity("https://issuer.test", "owner-subject")).isTrue();
    }

    @Test void physicalMissingTableFailsDespiteSufficientLedger() {
        put(DynamoDataVersionTest.head("99"));
        String missing = TABLES.userStudyPlanDetailsTableName();
        client.deleteTable(DeleteTableRequest.builder().tableName(missing).build()); created.remove(missing);
        try (var logs = LogCapture.start("lookahead.health")) {
            assertThat(readiness(1).health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(LogCapture.keys(logs.only("readiness"))).containsEntry("reason", "TABLE_MISSING");
            assertThat(logs.messages().getFirst()).contains(missing);
        }
    }

    @Test void absentProtectedTableIsMissingRatherThanVersionZero() {
        String missing = TABLES.protectedOwnerIdentityTableName();
        client.deleteTable(DeleteTableRequest.builder().tableName(missing).build()); created.remove(missing);
        try (var logs = LogCapture.start("lookahead.health")) {
            assertThat(readiness(1).health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(LogCapture.keys(logs.only("readiness"))).containsEntry("reason", "TABLE_MISSING");
        }
    }
}
