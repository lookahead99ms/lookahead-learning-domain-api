package com.lookahead.domain.db.store.testaccounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file operations of the custom test-plan fixtures; what the bundle must hold is the service's decision. */
class TestAccountPlanFixtureFileStoreTest {
    @TempDir Path temp;
    private final TestAccountPlanFixtureFileStore files = new TestAccountPlanFixtureFileStore();

    @Test void readsTheFileUpToTheBoundAndNothingPastIt() throws Exception {
        Path file = Files.writeString(temp.resolve("plans.json"), "{\"a\":1}");
        assertThat(files.text(file.toString(), 7)).contains("{\"a\":1}");
        assertThat(files.text(file.toString(), 6)).as("one byte past the bound: nothing is read").isEmpty();
        assertThatThrownBy(() -> files.text(temp.resolve("missing.json").toString(), 7)).isInstanceOf(NoSuchFileException.class);
    }

    @Test void neverReadsThroughASymbolicLink() throws Exception {
        Path file = Files.writeString(temp.resolve("plans.json"), "{\"a\":1}");
        Path link = Files.createSymbolicLink(temp.resolve("link.json"), file);
        assertThat(files.text(link.toString(), 100)).as("a symbolic link is never read").isEmpty();
        Path dangling = Files.createSymbolicLink(temp.resolve("dangling.json"), temp.resolve("gone.json"));
        assertThat(files.text(dangling.toString(), 100)).isEmpty();
    }
}
