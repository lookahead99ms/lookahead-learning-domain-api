package com.lookahead.domain.db.store.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file operations of the author review manifest; what the manifest must hold is the catalog's
 * decision.
 */
class AuthorReviewManifestFileStoreTest {
  @TempDir Path temp;
  private final AuthorReviewManifestFileStore files = new AuthorReviewManifestFileStore();

  @Test
  void readsTheFileUpToTheBoundAndNothingPastIt() throws Exception {
    Path file = Files.writeString(temp.resolve("manifest.json"), "{\"a\":1}");
    assertThat(files.bytes(file.toString(), 7))
        .hasValue("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
    assertThat(files.bytes(file.toString(), 6))
        .as("one byte past the bound: nothing is read")
        .isEmpty();
    assertThatThrownBy(() -> files.bytes(temp.resolve("missing.json").toString(), 7))
        .isInstanceOf(NoSuchFileException.class);
  }

  @Test
  void saysWhetherALocationIsAbsoluteAndRefusesOneThatIsNoPath() {
    assertThat(files.absolute(temp.resolve("manifest.json").toString())).isTrue();
    assertThat(files.absolute("relative.json")).isFalse();
    assertThatThrownBy(() -> files.absolute("bad\0name")).isInstanceOf(InvalidPathException.class);
    assertThatThrownBy(() -> files.bytes("bad\0name", 7)).isInstanceOf(InvalidPathException.class);
  }
}
