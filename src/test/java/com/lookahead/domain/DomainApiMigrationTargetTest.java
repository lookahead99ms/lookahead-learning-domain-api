package com.lookahead.domain;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DomainApiMigrationTargetTest {
    @Test void acceptsOnlyTheDedicatedMigratorAtAnExplicitDatabase() {
        assertThatCode(() -> DomainApiMigration.validateMigrationTarget(LegacyStorageNames.MIGRATOR_ROLE, "jdbc:postgresql://database:5432/owned"))
                .doesNotThrowAnyException();
        assertThatIllegalStateException().isThrownBy(() -> DomainApiMigration.validateMigrationTarget("postgres", "jdbc:postgresql://database:5432/owned"));
        assertThatIllegalStateException().isThrownBy(() -> DomainApiMigration.validateMigrationTarget(LegacyStorageNames.RUNTIME_ROLE, "jdbc:postgresql://database:5432/owned"));
        assertThatIllegalStateException().isThrownBy(() -> DomainApiMigration.validateMigrationTarget("lookahead_domain_migrator", "jdbc:postgresql://database:5432/owned"));
    }
    @Test void urlCannotReplaceValidatedRoleOrSessionConfiguration() {
        for (String query : new String[]{"user=postgres", "password=private-value", "options=-c%20role%3Dpostgres", "currentSchema=another", "socketTimeout=0"})
            assertThatIllegalStateException().isThrownBy(() -> DomainApiMigration.validateMigrationTarget(LegacyStorageNames.MIGRATOR_ROLE, "jdbc:postgresql://database:5432/owned?" + query))
                    .withMessageNotContaining("private-value");
    }

    @Test void relocatedInitialMigrationPreservesTheInstalledChecksum() throws Exception {
        try (var script = getClass().getClassLoader().getResourceAsStream(LegacyStorageNames.INITIAL_MIGRATION_RESOURCE)) {
            assertThat(script).isNotNull();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.readAllBytes())))
                    .isEqualTo("0ee1758c0c67c224c59408ed7cf5433dcd5becb9ef181f64ac37e6d3ed33597f");
        }
    }
}
