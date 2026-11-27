package com.lookahead.domain.db.store.testaccounts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The prepared custom test-plan fixtures on the task's own disk. File operations only; what the bundle must contain is
 * decided by {@code TestAccountScenarioPlanService}. DLV-1016: this read was written inside that service, then was a
 * static method here that the service called with a path it built; now the store is injected and only it builds the path.
 */
// Exception, decided 2026-11-25 (DLV-1016): db.store also holds the file-system stores; this one is the persistence
// layer of the test-plan fixture file. The full note is on content.PublicationFileStore.
@Component
public final class TestAccountPlanFixtureFileStore {
    /** The text of the file at {@code location}, or empty when it is a symbolic link or larger than {@code maximumBytes} (nothing is read then). */
    public Optional<String> text(String location, long maximumBytes) throws IOException {
        Path file = Path.of(location);
        if (Files.isSymbolicLink(file) || Files.size(file) > maximumBytes) return Optional.empty();
        return Optional.of(Files.readString(file));
    }
}
