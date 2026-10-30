package com.lookahead.domain;

import com.lookahead.domain.database.DomainDatabaseConnections;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mockStatic;

class RetiredDatabaseEntrypointsTest {
    @Test
    void bootstrapCannotReadCredentialsOrOpenDatabaseThroughRetiredLauncher() {
        try (var connections = mockStatic(DomainDatabaseConnections.class)) {
            for (String[] arguments : new String[][] {new String[0], {"synthetic-sensitive-argument"}, null}) {
                assertThatIllegalStateException().isThrownBy(() -> DomainApiBootstrap.main(arguments))
                    .withMessage("Domain database bootstrap entrypoint is retired; use Infra Flyway Utils prepare");
            }
            connections.verifyNoInteractions();
        }
    }

    @Test
    void migrationCannotReadCredentialsOrOpenDatabaseThroughRetiredLauncher() {
        try (var connections = mockStatic(DomainDatabaseConnections.class)) {
            for (String[] arguments : new String[][] {new String[0], {"synthetic-sensitive-argument"}, null}) {
                assertThatIllegalStateException().isThrownBy(() -> DomainApiMigration.main(arguments))
                    .withMessage("Domain database migration entrypoint is retired; use Infra Flyway Utils");
            }
            connections.verifyNoInteractions();
        }
    }
}
