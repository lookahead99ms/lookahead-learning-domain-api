package com.lookahead.domain.publication;

import com.lookahead.domain.HealthReport;
import com.lookahead.domain.HealthReport.Diagnosis;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness of the content release in cloud mode: loaded at startup and its extracted files still on the task's disk.
 * Local mode is always UP (content comes from a mounted folder). DLV-969 (owner, 2026-11-04): DOWN says why in the log
 * ({@link HealthReport}); the answer itself carries no details, and the log never names the private folder.
 */
final class ContentPublicationHealth implements HealthIndicator {
    private final PublicationLocation location;
    private final ProtectedContentPolicy policy;
    private final HealthReport report;

    ContentPublicationHealth(PublicationLocation location, ProtectedContentPolicy policy, Clock clock) {
        this.location = location; this.policy = policy;
        this.report = new HealthReport("content publication", "the content release is loaded and its files are on disk", clock);
    }

    @Override public Health health() {
        if (!location.cloud()) return Health.up().build();
        Diagnosis down = diagnose();
        report.report(down);
        return down == null ? Health.up().build() : Health.down().build();
    }

    Diagnosis diagnose() {
        if (policy.version().equals("disabled"))
            return new Diagnosis("CONTENT_DISABLED", "no content release is loaded: the content policy started without a publication manifest or content folder");
        if (!Files.isRegularFile(Path.of(location.manifest())))
            return new Diagnosis("MANIFEST_MISSING", "the loaded content release has lost its publication manifest (publication.json) from the task's disk");
        if (!Files.isRegularFile(Path.of(location.accountCatalog())))
            return new Diagnosis("CATALOG_MISSING", "the loaded content release has lost its trusted account catalog (account-catalog.json) from the task's disk");
        if (!Files.isDirectory(Path.of(location.contentRoot())))
            return new Diagnosis("CONTENT_ROOT_MISSING", "the loaded content release has lost its content folder from the task's disk");
        return null;
    }
}
