package com.lookahead.domain.delivery;

import com.lookahead.domain.Events;
import com.lookahead.domain.publication.LiveRelease;
import com.lookahead.domain.publication.PublicationLocation;
import com.lookahead.learning.content.exception.AccountFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-998 (owner, 2026-11-09): the plan file travels with the publication and the Domain merges it at start, the way
 * it loads content. The release carries the repository's {@code runtime/delivery/delivery-plan.json} as the staff asset
 * {@code content/staff/delivery-plan.json} (a local Domain serving the repository tree reads {@code delivery/delivery-plan.json}
 * under its content root). At each start, after the legacy import and before the sweeps:
 * <ul>
 * <li>no {@code delivery/plan.json} yet: the released plan becomes the first plan as it is (its own version);</li>
 * <li>otherwise the released plan becomes the inbox and the existing three-way merge runs at once, so DEV edits win
 *     a conflict exactly as with Upload plan file; its {@code baseVersion} (else {@code version}) must be the DEV plan
 *     or an archived one, or the file is left alone and logged;</li>
 * <li>the SHA-256 of the imported bytes is kept in {@code inbox/.release-import.json}; a restart on the same release
 *     imports nothing, so the plan version does not climb;</li>
 * <li>a file already in the inbox (an owner's upload) is never replaced; the import waits for the next start.</li>
 * </ul>
 * DEV and local only, like every other plan write: in prod nothing runs. Failures are logged and never stop the start.
 */
public final class ReleasePlanImport {
    public static final String MARKER = "inbox/.release-import.json";
    static final String MARKER_SCHEMA = "lookahead.release-plan-import/v1";
    static final String STAFF_ASSET = "staff/delivery-plan.json", REPOSITORY_FILE = "delivery/delivery-plan.json";

    /** What one start did: {@code seeded}, {@code merged} or {@code skipped} (then {@code reason} says why), and the plan version after. */
    public record Outcome(String action, String reason, long planVersion) {
        static Outcome skipped(String reason, long version) { return new Outcome("skipped", reason, version); }
    }

    private final DeliveryPlanStore store;
    private final DeliveryPlanService plans;
    private final PublicationLocation location;
    private final LiveRelease release;
    private final boolean allowed;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReleasePlanImport(DeliveryPlanStore store, DeliveryPlanService plans, PublicationLocation location, LiveRelease release,
            boolean allowed, ObjectMapper mapper, Clock clock) {
        this.store = store; this.plans = plans; this.location = location; this.release = release; this.allowed = allowed; this.mapper = mapper; this.clock = clock;
    }

    /** The released plan file: the staff asset of a publication, else the repository file of a local content tree; empty when neither exists. */
    Optional<Path> source() {
        if (location == null || location.contentRoot() == null || location.contentRoot().isBlank()) return Optional.empty();
        Path root = Path.of(location.contentRoot());
        for (String candidate : new String[] {STAFF_ASSET, REPOSITORY_FILE}) {
            Path file = root.resolve(candidate);
            if (Files.isRegularFile(file)) return Optional.of(file);
        }
        return Optional.empty();
    }

    /** Runs the import once; answers what it did. Storage failures surface as {@link AccountFailure} for the caller's guard. */
    public Outcome run() {
        if (!allowed) return Outcome.skipped("plan writes exist on DEV and local only", 0);
        var file = source();
        if (file.isEmpty()) return Outcome.skipped("no plan file in the publication", store.current().map(DeliveryPlanStore.Loaded::version).orElse(0L));
        byte[] bytes;
        try { bytes = Files.readAllBytes(file.get()); }
        catch (IOException unreadable) { return refused("RELEASE_PLAN_UNREADABLE", "the file could not be read (" + unreadable.getClass().getSimpleName() + ")"); }
        ObjectNode plan;
        try { plan = store.inboxCandidate(bytes); }
        catch (AccountFailure invalid) { return refused("RELEASE_PLAN_INVALID", invalid.getMessage()); }
        String digest = sha256(bytes);
        var marker = store.releaseImport();
        if (marker.isPresent() && digest.equals(marker.get().path("sha256").asString()))
            return Outcome.skipped("already imported (" + marker.get().path("release").asString() + ")", store.current().map(DeliveryPlanStore.Loaded::version).orElse(0L));
        var current = store.current();
        if (current.isEmpty()) {
            if (store.seed(bytes)) {
                long version = plan.path("version").asLong();
                record(digest, version);
                Events.success(Events.EVENT, "delivery.release-import").addKeyValue("action", "seeded").addKeyValue("plan.version", version)
                        .addKeyValue("items", plan.path("workItems").size()).addKeyValue("release", releaseName()).addKeyValue("file", file.get().getFileName().toString())
                        .log("Delivery plan seeded from the publication ({}): version {}, {} items", releaseName(), version, plan.path("workItems").size());
                return new Outcome("seeded", null, version);
            }
            current = store.current();
            if (current.isEmpty()) throw DeliveryPlanStore.unavailable();
        }
        if (store.inbox().isPresent()) return refused("INBOX_OCCUPIED", "the inbox already holds a file; it is merged first and the release is imported at the next start");
        long baseVersion = plan.path("baseVersion").isIntegralNumber() ? plan.path("baseVersion").asLong() : plan.path("version").asLong();
        if (baseVersion != current.get().version() && store.archived(baseVersion).isEmpty())
            return refused("BASE_VERSION_UNKNOWN", "the released plan says it was edited from version " + baseVersion + ", but DEV is at version "
                    + current.get().version() + " and has no archived version " + baseVersion);
        store.putInbox(bytes);
        DeliveryPlanService.Sync sync = plans.syncInbox();
        if (!sync.synced()) return Outcome.skipped(sync.reason(), sync.version());
        record(digest, sync.version());
        Events.success(Events.EVENT, "delivery.release-import").addKeyValue("action", "merged").addKeyValue("plan.version", sync.version())
                .addKeyValue("plan.base", baseVersion).addKeyValue("release", releaseName()).addKeyValue("file", file.get().getFileName().toString())
                .log("Delivery plan from the publication ({}) merged into plan version {}: {}", releaseName(), sync.version(), sync.counts().words());
        return new Outcome("merged", null, sync.version());
    }

    private Outcome refused(String reason, String words) {
        Events.failed(Events.EVENT, "delivery.release-import", reason).addKeyValue("release", releaseName())
                .log("The published delivery plan was not imported: {}", words);
        return Outcome.skipped(words, store.current().map(DeliveryPlanStore.Loaded::version).orElse(0L));
    }

    private void record(String digest, long version) {
        ObjectNode marker = mapper.createObjectNode();
        marker.put("schema", MARKER_SCHEMA).put("sha256", digest).put("release", releaseName()).put("planVersion", version).put("importedAt", clock.instant().toString());
        store.recordReleaseImport(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(marker));
    }

    /** The release tag when the tar came from release/live/, else {@code local}. */
    String releaseName() { return release != null && release.version() != null ? release.version() : "local"; }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
}
