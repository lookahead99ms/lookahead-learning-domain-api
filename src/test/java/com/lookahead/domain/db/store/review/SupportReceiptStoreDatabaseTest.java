package com.lookahead.domain.db.store.review;

import com.lookahead.domain.db.store.plan.StudyPlanStoreLocalFixture;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionManager;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.SupportFeedbackModels;
import com.lookahead.domain.util.SupportFeedbackRules;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import static org.assertj.core.api.Assertions.*;

/** Real DynamoDB Local: reserve, replay, key reuse, five per hour, release and acceptance of support receipts. */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class SupportReceiptStoreDatabaseTest {
    private static DynamoDbClient client;
    private final StudyPlanStoreLocalFixture.MutableClock clock = new StudyPlanStoreLocalFixture.MutableClock(Instant.parse("2026-11-10T06:00:00Z"));
    private final SupportReceiptStore receipts = new SupportReceiptStore(client, StudyPlanStoreLocalFixture.TABLES, clock);
    private String owner;

    @BeforeAll static void connect() { client = StudyPlanStoreLocalFixture.connect(); }
    @AfterAll static void close() { if (client != null) client.close(); }
    @BeforeEach void owner() { owner = StudyPlanStoreLocalFixture.owner(client).toString(); }

    String status(String key) {
        var item = StudyPlanStoreLocalFixture.item(client, StudyPlanStoreLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(UUID.fromString(owner)), "SUPPORT#" + DynamoItems.encode(key));
        return item.isEmpty() ? null : item.get("status").s();
    }
    long metaRevision() { return DynamoItems.number(StudyPlanStoreLocalFixture.item(client, StudyPlanStoreLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(UUID.fromString(owner)), "META"), "revision"); }
    int timeRows() { return StudyPlanStoreLocalFixture.items(client, DynamoItems.account(UUID.fromString(owner)), "SUPPORTTIME#").size(); }

    @Test void reserveAcceptReplayAndKeyReuse() {
        var first = receipts.reserve(owner, "key-1", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW);
        assertThat(first.dispatch()).isTrue();
        assertThat(first.receipt().status()).isEqualTo("unconfirmed");
        assertThat(status("key-1")).isEqualTo("unconfirmed");
        receipts.accepted(owner, "key-1");
        assertThat(status("key-1")).isEqualTo("accepted");
        var again = receipts.reserve(owner, "key-1", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW);
        assertThat(again.dispatch()).isFalse();
        assertThat(again.receipt()).isEqualTo(new SupportFeedbackModels.Receipt(first.receipt().reference(), "accepted"));
        // DLV-1016: the store answers that nothing was reserved (and writes nothing); the service makes the refusal.
        long revisionBefore = metaRevision();
        assertThat(receipts.reserve(owner, "key-1", "b".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW)).isEqualTo(SupportReceipt.Reservation.refused(SupportReceipt.Refused.KEY_REUSED));
        assertThat(metaRevision()).as("a refused reservation commits nothing: the owner fence does not move").isEqualTo(revisionBefore);
        assertThat(status("key-1")).isEqualTo("accepted"); assertThat(timeRows()).isEqualTo(1);
        receipts.release(owner, "key-1");
        assertThat(status("key-1")).isEqualTo("accepted");
        receipts.accepted(owner, "missing");
        assertThat(status("missing")).isNull();
        assertThatThrownBy(() -> receipts.reserve(UUID.randomUUID().toString(), "key-2", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW)).isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    /** DLV-970 review Q7: the OwnerTransactionUnit.run convention; inside a bound unit the reservation lands with that unit's commit. */
    @Test void insideABoundUnitTheReservationCommitsWithTheUnit() {
        var manager = new OwnerTransactionManager(client, StudyPlanStoreLocalFixture.TABLES);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(receipts.reserve(owner, "key-1", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW).dispatch()).isTrue();
            assertThat(status("key-1")).isNull();
            assertThat(timeRows()).isZero();
        });
        assertThat(status("key-1")).isEqualTo("unconfirmed");
        assertThat(timeRows()).isEqualTo(1);
        assertThat(DynamoItems.number(StudyPlanStoreLocalFixture.item(client, StudyPlanStoreLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(UUID.fromString(owner)), "META"), "revision")).isEqualTo(2);
    }

    @Test void fivePerHourThenReleaseFreesASlotAndTheWindowRolls() {
        for (int i = 1; i <= 5; i++) { assertThat(receipts.reserve(owner, "key-" + i, "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW).dispatch()).isTrue(); clock.advance(Duration.ofMinutes(1)); }
        long revisionBefore = metaRevision();
        assertThat(receipts.reserve(owner, "key-6", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW)).isEqualTo(SupportReceipt.Reservation.refused(SupportReceipt.Refused.QUOTA_REACHED));
        assertThat(metaRevision()).isEqualTo(revisionBefore); assertThat(status("key-6")).isNull();
        assertThat(timeRows()).isEqualTo(5);
        receipts.release(owner, "key-5");
        assertThat(status("key-5")).isNull();
        assertThat(timeRows()).isEqualTo(4);
        assertThat(receipts.reserve(owner, "key-6", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW).dispatch()).isTrue();
        assertThat(receipts.reserve(owner, "key-7", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW).refused()).isEqualTo(SupportReceipt.Refused.QUOTA_REACHED);
        clock.advance(Duration.ofMinutes(57));
        assertThat(receipts.reserve(owner, "key-7", "a".repeat(64), SupportFeedbackRules.PER_WINDOW, SupportFeedbackRules.WINDOW).dispatch()).isTrue();
        var references = StudyPlanStoreLocalFixture.items(client, DynamoItems.account(UUID.fromString(owner)), "SUPPORT#").stream().map(item -> item.get("reference").s()).distinct().count();
        assertThat(references).isEqualTo(6);
        assertThat(DynamoItems.number(StudyPlanStoreLocalFixture.item(client, StudyPlanStoreLocalFixture.TABLES.userAccountDetailsTableName(), DynamoItems.account(UUID.fromString(owner)), "META"), "revision")).isEqualTo(8);
    }
}
