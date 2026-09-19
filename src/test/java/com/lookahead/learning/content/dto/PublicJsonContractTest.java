package com.lookahead.learning.content.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public wire shape stays stable when Java transport records become app-owned. */
@SpringBootTest(classes = PublicJsonContractTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.name=wire-contract-test")
class PublicJsonContractTest {
    @Autowired private ObjectMapper mapper;
    private final Instant timestamp = Instant.parse("2026-01-02T03:04:05Z");

    @Configuration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    static class App { }

    @Test void accountEnvelopePreservesIdentifiersGrantsAndAuthorCapabilityWithoutCredentials() {
        UUID subject = UUID.fromString("12345678-1234-4234-8234-123456789abc");
        var account = new AccountView(subject, "synthetic-learner", "Synthetic Learner",
                Set.of("learn:java"), Set.of("lesson-one"), false);
        JsonNode actual = mapper.valueToTree(new ApiResponse<>(account, timestamp));
        JsonNode expected = mapper.readTree("""
                {"data":{"accountId":"12345678-1234-4234-8234-123456789abc",
                "username":"synthetic-learner","displayName":"Synthetic Learner",
                "topicGrants":["learn:java"],"contentGrants":["lesson-one"],"authorPreview":false},
                "timestamp":"2026-01-02T03:04:05Z"}
                """);
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.path("data").properties().stream().map(Map.Entry::getKey).toList())
                .doesNotContain("password", "passwordHash", "accessToken", "refreshToken", "enabled");
    }

    @Test void compatibilityConstructorDoesNotInventEntitlementsOrAuthorPermission() {
        var account = new AccountView(UUID.fromString("12345678-1234-4234-8234-123456789abc"),
                "synthetic-learner", null, Set.of());
        JsonNode actual = mapper.valueToTree(account);
        assertThat(actual.path("displayName").isNull()).isTrue();
        assertThat(actual.path("topicGrants").isArray()).isTrue();
        assertThat(actual.path("topicGrants").size()).isZero();
        assertThat(actual.path("contentGrants").isArray()).isTrue();
        assertThat(actual.path("contentGrants").size()).isZero();
        assertThat(actual.path("authorPreview").asBoolean()).isFalse();
    }

    @Test void statusEnvelopeRetainsVersionAndExplicitNullData() {
        JsonNode status = mapper.valueToTree(new ApiResponse<>(
                new ApplicationStatus("lookahead-learning-domain-api", "UP", "synthetic-version"), timestamp));
        assertThat(status).isEqualTo(mapper.readTree("""
                {"data":{"application":"lookahead-learning-domain-api","status":"UP","version":"synthetic-version"},
                "timestamp":"2026-01-02T03:04:05Z"}
                """));
        JsonNode empty = mapper.valueToTree(new ApiResponse<>(null, timestamp));
        assertThat(empty.has("data")).isTrue();
        assertThat(empty.path("data").isNull()).isTrue();
    }

    @Test void publicErrorRetainsStatusDetailsAndTimestamp() {
        JsonNode actual = mapper.valueToTree(new ApiError(422, "Unprocessable Entity", "Invalid synthetic request",
                "/api/v1/plans", List.of("Unknown content reference"), timestamp));
        assertThat(actual).isEqualTo(mapper.readTree("""
                {"status":422,"error":"Unprocessable Entity","message":"Invalid synthetic request",
                "path":"/api/v1/plans","details":["Unknown content reference"],"timestamp":"2026-01-02T03:04:05Z"}
                """));
    }

    @Test void existingJsonWithAbsentOptionalAccountFieldsRetainsDefaults() {
        var account = mapper.readValue("""
                {"accountId":"12345678-1234-4234-8234-123456789abc","username":"synthetic-learner","authorPreview":false}
                """, AccountView.class);
        assertThat(account.accountId()).isEqualTo(UUID.fromString("12345678-1234-4234-8234-123456789abc"));
        assertThat(account.displayName()).isNull();
        assertThat(account.topicGrants()).isNull();
        assertThat(account.contentGrants()).isNull();
        assertThat(account.authorPreview()).isFalse();
        assertThat(mapper.readValue(mapper.writeValueAsString(account), AccountView.class)).isEqualTo(account);
    }

    @Test void missingOrNullPrimitiveCapabilityRemainsInvalidJson() {
        assertThatThrownBy(() -> mapper.readValue("{}", AccountView.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"authorPreview\":null}", AccountView.class))
                .isInstanceOf(MismatchedInputException.class);
    }
}
