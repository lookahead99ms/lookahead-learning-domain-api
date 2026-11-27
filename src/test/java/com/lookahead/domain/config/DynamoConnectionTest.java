package com.lookahead.domain.config;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNamesTest;
import com.lookahead.domain.log.LogCapture;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.*;

class DynamoConnectionTest {
    static MockEnvironment dev() {
        var environment = DynamoTableNamesTest.environment("dev");
        environment.setProperty("app.dynamodb.region", "us-east-2");
        return environment;
    }

    @Test void localNeedsALoopbackEndpointAndUsesTheDevNamesWithSyntheticCredentials() {
        var environment = new MockEnvironment().withProperty("app.deployment-environment", "local")
                .withProperty("app.dynamodb.endpoint", "http://127.0.0.1:4400");
        var connection = DynamoConnection.from(environment);
        assertThat(connection.local()).isTrue();
        assertThat(connection.endpoint()).isEqualTo(URI.create("http://127.0.0.1:4400"));
        assertThat(connection.tables()).isEqualTo(DynamoTableNames.local());
        assertThat(connection.region()).isEqualTo(Region.US_EAST_2);
        assertThat(connection.toString()).isEqualTo("DynamoConnection[local, us-east-2]");
        try (var client = new DynamoPersistenceConfiguration().dynamoDbClient(connection)) { assertThat(client).isNotNull(); }
        var variables = Map.of("LOOKAHEAD_ENVIRONMENT", "local", "LOOKAHEAD_DYNAMODB_ENDPOINT", "http://127.0.0.1:4401");
        assertThat(DynamoConnection.fromVariables(variables).endpoint().getPort()).isEqualTo(4401);
    }

    @Test void localRefusesAMissingOrNonLoopbackEndpointWithoutEchoingIt() {
        for (String endpoint : new String[]{"", "https://127.0.0.1:4400", "http://localhost:4400", "http://127.0.0.1:80",
                "http://127.0.0.1:4400/path", "http://user@127.0.0.1:4400", "http://127.0.0.1:4400?x=1", "http://10.0.0.5:4400", "::not a url::"}) {
            try (var logs = LogCapture.start("lookahead.startup")) {
                var environment = new MockEnvironment().withProperty("app.deployment-environment", "local").withProperty("app.dynamodb.endpoint", endpoint);
                assertThatThrownBy(() -> DynamoConnection.from(environment)).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Local DynamoDB requires a loopback endpoint");
                assertThat(logs.messages()).singleElement().asString().contains(DynamoConnection.ENDPOINT_SETTING).doesNotContain("10.0.0.5").doesNotContain("localhost");
            }
        }
    }

    @Test void devAndProdUseTheRegionWithTaskCredentialsAndNoEndpoint() {
        var connection = DynamoConnection.from(dev());
        assertThat(connection.local()).isFalse();
        assertThat(connection.endpoint()).isNull();
        assertThat(connection.region()).isEqualTo(Region.US_EAST_2);
        assertThat(connection.tables()).isEqualTo(DynamoTableNamesTest.names("dev"));
        try (var client = new DynamoPersistenceConfiguration().dynamoDbClient(connection)) { assertThat(client).isNotNull(); }
        var variables = new HashMap<String, String>();
        variables.put("LOOKAHEAD_ENVIRONMENT", "prod");
        variables.put("AWS_REGION", "us-east-2");
        for (int i = 0; i < 4; i++) variables.put(DynamoTableNames.VARIABLES.get(i), DynamoTableNamesTest.names("prod").all().get(i));
        assertThat(DynamoConnection.fromVariables(variables).tables()).isEqualTo(DynamoTableNamesTest.names("prod"));
    }

    @Test void cloudRefusesAnEndpointOverrideABadRegionOrAnUnknownEnvironment() {
        try (var logs = LogCapture.start("lookahead.startup")) {
            var withEndpoint = dev();
            withEndpoint.setProperty("app.dynamodb.endpoint", "http://127.0.0.1:4400");
            assertThatThrownBy(() -> DynamoConnection.from(withEndpoint)).hasMessage("Cloud DynamoDB does not accept an endpoint override");
            var badRegion = dev();
            badRegion.setProperty("app.dynamodb.region", "ohio");
            assertThatThrownBy(() -> DynamoConnection.from(badRegion)).hasMessage("Cloud DynamoDB requires AWS_REGION");
            var noRegion = dev();
            noRegion.setProperty("app.dynamodb.region", "");
            assertThatThrownBy(() -> DynamoConnection.from(noRegion)).hasMessage("Cloud DynamoDB requires AWS_REGION");
            assertThatThrownBy(() -> DynamoConnection.fromVariables(Map.of("LOOKAHEAD_ENVIRONMENT", "staging")))
                    .hasMessage("DynamoDB requires an explicit local, dev or prod environment");
            assertThat(logs.messages()).hasSize(4).allMatch(message -> message.startsWith("Startup refused: "));
        }
        assertThatThrownBy(() -> new DynamoConnection("dev", Region.US_EAST_2, URI.create("http://127.0.0.1:4400"), DynamoTableNames.local()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoConnection("local", Region.US_EAST_2, null, DynamoTableNames.local()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tableNamesSelectLocalOrTheExplicitProperties() {
        assertThat(DynamoTableNames.select(new MockEnvironment().withProperty("app.deployment-environment", "local"))).isEqualTo(DynamoTableNames.local());
        assertThat(DynamoTableNames.select(DynamoTableNamesTest.environment("prod"))).isEqualTo(DynamoTableNamesTest.names("prod"));
        assertThat(DynamoTableNames.local().all()).containsExactly("lookahead-dev-user-account-details", "lookahead-dev-administrator-audit-events",
                "lookahead-dev-user-study-plan-details", "lookahead-dev-protected-owner-identity");
        assertThatThrownBy(() -> DynamoTableNames.fromVariables("dev", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamoTableNames.fromVariables("staging", Map.of())).isInstanceOf(IllegalArgumentException.class);
        var crossed = new HashMap<String, String>();
        for (int i = 0; i < 4; i++) crossed.put(DynamoTableNames.VARIABLES.get(i), DynamoTableNamesTest.names("prod").all().get(i));
        assertThatThrownBy(() -> DynamoTableNames.fromVariables("dev", crossed)).hasMessage("DynamoDB table environment does not match deployment");
    }
}
