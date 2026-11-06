package com.lookahead.domain.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLV-968 (owner decision 2026-11-06): every cross-repository contract lives only in Content, under
 * {@code docs/contracts/}; this repository keeps no copy. {@code scripts/fetch-contracts.sh} reads the files this
 * repository tests against from Content main into {@code .contracts/} (git-ignored; CI step "Fetch contracts from
 * Content" with {@code CONTENT_READ_TOKEN}, a developer's own {@code gh} login locally). {@code -Dcontracts.dir=<folder>}
 * points the tests at another folder. A missing file fails with the one message that names the fix.
 */
final class ContractFiles {
    static final String PROPERTY = "contracts.dir";
    static final String DEFAULT_DIRECTORY = ".contracts";
    static final String SCRIPT = "scripts/fetch-contracts.sh";
    /** JUnit tag of the tests that read a fetched file; the image build excludes it ({@code -DexcludedGroups}). */
    static final String TAG = "fetched-contract";

    private ContractFiles() {}

    static Path directory() {
        return Path.of(System.getProperty("user.dir")).resolve(System.getProperty(PROPERTY, DEFAULT_DIRECTORY));
    }

    static Path of(String file) { return directory().resolve(file); }

    static String missing(Path contract) {
        return "Contract file absent: " + contract + ". Contracts live only in Content (docs/contracts/); run " + SCRIPT
                + " to fetch them (CI: step \"Fetch contracts from Content\" with CONTENT_READ_TOKEN), or point -D" + PROPERTY
                + " at the folder that holds them.";
    }

    /** The fetched contract as JSON; a missing file fails with {@link #missing(Path)} and nothing else. */
    static JsonNode read(Path contract) throws IOException {
        assertThat(contract).as(missing(contract)).isRegularFile();
        return JsonMapper.builder().build().readTree(Files.readString(contract));
    }
}
