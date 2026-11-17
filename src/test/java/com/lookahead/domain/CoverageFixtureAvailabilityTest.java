package com.lookahead.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release coverage gate must execute the DynamoDB persistence suites, never silently skip them. DLV-970 (2026-11-17):
 * the PostgreSQL fixtures are gone with the store; the disposable DynamoDB Local is the one fixture the gate needs.
 */
@EnabledIfSystemProperty(named = "coverage.required", matches = "true")
class CoverageFixtureAvailabilityTest {
    @Test void theDisposableDynamoDbFixtureIsConfigured() {
        for (String name : new String[]{"DLV970_DYNAMODB_TEST_ENDPOINT", "DLV970_LOCAL_DYNAMODB_ENDPOINT"})
            assertThat(System.getenv(name)).as("required DynamoDB Local endpoint %s", name).matches("http://127\\.0\\.0\\.1:\\d+");
    }
}
