package com.lookahead.domain.health;

import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationLiveRelease;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.HealthLog;
import com.lookahead.domain.model.HealthDiagnosis;
import com.lookahead.domain.db.store.content.PublicationFileStore;
import java.time.Clock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness of the content release in cloud mode: loaded at startup and its extracted files still on the task's disk.
 * Local mode is always UP (content comes from a mounted folder). DLV-969 (owner, 2026-11-04): DOWN says why in the log
 * ({@link HealthLog}); the answer itself carries no details, and the log never names the private folder.
 */
public final class PublicationHealthIndicator implements HealthIndicator {
    private final PublicationLocation location;
    private final ProtectedContentManifestService policy;
    private final PublicationLiveRelease release;
    private final PublicationFileStore files;
    private final HealthLog healthLog;

    /** DLV-971: with the live tar configured, the answer carries the served version and DOWN says which tar problem. */
    public PublicationHealthIndicator(PublicationLocation location, ProtectedContentManifestService policy, PublicationLiveRelease release, PublicationFileStore files, Clock clock) {
        this.location = location; this.policy = policy; this.release = release; this.files = files;
        this.healthLog = new HealthLog("content publication", "the content release is loaded and its files are on disk", clock);
    }

    @Override public Health health() {
        if (!location.cloud()) return Health.up().build();
        HealthDiagnosis down = diagnose();
        EventLog.readiness(healthLog, down);
        var health = down == null ? Health.up() : Health.down();
        if (release.loaded()) health = health.withDetail("release", release.version());
        return health.build();
    }

    HealthDiagnosis diagnose() {
        if (release.configured() && release.problem() != null) return release.problem();
        if (policy.version().equals("disabled"))
            return new HealthDiagnosis("CONTENT_DISABLED", "no content release is loaded: the content policy started without a publication manifest or content folder");
        if (!files.holdsFile(location.manifest()))
            return new HealthDiagnosis("MANIFEST_MISSING", "the loaded content release has lost its publication manifest (publication.json) from the task's disk");
        if (!files.holdsFile(location.accountCatalog()))
            return new HealthDiagnosis("CATALOG_MISSING", "the loaded content release has lost its trusted account catalog (account-catalog.json) from the task's disk");
        if (!files.holdsFolder(location.contentRoot()))
            return new HealthDiagnosis("CONTENT_ROOT_MISSING", "the loaded content release has lost its content folder from the task's disk");
        return null;
    }
}
