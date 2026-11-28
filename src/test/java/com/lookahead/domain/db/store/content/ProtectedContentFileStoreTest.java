package com.lookahead.domain.db.store.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reading half that left ProtectedContentManifestService (DLV-1016). The refusals and their log
 * lines are covered in ProtectedContentServiceTest.
 */
class ProtectedContentFileStoreTest {
  @TempDir Path directory;
  private final JsonMapper mapper = new JsonMapper();
  private static final byte[] BYTES = "{\"title\":\"sample\"}".getBytes(StandardCharsets.UTF_8);

  private Path manifest() throws Exception {
    Files.write(directory.resolve("sample.json"), BYTES);
    var entry =
        Map.of(
            "path",
            "/content/sample.json",
            "sha256",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)),
            "mediaType",
            "application/json",
            "tier",
            "public",
            "scopes",
            List.of(),
            "contentIds",
            List.of("sample-lesson"));
    Path manifest = directory.resolve("manifest.json");
    Files.writeString(
        manifest,
        mapper.writeValueAsString(
            Map.of(
                "schemaVersion",
                "content-publication/v1",
                "version",
                "test-v1",
                "assets",
                List.of(entry))));
    return manifest;
  }

  @Test
  void readsThePublishedBytesFromTheContentFolder() throws Exception {
    var policy =
        new ProtectedContentManifestService(
            mapper, manifest().toString(), directory.toString(), new PublicationFileStore());
    var contentFiles = new ProtectedContentFileStore(policy.root(), policy.version());
    var asset = policy.find("/content/sample.json").orElseThrow();
    assertThat(contentFiles.read(asset)).isEqualTo(BYTES);
    assertThat(contentFiles.read(asset)).isEqualTo(BYTES);
  }

  @Test
  void cloudModeAnswersTheSecondReadFromTheCache() throws Exception {
    var location =
        new PublicationLocation(manifest().toString(), directory.toString(), "", true, 1024, 4);
    var policy = new ProtectedContentManifestService(mapper, location, new PublicationFileStore());
    var contentFiles = new ProtectedContentFileStore(policy.root(), policy.version(), location);
    var asset = policy.find("/content/sample.json").orElseThrow();
    assertThat(contentFiles.read(asset)).isEqualTo(BYTES);
    Files.delete(directory.resolve("sample.json"));
    assertThat(contentFiles.read(asset)).isEqualTo(BYTES);
  }
}
