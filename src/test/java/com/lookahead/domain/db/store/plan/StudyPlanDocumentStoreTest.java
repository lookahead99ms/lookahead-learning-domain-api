package com.lookahead.domain.db.store.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The partition key of a document's items: written in one place, read back by the two cleanup
 * stores through one parse.
 */
class StudyPlanDocumentStoreTest {
  private final UUID owner = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private final UUID document = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  @Test
  void aDocumentAndItsPublishedReferenceWriteTheSameKey() {
    String key = "DOC#11111111-2222-3333-4444-555555555555#aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    var id = new StudyPlanDocumentStore.DocumentId(owner, document);
    assertThat(id.pk()).isEqualTo(key);
    assertThat(
            new StudyPlanDocumentPublishStore.Reference(document, 10, 1, "0".repeat(64), 0)
                .pk(owner))
        .isEqualTo(key);
    assertThat(StudyPlanDocumentStore.DocumentId.parse(key)).isEqualTo(id);
  }

  @Test
  void aKeyThatWouldNotBeWrittenBackTheSameWayIsRefused() {
    for (String shape :
        List.of(
            "bad",
            "OTHER#" + owner + "#" + document,
            "DOC#" + owner,
            "DOC#" + owner + "#" + document + "#more"))
      assertThatThrownBy(() -> StudyPlanDocumentStore.DocumentId.parse(shape))
          .as(shape)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Invalid document key");
    assertThatThrownBy(() -> StudyPlanDocumentStore.DocumentId.parse("DOC#bad#bad"))
        .isInstanceOf(IllegalArgumentException.class);
    // UUID.fromString accepts short groups; the key it would be written back as differs, so it
    // names no document.
    assertThatThrownBy(() -> StudyPlanDocumentStore.DocumentId.parse("DOC#1-1-1-1-1#1-1-1-1-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Noncanonical document key");
  }
}
