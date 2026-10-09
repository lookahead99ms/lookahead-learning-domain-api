package com.lookahead.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

/** The release coverage gate must execute persistence suites, never silently skip them. */
@EnabledIfSystemProperty(named = "coverage.required", matches = "true")
class CoverageFixtureAvailabilityTest {
    @Test void bothDisposableDatabaseFixturesAreConfigured() throws Exception {
        assertThat(System.getenv("CLOUD_AUTH_TEST_DATABASE_URL"))
            .isEqualTo("jdbc:postgresql://127.0.0.1:4393/lookahead_domain_cloud_auth");
        assertThat(System.getenv("DLV921_DATABASE_URL"))
            .isEqualTo("jdbc:postgresql://127.0.0.1:4392/lookahead_domain_review");
        for (String name : new String[]{"CLOUD_AUTH_TEST_PASSWORD_FILE", "DLV921_DATABASE_PASSWORD_FILE"}) {
            assertThat(System.getenv(name)).as("required fixture password file path %s", name).isNotBlank();
            assertThat(Files.size(Path.of(System.getenv(name)))).isGreaterThan(0);
        }
    }
}
