package com.lookahead.domain.review.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** DLV-971: the Local store walks day folders, keeps dot-marker files out of listings and refuses keys outside its root. */
class FileSystemPlatformReviewObjectsTest {
    @TempDir Path root;

    @Test void listAllWalksFoldersAndSkipsDotFilesWhileReadKeepsThemReachable() {
        var store = new FileSystemPlatformReviewObjects(root);
        store.put("records/2026/11/06/REV-000001.json", "{}".getBytes());
        store.put("records/2026/11/07/SUP-000001.json", "{}".getBytes());
        store.put(".imported.json", "{\"reviews\":1}".getBytes());
        store.put("attachments/REV-000001/01-shot.png", new byte[] {1});
        assertThat(store.listAll("records/")).containsExactlyInAnyOrder("records/2026/11/06/REV-000001.json", "records/2026/11/07/SUP-000001.json");
        assertThat(store.listAll("records")).hasSize(2);
        assertThat(store.listAll("nowhere/")).isEmpty();
        assertThat(store.listAll("attachments/")).as("the marker is not under a listed root").containsExactly("attachments/REV-000001/01-shot.png");
        assertThat(store.read(".imported.json")).isPresent();
        assertThat(store.list("records/2026/11/06")).containsExactly("records/2026/11/06/REV-000001.json");
        assertThatThrownBy(() -> store.put("../outside.json", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("records/../../x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(root.resolveSibling("outside.json"))).isFalse();
        // A conditional write on a versioned file: the version is the digest of the bytes read.
        var stored = store.read("records/2026/11/06/REV-000001.json").orElseThrow();
        assertThat(store.write("records/2026/11/06/REV-000001.json", "{\"a\":1}".getBytes(), "stale")).isFalse();
        assertThat(store.write("records/2026/11/06/REV-000001.json", "{\"a\":1}".getBytes(), stored.version())).isTrue();
        assertThat(store.write("records/2026/11/06/REV-000002.json", "{}".getBytes(), null)).isTrue();
        assertThat(store.write("records/2026/11/06/REV-000002.json", "{}".getBytes(), null)).isFalse();
        store.delete("attachments/REV-000001/01-shot.png");
        assertThat(Files.exists(root.resolve("attachments/REV-000001"))).as("an emptied attachment folder goes with its last file").isFalse();
    }
}
