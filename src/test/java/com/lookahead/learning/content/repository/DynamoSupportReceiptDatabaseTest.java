package com.lookahead.learning.content.repository;

import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import static org.assertj.core.api.Assertions.*;

/** Real DynamoDB Local: reserve, replay, key reuse, five per hour, release and acceptance of support receipts. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class DynamoSupportReceiptDatabaseTest {
    private static DynamoDbClient client;
    private final DynamoLocalFixture.MutableClock clock = new DynamoLocalFixture.MutableClock(Instant.parse("2026-11-10T06:00:00Z"));
    private final DynamoSupportReceiptRepository receipts = new DynamoSupportReceiptRepository(client, DynamoLocalFixture.TABLES, clock);
    private String owner;

    @BeforeAll static void connect() { client = DynamoLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void owner() { owner = DynamoLocalFixture.owner(client).toString(); }

    String status(String key) {
        var item = DynamoLocalFixture.item(client, DynamoLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(UUID.fromString(owner)), "SUPPORT#" + DynamoItems.encode(key));
        return item.isEmpty() ? null : item.get("status").s();
    }
    int timeRows() { return DynamoLocalFixture.items(client, DynamoItems.account(UUID.fromString(owner)), "SUPPORTTIME#").size(); }

    @Test void reserveAcceptReplayAndKeyReuse() {
        var first = receipts.reserve(owner, "key-1", "a".repeat(64));
        assertThat(first.dispatch()).isTrue();
        assertThat(first.receipt().status()).isEqualTo("unconfirmed");
        assertThat(status("key-1")).isEqualTo("unconfirmed");
        receipts.accepted(owner, "key-1");
        assertThat(status("key-1")).isEqualTo("accepted");
        var again = receipts.reserve(owner, "key-1", "a".repeat(64));
        assertThat(again.dispatch()).isFalse();
        assertThat(again.receipt()).isEqualTo(new com.lookahead.learning.content.service.SupportFeedbackService.Receipt(first.receipt().reference(), "accepted"));
        assertThatThrownBy(() -> receipts.reserve(owner, "key-1", "b".repeat(64))).isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FEEDBACK_KEY_REUSED"));
        receipts.release(owner, "key-1");
        assertThat(status("key-1")).isEqualTo("accepted");
        receipts.accepted(owner, "missing");
        assertThat(status("missing")).isNull();
        assertThatThrownBy(() -> receipts.reserve(UUID.randomUUID().toString(), "key-2", "a".repeat(64))).isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    /** DLV-970 review Q7: the DynamoOwnerUnit.run convention; inside a bound unit the reservation lands with that unit's commit. */
    @Test void insideABoundUnitTheReservationCommitsWithTheUnit() {
        var manager = new DynamoOwnerTransactionManager(client, DynamoLocalFixture.TABLES);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(receipts.reserve(owner, "key-1", "a".repeat(64)).dispatch()).isTrue();
            assertThat(status("key-1")).isNull();
            assertThat(timeRows()).isZero();
        });
        assertThat(status("key-1")).isEqualTo("unconfirmed");
        assertThat(timeRows()).isEqualTo(1);
        assertThat(DynamoItems.number(DynamoLocalFixture.item(client, DynamoLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(UUID.fromString(owner)), "META"), "revision")).isEqualTo(2);
    }

    @Test void fivePerHourThenReleaseFreesASlotAndTheWindowRolls() {
        for (int i = 1; i <= 5; i++) { assertThat(receipts.reserve(owner, "key-" + i, "a".repeat(64)).dispatch()).isTrue(); clock.advance(Duration.ofMinutes(1)); }
        assertThatThrownBy(() -> receipts.reserve(owner, "key-6", "a".repeat(64))).isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo("FEEDBACK_RATE_LIMIT"));
        assertThat(timeRows()).isEqualTo(5);
        receipts.release(owner, "key-5");
        assertThat(status("key-5")).isNull();
        assertThat(timeRows()).isEqualTo(4);
        assertThat(receipts.reserve(owner, "key-6", "a".repeat(64)).dispatch()).isTrue();
        assertThatThrownBy(() -> receipts.reserve(owner, "key-7", "a".repeat(64))).isInstanceOf(AccountFailure.class);
        clock.advance(Duration.ofMinutes(57));
        assertThat(receipts.reserve(owner, "key-7", "a".repeat(64)).dispatch()).isTrue();
        var references = DynamoLocalFixture.items(client, DynamoItems.account(UUID.fromString(owner)), "SUPPORT#").stream().map(item -> item.get("reference").s()).distinct().count();
        assertThat(references).isEqualTo(6);
        assertThat(DynamoItems.number(DynamoLocalFixture.item(client, DynamoLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(UUID.fromString(owner)), "META"), "revision")).isEqualTo(8);
    }
}
