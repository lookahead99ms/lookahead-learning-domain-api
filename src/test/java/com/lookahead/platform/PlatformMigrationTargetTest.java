package com.lookahead.platform;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlatformMigrationTargetTest {
    @Test void acceptsOnlyTheDedicatedMigratorAtAnExplicitDatabase() {
        assertThatCode(() -> PlatformMigration.validateMigrationTarget("lookahead_platform_migrator", "jdbc:postgresql://database:5432/owned"))
                .doesNotThrowAnyException();
        assertThatIllegalStateException().isThrownBy(() -> PlatformMigration.validateMigrationTarget("postgres", "jdbc:postgresql://database:5432/owned"));
        assertThatIllegalStateException().isThrownBy(() -> PlatformMigration.validateMigrationTarget("lookahead_platform_app", "jdbc:postgresql://database:5432/owned"));
    }
    @Test void urlCannotReplaceValidatedRoleOrSessionConfiguration() {
        for (String query : new String[]{"user=postgres", "password=private-value", "options=-c%20role%3Dpostgres", "currentSchema=another", "socketTimeout=0"})
            assertThatIllegalStateException().isThrownBy(() -> PlatformMigration.validateMigrationTarget("lookahead_platform_migrator", "jdbc:postgresql://database:5432/owned?" + query))
                    .withMessageNotContaining("private-value");
    }
}
