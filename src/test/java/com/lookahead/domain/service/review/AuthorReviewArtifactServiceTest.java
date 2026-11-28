package com.lookahead.domain.service.review;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.db.store.review.AuthorReviewManifestFileStore;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AuthorReviewArtifactServiceTest {
  @TempDir Path directory;
  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void absentConfigurationHasNoApprovedArtifacts() {
    var catalog = new AuthorReviewArtifactService("", mapper, new AuthorReviewManifestFileStore());
    assertThat(catalog.list()).isEmpty();
    assertThatThrownBy(() -> catalog.require("study-plan-review"))
        .hasMessageContaining("unavailable");
  }

  @Test
  void exactOperatorManifestLoadsAndUnknownArtifactFailsClosed() throws Exception {
    Path file = directory.resolve("manifest.json");
    Files.writeString(
        file,
        "[{\"artifactId\":\"study-plan-review\","
            + "\"artifactVersion\":\"dlv-704/review-2026-09-19.1\",\"contentHash\":\""
            + "a".repeat(64)
            + "\",\"ticketId\":\"DLV-704\"}]");
    var catalog =
        new AuthorReviewArtifactService(
            file.toString(), mapper, new AuthorReviewManifestFileStore());
    assertThat(catalog.require("study-plan-review").ticketId()).isEqualTo("DLV-704");
    assertThatThrownBy(() -> catalog.require("../../other")).hasMessageContaining("unavailable");
  }

  @Test
  void malformedOrExtraFieldsPreventStartup() throws Exception {
    Path file = directory.resolve("bad.json");
    Files.writeString(file, "[{\"artifactId\":\"spoof\"}]");
    assertThatThrownBy(() ->
            new AuthorReviewArtifactService(
                file.toString(), mapper, new AuthorReviewManifestFileStore()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            new AuthorReviewArtifactService(
                "relative.json", mapper, new AuthorReviewManifestFileStore()))
        .isInstanceOf(IllegalStateException.class);
    Files.writeString(file, "x".repeat(65537));
    assertThatThrownBy(() ->
            new AuthorReviewArtifactService(
                file.toString(), mapper, new AuthorReviewManifestFileStore()))
        .isInstanceOf(IllegalStateException.class);
  }
}
