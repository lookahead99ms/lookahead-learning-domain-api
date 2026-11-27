package com.lookahead.domain.db.store.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The operator-supplied author review manifest on the task's own disk. File operations only; what the manifest must
 * contain, and that its location must be absolute, is decided by {@code AuthorReviewArtifactService}. DLV-1016: this
 * read was written inside that service, then was a static method here that the service called with a path it built;
 * now the store is injected and only it builds the path.
 */
// Exception, decided 2026-11-25 (DLV-1016): db.store also holds the file-system stores; this one is the persistence
// layer of the author review manifest file. The full note is on content.PublicationFileStore.
@Component
public final class AuthorReviewManifestFileStore {
    /** True when {@code location} is an absolute path. */
    public boolean absolute(String location) { return Path.of(location).isAbsolute(); }

    /** The bytes of the file at {@code location}, or empty when the file is larger than {@code maximumBytes} (nothing is read then). */
    public Optional<byte[]> bytes(String location, long maximumBytes) throws IOException {
        Path file = Path.of(location);
        if (Files.size(file) > maximumBytes) return Optional.empty();
        return Optional.of(Files.readAllBytes(file));
    }
}
