package com.lookahead.domain.review;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ReviewArtifactCatalogTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    @Test void absentConfigurationHasNoApprovedArtifacts() {
        var catalog=new ReviewArtifactCatalog("",mapper);
        assertThat(catalog.list()).isEmpty();
        assertThatThrownBy(()->catalog.require("study-plan-review")).hasMessageContaining("unavailable");
    }
    @Test void exactOperatorManifestLoadsAndUnknownArtifactFailsClosed()throws Exception {
        Path file=directory.resolve("manifest.json");
        Files.writeString(file,"[{\"artifactId\":\"study-plan-review\",\"artifactVersion\":\"dlv-704/review-2026-09-19.1\",\"contentHash\":\""+"a".repeat(64)+"\",\"ticketId\":\"DLV-704\"}]");
        var catalog=new ReviewArtifactCatalog(file.toString(),mapper);
        assertThat(catalog.require("study-plan-review").ticketId()).isEqualTo("DLV-704");
        assertThatThrownBy(()->catalog.require("../../other")).hasMessageContaining("unavailable");
    }
    @Test void malformedOrExtraFieldsPreventStartup()throws Exception {
        Path file=directory.resolve("bad.json");Files.writeString(file,"[{\"artifactId\":\"spoof\"}]");
        assertThatThrownBy(()->new ReviewArtifactCatalog(file.toString(),mapper)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ReviewArtifactCatalog("relative.json",mapper)).isInstanceOf(IllegalStateException.class);
        Files.writeString(file,"x".repeat(65537));
        assertThatThrownBy(()->new ReviewArtifactCatalog(file.toString(),mapper)).isInstanceOf(IllegalStateException.class);
    }
}
