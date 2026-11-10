package com.lookahead.domain.persistence.dynamodb;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** SDK JSON/signing/error boundary against loopback HTTP; not a DynamoDB or IAM emulator. */
class DynamoSdkBoundaryTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void serializesBinaryParametersAndStrongReadsWithoutAmbientAwsCredentials() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var targets = new CopyOnWriteArrayList<String>();
        var server = server(requests, targets, false);
        try (var client = client(server)) {
            var store = new AuditedAccountStore(client, DynamoTableNamesTest.names("dev"));
            assertThat(store.commit(mutation())).isEqualTo(new AuditedAccountStore.CommitResult(1, false));
            assertThat(targets).containsExactly("DynamoDB_20120810.GetItem", "DynamoDB_20120810.ExecuteTransaction");
            assertThat(requests.getFirst().path("ConsistentRead").asBoolean()).isTrue();
            assertThat(requests.getFirst().path("TableName").asText()).isEqualTo("lookahead-dev-user-account-details");
            var statements = requests.get(1).path("TransactStatements");
            assertThat(statements.size()).isEqualTo(3);
            assertThat(statements.get(0).path("Parameters").get(3).path("B").asText()).isEqualTo("e30=");
            assertThat(statements.get(1).path("Statement").asText())
                    .startsWith("INSERT INTO \"lookahead-dev-administrator-audit-events\"");
            assertThat(statements.get(2).path("Parameters").get(2).path("S").asText()).matches("[0-9a-f]{64}");
        } finally {
            server.stop(0);
        }
    }

    @Test void recoversServiceErrorUsingReceiptAndSubsequentReplaySendsNoSecondTransaction() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var targets = new CopyOnWriteArrayList<String>();
        var server = server(requests, targets, true);
        try (var client = client(server)) {
            var store = new AuditedAccountStore(client, DynamoTableNamesTest.names("dev"));
            assertThat(store.commit(mutation())).isEqualTo(new AuditedAccountStore.CommitResult(1, true));
            assertThat(store.commit(mutation())).isEqualTo(new AuditedAccountStore.CommitResult(1, true));
            assertThat(targets).containsExactly("DynamoDB_20120810.GetItem", "DynamoDB_20120810.ExecuteTransaction",
                    "DynamoDB_20120810.GetItem", "DynamoDB_20120810.GetItem");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(List<JsonNode> requests, List<String> targets, boolean loseResponse) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var receipt = new AtomicReference<>("{}");
        server.createContext("/", exchange -> {
            try {
                String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                requests.add(request);
                targets.add(target);
                String response = receipt.get();
                int status = 200;
                if ("DynamoDB_20120810.ExecuteTransaction".equals(target)) {
                    var parameters = request.path("TransactStatements").get(2).path("Parameters");
                    receipt.set("{\"Item\":{\"schemaVersion\":{\"N\":\"1\"},\"fingerprint\":"
                            + parameters.get(2) + ",\"revision\":" + parameters.get(3) + "}}");
                    response = "{}";
                    if (loseResponse) {
                        status = 500;
                        response = "{\"__type\":\"InternalServerError\",\"message\":\"synthetic response unavailable\"}";
                    }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.0");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private DynamoDbClient client(HttpServer server) {
        return DynamoDbClient.builder().endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration.retryPolicy(RetryPolicy.none())).build();
    }

    private AuditedAccountStore.Mutation mutation() {
        return new AuditedAccountStore.Mutation(UUID.fromString("00000000-0000-0000-0000-000000000970"), "example",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 0, SdkBytes.fromUtf8String("{}"),
                new AuditedAccountStore.AuditEvent("system:synthetic", "membership.activated", "Synthetic verification",
                        Instant.parse("2026-11-02T12:00:00Z")));
    }
}
