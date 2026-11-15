package com.lookahead.learning.content.service;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.learning.content.repository.DynamoLocalFixture;
import com.lookahead.learning.content.repository.DynamoOwnerTransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import static org.assertj.core.api.Assertions.*;

/** Real DynamoDB Local: lifetime creation receipts with their counter, the rolling deletion window and the active free plan count. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoFreePlanLedgerStoreDatabaseTest {
    private static DynamoDbClient client;
    private static final String TABLE = DynamoLocalFixture.TABLES.userAccountDetailsTableName();
    private final DynamoLocalFixture.MutableClock clock = new DynamoLocalFixture.MutableClock(Instant.parse("2026-10-16T12:00:00Z"));
    private final DynamoFreePlanLedgerStore store = new DynamoFreePlanLedgerStore(client, DynamoLocalFixture.TABLES, clock);
    private UUID owner;

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void owner() { owner = DynamoLocalFixture.owner(client); }

    long meta(String name) { return DynamoItems.number(DynamoLocalFixture.item(client, TABLE, DynamoItems.account(owner), "META"), name); }
    void put(String pk, String sk, Map<String, AttributeValue> attributes) {
        client.putItem(PutItemRequest.builder().tableName(TABLE).item(DynamoItems.item(pk, sk, 1, attributes)).build());
    }

    @Test void lifetimeReceiptsCountOnceAndTheCounterNeverDecrements() {
        UUID plan = UUID.randomUUID();
        assertThat(store.creations(owner)).isZero();
        assertThat(store.creationRecorded(owner, plan)).isFalse();
        store.recordCreation(owner, plan);
        store.recordCreation(owner, plan);
        assertThat(store.creations(owner)).isEqualTo(1);
        assertThat(meta("freePlanCreations")).isEqualTo(1);
        assertThat(store.creationRecorded(owner, plan)).isTrue();
        assertThat(DynamoLocalFixture.items(client, DynamoItems.account(owner), "FREECREATE#")).hasSize(1);
        // Inside one unit: two creations land together with one counter move, visible to the unit before commit.
        new TransactionTemplate(new DynamoOwnerTransactionManager(client, DynamoLocalFixture.TABLES)).executeWithoutResult(status -> {
            UUID second = UUID.randomUUID();
            store.recordCreation(owner, second);
            store.recordCreation(owner, second);
            assertThat(store.creationRecorded(owner, second)).isTrue();
            assertThat(store.creations(owner)).isEqualTo(2);
            assertThat(meta("freePlanCreations")).isEqualTo(1);
        });
        assertThat(meta("freePlanCreations")).isEqualTo(2);
        assertThat(meta("revision")).isEqualTo(3);
    }

    @Test void deletionWindowIsRollingAndStrictlyAfterSince() {
        Instant since = clock.instant().minus(Duration.ofDays(21));
        assertThat(store.deletionsSince(owner, since)).isZero();
        assertThat(store.oldestDeletionSince(owner, since)).isNull();
        store.recordDeletion(owner, UUID.randomUUID());
        clock.advance(Duration.ofDays(1));
        store.recordDeletion(owner, UUID.randomUUID());
        assertThat(store.deletionsSince(owner, since)).isEqualTo(2);
        assertThat(store.oldestDeletionSince(owner, since)).isEqualTo(Instant.parse("2026-10-16T12:00:00Z"));
        assertThat(store.deletionsSince(owner, Instant.parse("2026-10-16T12:00:00Z"))).isEqualTo(1);
        new TransactionTemplate(new DynamoOwnerTransactionManager(client, DynamoLocalFixture.TABLES)).executeWithoutResult(status -> {
            store.recordDeletion(owner, UUID.randomUUID());
            assertThat(store.deletionsSince(owner, since)).isEqualTo(3);
        });
        assertThat(DynamoLocalFixture.items(client, DynamoItems.account(owner), "FREEDELETE#")).hasSize(3);
    }

    @Test void activeFreePlansSkipsArchivedCompletedExpiredDeletedExcludedAndPaidPlans() {
        LocalDate today = LocalDate.parse("2026-10-16");
        UUID fresh = UUID.randomUUID(), archived = UUID.randomUUID(), expired = UUID.randomUUID(), deleted = UUID.randomUUID(), excluded = UUID.randomUUID(), paid = UUID.randomUUID(), undated = UUID.randomUUID();
        for (UUID id : java.util.List.of(fresh, archived, expired, deleted, excluded, paid, undated)) {
            UUID version = UUID.randomUUID();
            put(DynamoItems.account(owner), "PLAN#" + id, Map.of("planId", DynamoItems.s(id.toString()), "currentVersion", DynamoItems.s(version.toString()), "deleted", DynamoItems.bool(id.equals(deleted))));
            put(DynamoItems.plan(owner, id), "VERSION#" + version, Map.of("contentIds", DynamoItems.strings(id.equals(paid) ? Set.of("lesson-paid") : Set.of("lesson-free"))));
        }
        for (var entry : Map.of(fresh, "active", archived, "archived", expired, "active", excluded, "active").entrySet())
            put(DynamoItems.account(owner), "LIFECYCLE#" + entry.getKey(), Map.of("planId", DynamoItems.s(entry.getKey().toString()), "state", DynamoItems.s(entry.getValue()),
                    "startDate", DynamoItems.s(entry.getKey().equals(expired) ? "2026-01-01" : "2026-10-10"), "dayCount", DynamoItems.n(7), "dailyMinutes", DynamoItems.n(60), "subjectIds", DynamoItems.strings(Set.of("learn:x"))));
        assertThat(store.activeFreePlans(owner, excluded, today, content -> content.equals(Set.of("lesson-free")))).isEqualTo(2);
        assertThat(store.activeFreePlans(owner, null, today, content -> content.equals(Set.of("lesson-free")))).isEqualTo(3);
        put(DynamoItems.account(owner), "PLAN#" + UUID.randomUUID(), Map.of("planId", DynamoItems.s(UUID.randomUUID().toString()), "currentVersion", DynamoItems.s(UUID.randomUUID().toString()), "deleted", DynamoItems.bool(false)));
        assertThatIllegalStateException().isThrownBy(() -> store.activeFreePlans(owner, null, today, content -> true));
    }
}
