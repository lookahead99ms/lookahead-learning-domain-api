package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.LogCapture;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static com.lookahead.domain.cloud.CloudAccountItems.account;
import static com.lookahead.domain.cloud.CloudAccountItems.attrs;
import static com.lookahead.domain.cloud.CloudAccountItems.get;
import static com.lookahead.domain.cloud.CloudAccountItems.item;
import static com.lookahead.domain.cloud.CloudAccountItems.n;
import static com.lookahead.domain.cloud.CloudAccountItems.s;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-970 step 5 after the PostgreSQL retirement (2026-11-17): the step reads nothing but its own result marker. A store
 * without one gets the empty marker (a fresh local store, PROD); a store that carries one (DEV, imported in run
 * 44162520740) is left exactly as it is; verify still checks every account the marker names.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class AccountAccessImportDatabaseTest {
    private static DynamoDbClient client;
    private static final DynamoTableNames TABLES = DynamoTableNames.local();
    private final AccountAccessImport step = new AccountAccessImport();

    @BeforeAll static void connect() {
        URI endpoint = URI.create(System.getenv("DLV970_DYNAMODB_TEST_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("Loopback fixture required");
        client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    @AfterAll static void close() { if (client != null) client.close(); }

    /** The tables exist (other classes may have created them), the marker is gone and steps 2 and 4 hold (verify checks their counters). */
    @BeforeEach void prepare() {
        for (String name : TABLES.all()) createTable(name);
        purge(TABLES.userAccountDetailsTableName(), "IMPORT#");
        purge(TABLES.userAccountDetailsTableName(), "ACCOUNT#");
        DomainDynamoCatalog.catalog().step(2).apply(client, TABLES);
        DomainDynamoCatalog.catalog().step(4).apply(client, TABLES);
    }

    @Test void theStepKeepsItsRecordedIdentity() {
        assertThat(step.version()).isEqualTo(5);
        assertThat(step.name()).isEqualTo("account-access-import");
        assertThat(DomainDynamoCatalog.catalog().step(5)).isInstanceOf(AccountAccessImport.class);
        assertThat(step.definition()).contains("PostgreSQL platform database").endsWith("A local store without a PostgreSQL source imports nothing.");
    }

    @Test void aStoreWithoutTheMarkerGetsTheEmptyResultAndVerifies() {
        assertThatThrownBy(() -> step.verify(client, TABLES)).hasMessage("Account import result is missing");
        try (var logs = LogCapture.start("lookahead.migration")) {
            step.apply(client, TABLES);
            assertThat(logs.messages()).containsExactly("Account import: no PostgreSQL source (retired 2026-11-17 after the DEV import, run 44162520740); the empty result marker is written");
        }
        var marker = get(client, TABLES.userAccountDetailsTableName(), AccountAccessImport.MARKER_PK, AccountAccessImport.MARKER_SK);
        assertThat(marker).isNotEmpty();
        assertThat(DynamoItems.stringSet(marker, "accountIds")).isEmpty();
        assertThat(DynamoItems.number(marker, "importedAccounts")).isZero();
        assertThat(DynamoItems.number(marker, "removedWaiting")).isZero();
        assertThat(DynamoItems.string(marker, "source")).isEqualTo("postgresql");
        assertThat(marker.get("importedAt").s()).isNotBlank();
        assertThatCode(() -> step.verify(client, TABLES)).doesNotThrowAnyException();
        assertThat(AccountAccessImport.importedAccountIds(client, TABLES)).isEmpty();
    }

    @Test void aStoreThatCarriesTheDevMarkerIsLeftAsItIsAndItsAccountsAreVerified() {
        UUID imported = UUID.randomUUID();
        String table = TABLES.userAccountDetailsTableName();
        client.putItem(PutItemRequest.builder().tableName(table).item(item(AccountAccessImport.MARKER_PK, AccountAccessImport.MARKER_SK,
                attrs("accountIds", DynamoItems.strings(Set.of(imported.toString())), "importedAccounts", n(1), "removedWaiting", n(2),
                        "source", s("postgresql"), "importedAt", s("2026-11-17T08:00:00Z")))).build());
        client.putItem(PutItemRequest.builder().tableName(table).item(item(account(imported), "CONTROL", attrs("importedFrom", s("postgresql"), "admitted", DynamoItems.bool(true)))).build());
        var before = get(client, table, AccountAccessImport.MARKER_PK, AccountAccessImport.MARKER_SK);
        try (var logs = LogCapture.start("lookahead.migration")) {
            step.apply(client, TABLES);
            assertThat(logs.messages()).containsExactly("Account import: the store already carries IMPORT#account-access / RESULT (DEV import, run 44162520740); nothing to do");
        }
        assertThat(get(client, table, AccountAccessImport.MARKER_PK, AccountAccessImport.MARKER_SK)).isEqualTo(before);
        assertThatCode(() -> step.verify(client, TABLES)).doesNotThrowAnyException();
        assertThat(AccountAccessImport.importedAccountIds(client, TABLES)).containsExactly(imported.toString());
        // An account the marker names must still be there as imported; verify never repairs or re-imports it.
        client.deleteItem(DeleteItemRequest.builder().tableName(table).key(Map.of("pk", s(account(imported)), "sk", s("CONTROL"))).build());
        assertThatThrownBy(() -> step.verify(client, TABLES)).hasMessage("An imported account is missing from the store");
        step.apply(client, TABLES);
        assertThatThrownBy(() -> step.verify(client, TABLES)).hasMessage("An imported account is missing from the store");
    }

    private static void purge(String table, String prefix) {
        var scan = client.scan(ScanRequest.builder().tableName(table).consistentRead(true).filterExpression("begins_with(pk, :p)")
                .expressionAttributeValues(Map.of(":p", s(prefix))).build());
        for (var found : scan.items())
            client.deleteItem(DeleteItemRequest.builder().tableName(table).key(Map.of("pk", found.get("pk"), "sk", found.get("sk"))).build());
    }
    private static void createTable(String name) {
        try { client.describeTable(DescribeTableRequest.builder().tableName(name).build()); return; } catch (ResourceNotFoundException absent) { /* create it */ }
        client.createTable(CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()).build());
    }
}
