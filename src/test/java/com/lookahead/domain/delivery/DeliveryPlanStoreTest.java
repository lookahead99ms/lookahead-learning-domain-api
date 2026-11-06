package com.lookahead.domain.delivery;

import com.lookahead.domain.review.platform.FileSystemPlatformReviewObjects;
import com.lookahead.domain.review.platform.PlatformReviewFixtures;
import com.lookahead.learning.content.exception.AccountFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** DLV-971: every write raises the version, archives the previous file and is conditional on the object read. */
class DeliveryPlanStoreTest {
    @TempDir Path root;
    final JsonMapper mapper = JsonMapper.builder().build();
    DeliveryPlanStore store() { return new DeliveryPlanStore(new FileSystemPlatformReviewObjects(root.resolve("delivery")), mapper); }

    @Test void saveArchivesThePreviousVersionAndRaisesTheNumber() throws Exception {
        PlatformReviewFixtures.seedPlan(root);
        var store = store();
        var loaded = store.current().orElseThrow();
        assertThat(loaded.version()).isEqualTo(7);
        var next = loaded.plan().deepCopy();
        next.put("title", "Edited"); next.put("baseVersion", 7);
        assertThat(store.save(loaded, next)).isTrue();
        var saved = store.current().orElseThrow();
        assertThat(saved.version()).isEqualTo(8);
        assertThat(saved.plan().path("title").asString()).isEqualTo("Edited");
        assertThat(saved.plan().has("baseVersion")).isFalse();
        assertThat(Files.readString(root.resolve("delivery/archive/plan-v7.json"))).isEqualTo(PlatformReviewFixtures.PLAN);
        assertThat(store.archived(7).orElseThrow().plan().path("title").asString()).isEqualTo("Build with discipline.");
        assertThat(store.archived(6)).isEmpty();
    }

    @Test void aStaleReadCannotOverwriteANewerPlan() throws Exception {
        PlatformReviewFixtures.seedPlan(root);
        var first = store(); var second = store();
        var readByFirst = first.current().orElseThrow();
        var readBySecond = second.current().orElseThrow();
        var fromSecond = readBySecond.plan().deepCopy(); fromSecond.put("title", "Second wins");
        assertThat(second.save(readBySecond, fromSecond)).isTrue();
        var fromFirst = readByFirst.plan().deepCopy(); fromFirst.put("title", "First is stale");
        assertThat(first.save(readByFirst, fromFirst)).isFalse();
        assertThat(store().current().orElseThrow().plan().path("title").asString()).isEqualTo("Second wins");
        assertThat(store().current().orElseThrow().version()).isEqualTo(8);
    }

    @Test void inboxIsReadAndDeletedAndAMissingPlanIsEmpty() throws Exception {
        var store = store();
        assertThat(store.current()).isEmpty();
        assertThat(store.inbox()).isEmpty();
        Files.createDirectories(root.resolve("delivery/inbox"));
        Files.writeString(root.resolve("delivery/inbox/plan.json"), "{\"version\":3,\"baseVersion\":3,\"workItems\":[]}");
        assertThat(store.inbox().orElseThrow().version()).isEqualTo(3);
        store.deleteInbox();
        assertThat(store.inbox()).isEmpty();
    }

    @Test void aDamagedPlanAnswersUnavailableAndSaysWhyWithoutItsContents() throws Exception {
        Files.createDirectories(root.resolve("delivery"));
        Files.writeString(root.resolve("delivery/plan.json"), "{\"version\":\"seven\",\"workItems\":[] , \"secret-text\": 1}");
        try (var log = com.lookahead.domain.LogCapture.start()) {
            var failure = catchThrowableOfType(AccountFailure.class, () -> store().current());
            assertThat(failure.status()).isEqualTo(503);
            assertThat(failure.code()).isEqualTo("DELIVERY_STORAGE_UNAVAILABLE");
            Files.writeString(root.resolve("delivery/plan.json"), "{ not json secret-text");
            assertThat(catchThrowableOfType(AccountFailure.class, () -> store().current()).status()).isEqualTo(503);
            assertThat(log.lines("delivery.read")).hasSize(2);
            assertThat(log.lines("delivery.read").getFirst().getFormattedMessage()).isEqualTo("The delivery plan file plan.json cannot be read: it is not a plan object with a numeric version and a workItems array; the Delivery Plan answers 503 until it is repaired");
            log.containsNone("secret-text");
        }
    }
}
