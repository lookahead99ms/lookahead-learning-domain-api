package com.lookahead.domain.db.store.delivery;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.db.store.review.PlatformReviewFileSystemObjectStore;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.service.review.PlatformReviewFixtures;
import com.lookahead.domain.util.DeliveryPlanRules;
import com.lookahead.domain.validator.DeliveryPlanFileValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-971: every write raises the version, archives the previous file and is conditional on the
 * object read.
 */
class DeliveryPlanStoreTest {
  @TempDir Path root;
  final JsonMapper mapper = JsonMapper.builder().build();

  DeliveryPlanStore store() {
    return new DeliveryPlanStore(
        new PlatformReviewFileSystemObjectStore(root.resolve("delivery")), mapper);
  }

  @Test
  void saveArchivesThePreviousVersionAndRaisesTheNumber() throws Exception {
    PlatformReviewFixtures.seedPlan(root);
    var store = store();
    var loaded = store.current().orElseThrow();
    assertThat(loaded.version()).isEqualTo(7);
    var next = loaded.plan().deepCopy();
    next.put("title", "Edited");
    next.put("baseVersion", 7);
    assertThat(store.save(loaded, next)).isTrue();
    var saved = store.current().orElseThrow();
    assertThat(saved.version()).isEqualTo(8);
    assertThat(saved.plan().path("title").asString()).isEqualTo("Edited");
    assertThat(saved.plan().has("baseVersion")).isFalse();
    assertThat(Files.readString(root.resolve("delivery/archive/plan-v7.json")))
        .isEqualTo(PlatformReviewFixtures.PLAN);
    assertThat(store.archived(7).orElseThrow().plan().path("title").asString())
        .isEqualTo("Build with discipline.");
    assertThat(store.archived(6)).isEmpty();
  }

  @Test
  void aStaleReadCannotOverwriteANewerPlan() throws Exception {
    PlatformReviewFixtures.seedPlan(root);
    var first = store();
    var second = store();
    var readByFirst = first.current().orElseThrow();
    var readBySecond = second.current().orElseThrow();
    var fromSecond = readBySecond.plan().deepCopy();
    fromSecond.put("title", "Second wins");
    assertThat(second.save(readBySecond, fromSecond)).isTrue();
    var fromFirst = readByFirst.plan().deepCopy();
    fromFirst.put("title", "First is stale");
    assertThat(first.save(readByFirst, fromFirst)).isFalse();
    assertThat(store().current().orElseThrow().plan().path("title").asString())
        .isEqualTo("Second wins");
    assertThat(store().current().orElseThrow().version()).isEqualTo(8);
  }

  @Test
  void inboxIsReadAndDeletedAndAMissingPlanIsEmpty() throws Exception {
    var store = store();
    assertThat(store.current()).isEmpty();
    assertThat(store.inbox()).isEmpty();
    Files.createDirectories(root.resolve("delivery/inbox"));
    Files.writeString(
        root.resolve("delivery/inbox/plan.json"),
        "{\"version\":3,\"baseVersion\":3,\"workItems\":[]}");
    assertThat(store.inbox().orElseThrow().version()).isEqualTo(3);
    store.deleteInbox();
    assertThat(store.inbox()).isEmpty();
  }

  @Test
  void aDamagedPlanAnswersUnavailableAndSaysWhyWithoutItsContents() throws Exception {
    Files.createDirectories(root.resolve("delivery"));
    Files.writeString(
        root.resolve("delivery/plan.json"),
        "{\"version\":\"seven\",\"workItems\":[] , \"secret-text\": 1}");
    try (var log = LogCapture.start()) {
      var failure = catchThrowableOfType(AccountFailure.class, () -> store().current());
      assertThat(failure.status()).isEqualTo(503);
      assertThat(failure.code()).isEqualTo("DELIVERY_STORAGE_UNAVAILABLE");
      Files.writeString(root.resolve("delivery/plan.json"), "{ not json secret-text");
      assertThat(catchThrowableOfType(AccountFailure.class, () -> store().current()).status())
          .isEqualTo(503);
      assertThat(log.lines("delivery.read")).hasSize(2);
      assertThat(log.lines("delivery.read").getFirst().getFormattedMessage())
          .isEqualTo(
              "The delivery plan file plan.json cannot be read: it is not a plan object with a"
                  + " numeric version and a workItems array; the Delivery Plan answers 503 until it"
                  + " is repaired");
      log.containsNone("secret-text");
    }
  }

  /**
   * DLV-1016: an edited plan that would be written larger than the limit is reported by type,
   * before the archive copy and the write.
   */
  @Test
  void aPlanThatWouldBeWrittenTooLargeIsReportedBeforeAnythingIsWritten() throws Exception {
    PlatformReviewFixtures.seedPlan(root);
    var store = store();
    var loaded = store.current().orElseThrow();
    var next = loaded.plan().deepCopy();
    next.put("padding", "x".repeat(DeliveryPlanRules.MAX_BYTES));
    assertThatThrownBy(() -> store.save(loaded, next))
        .isInstanceOf(DeliveryPlanStore.TooLarge.class)
        .hasMessageContaining(String.valueOf(DeliveryPlanRules.MAX_BYTES));
    assertThat(Files.exists(root.resolve("delivery/archive/plan-v7.json"))).isFalse();
    assertThat(store.current().orElseThrow().version()).isEqualTo(7);
  }

  /**
   * DLV-998: the first plan is seeded only once, and the release import marker is read back as
   * written.
   */
  @Test
  void seedsOnlyWhileNoPlanExistsAndKeepsTheReleaseImportMarker() throws Exception {
    var store = store();
    assertThat(store.current()).isEmpty();
    assertThat(store.releaseImport()).isEmpty();
    byte[] plan = PlatformReviewFixtures.PLAN.getBytes(StandardCharsets.UTF_8);
    assertThat(store.seed(plan)).isTrue();
    assertThat(store.current()).isPresent();
    assertThat(store.current().get().version()).isEqualTo(7);
    assertThat(store.seed(plan)).as("a second task starting at the same moment loses").isFalse();
    // DLV-1016: the 413 for an oversized file is the validator's (the import job has every file
    // checked before it seeds).
    assertThat(
            catchThrowableOfType(
                    AccountFailure.class,
                    () ->
                        new DeliveryPlanFileValidator(mapper)
                            .plan(new byte[DeliveryPlanRules.MAX_BYTES + 1]))
                .status())
        .isEqualTo(413);
    store.recordReleaseImport(
        ("{\"schema\":\"lookahead.release-plan-import/v1\",\"sha256\":\"abc\","
                + "\"release\":\"v2026.11.09.20.00\",\"planVersion\":7}")
            .getBytes(StandardCharsets.UTF_8));
    assertThat(store.releaseImport()).isPresent();
    assertThat(store.releaseImport().get().path("sha256").asString()).isEqualTo("abc");
    store.recordReleaseImport("not json".getBytes(StandardCharsets.UTF_8));
    assertThat(store.releaseImport())
        .as("a damaged marker reads as no marker, so the next start imports again")
        .isEmpty();
  }
}
