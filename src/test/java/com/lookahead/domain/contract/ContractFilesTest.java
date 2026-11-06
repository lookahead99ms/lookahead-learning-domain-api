package com.lookahead.domain.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fetched-contract folder: default, override and the one message a missing file gets. No network. */
class ContractFilesTest {
    @TempDir Path folder;

    @Test void defaultsToTheGitIgnoredFolderUnderTheProject() {
        String before = System.getProperty(ContractFiles.PROPERTY);
        System.clearProperty(ContractFiles.PROPERTY);
        try {
            assertThat(ContractFiles.of("release-layout.json"))
                    .isEqualTo(Path.of(System.getProperty("user.dir"), ".contracts", "release-layout.json"));
        } finally { if (before != null) System.setProperty(ContractFiles.PROPERTY, before); }
    }

    @Test void systemPropertyPointsAtAnotherFolder() {
        String before = System.getProperty(ContractFiles.PROPERTY);
        System.setProperty(ContractFiles.PROPERTY, folder.toString());
        try {
            assertThat(ContractFiles.of("domain-api-v1.json")).isEqualTo(folder.resolve("domain-api-v1.json"));
        } finally { if (before == null) System.clearProperty(ContractFiles.PROPERTY); else System.setProperty(ContractFiles.PROPERTY, before); }
    }

    @Test void missingFileFailsWithTheFetchInstruction() {
        Path absent = folder.resolve("release-layout.json");
        assertThatThrownBy(() -> ContractFiles.read(absent)).isInstanceOf(AssertionError.class)
                .hasMessageContaining(ContractFiles.missing(absent)).hasMessageContaining("scripts/fetch-contracts.sh")
                .hasMessageContaining("CONTENT_READ_TOKEN").hasMessageContaining("-Dcontracts.dir");
    }

    @Test void presentFileIsReadAsJson() throws Exception {
        Path present = folder.resolve("release-layout.json");
        Files.writeString(present, "{\"schema\":\"release-layout/v1\"}");
        assertThat(ContractFiles.read(present).path("schema").asString()).isEqualTo("release-layout/v1");
    }
}
