package com.lookahead.learning.content.service;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.learning.content.repository.DynamoLocalFixture;
import com.lookahead.learning.content.repository.DynamoOwnerTransactionManager;
import com.lookahead.learning.content.repository.DynamoOwnerUnit;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Real DynamoDB Local: lifecycle rows and the PlanLifecycle rules on top of them. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoPlanLifecycleStoreDatabaseTest {
    private static DynamoDbClient client;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private final DynamoPlanLifecycleStore store = new DynamoPlanLifecycleStore(client, DynamoLocalFixture.TABLES, clock);
    private final ObjectMapper json = new ObjectMapper();
    private final PlanLifecycle policy = new PlanLifecycle(store, json, clock);
    private UUID owner;

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void owner() { owner = DynamoLocalFixture.owner(client); }

    private tools.jackson.databind.node.ObjectNode snapshot(String course, int hours, int days) {
        var result = json.createObjectNode(); var config = result.putObject("config").put("days", days).put("dailyHours", hours);
        config.putArray("topicIds").add(course); return result;
    }

    @Test void reservationsOrderStateChangesAndReadYourWritesInsideAUnit() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a"), b = UUID.fromString("00000000-0000-0000-0000-000000000009");
        policy.create(owner, a, snapshot("learn:core-java", 1, 7), json.getNodeFactory().textNode("2026-10-20"), false);
        policy.create(owner, b, snapshot("learn:hands-on-dsa", 1, 7), json.getNodeFactory().textNode("2026-10-20"), false);
        assertThat(store.entries(owner)).extracting(PlanLifecycle.Entry::id).containsExactly(b, a);
        assertThat(policy.view(owner, a).path("state").asText()).isEqualTo("active");
        assertThat(policy.view(owner, a).path("endDateExclusive").asText()).isEqualTo("2026-10-27");
        assertThat(policy.view(owner, UUID.randomUUID()).path("state").asText()).isEqualTo("unactivated");
        // Same course and days as plan a: refused by the reservation policy before anything is written.
        assertThatThrownBy(() -> policy.create(owner, UUID.randomUUID(), snapshot("learn:core-java", 1, 7), json.getNodeFactory().textNode("2026-10-22"), false))
                .isInstanceOfSatisfying(com.lookahead.learning.content.exception.AccountFailure.class, e -> assertThat(e.code()).isEqualTo("PLAN_SUBJECT_CONFLICT"));
        policy.transition(owner, a, snapshot("learn:core-java", 1, 7), "archived", json.missingNode());
        assertThat(policy.view(owner, a).path("state").asText()).isEqualTo("archived");
        assertThatThrownBy(() -> policy.replace(owner, a, snapshot("learn:core-java", 2, 7))).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
        policy.replace(owner, UUID.randomUUID(), snapshot("learn:core-java", 2, 7)); // unknown legacy plan: untouched
        store.setState(owner, UUID.randomUUID(), "completed");
        assertThat(store.entries(owner)).hasSize(2);
        // Inside a unit the pending change is visible before commit and nothing is stored until it commits.
        var manager = new DynamoOwnerTransactionManager(client, DynamoLocalFixture.TABLES);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            policy.transition(owner, b, snapshot("learn:hands-on-dsa", 1, 7), "completed", json.missingNode());
            assertThat(policy.view(owner, b).path("state").asText()).isEqualTo("completed");
            assertThat(DynamoLocalFixture.item(client, DynamoLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(owner), "LIFECYCLE#" + b).get("state").s()).isEqualTo("active");
            assertThat(DynamoOwnerUnit.current()).isNotNull();
        });
        assertThat(DynamoLocalFixture.item(client, DynamoLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(owner), "LIFECYCLE#" + b).get("state").s()).isEqualTo("completed");
        var stored = DynamoLocalFixture.item(client, DynamoLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(owner), "LIFECYCLE#" + a);
        assertThat(DynamoPlanLifecycleStore.entry(stored)).isEqualTo(new PlanLifecycle.Entry(a, "archived", LocalDate.parse("2026-10-20"), 7, 60, Set.of("learn:core-java")));
        store.reserve(owner, a, LocalDate.parse("2026-11-01"), 3, 30, Set.of("learn:core-java"));
        assertThat(DynamoPlanLifecycleStore.entry(DynamoLocalFixture.item(client, DynamoLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(owner), "LIFECYCLE#" + a)).state()).isEqualTo("active");
    }
}
