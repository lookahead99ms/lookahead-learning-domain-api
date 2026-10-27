package com.lookahead.domain.publication;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Explicit deployment selection; cloud content never falls back to a local mount.
 * A deployment either pins one release (its Content calendar release tag plus both digests, read
 * under releases/&lt;tag&gt;/) or pins nothing and serves the single release that promotion placed
 * under live/&lt;tag&gt;/. The release identity is the tag {@code vYYYY.MM.DD.HH.NN} (UTC hour plus a
 * counter that starts at 00 and may exceed 99 without leading zeros); it is never a content hash.
 */
public record PublicationSettings(boolean cloud, String region, String accountId, String bucket,
        String releaseId, String manifestSha256, String archiveSha256, Path directory,
        long cacheBytes, int cacheEntries) {
    static final String RELEASES_PREFIX = "releases/";
    static final String LIVE_PREFIX = "live/";
    /** Content calendar release tag; the only accepted release identity in S3 keys, pins and manifests. */
    static final String RELEASE_TAG = "v[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}\\.(?:[0-9]{2}|[1-9][0-9]{2,})";

    /** True when no release is pinned and the serving release is discovered under live/. */
    public boolean live() { return cloud && releaseId == null; }

    public static PublicationSettings from(Environment env) {
        String mode = env.getRequiredProperty("app.deployment-environment");
        if (!Set.of("local", "dev", "prod").contains(mode)
                || "local".equals(mode) && env.acceptsProfiles(Profiles.of("dev", "prod", "production"))
                || !"local".equals(mode) && env.acceptsProfiles(Profiles.of("local", "local-test")))
            throw new IllegalStateException("Explicit consistent publication environment required");
        if (mode.equals("local")) return new PublicationSettings(false, null, null, null, null, null, null, null, 0, 0);
        String region = required(env, "region"), account = required(env, "account-id"), bucket = required(env, "bucket");
        String release = optional(env, "release-id"), manifest = optional(env, "manifest-sha256"), archive = optional(env, "archive-sha256");
        boolean pinned = release != null;
        if (pinned != (manifest != null) || pinned != (archive != null))
            throw new IllegalStateException("Pin a release with its calendar tag and both digests, or leave all three unset to serve the live release");
        Path directory = Path.of(required(env, "directory"));
        long bytes = env.getProperty("app.publication.cache-bytes", Long.class, 32L * 1024 * 1024);
        int entries = env.getProperty("app.publication.cache-entries", Integer.class, 256);
        if (!region.matches("[a-z]{2}-[a-z]+-[1-9]") || !account.matches("[0-9]{12}")
                || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                || pinned && (!release.matches(RELEASE_TAG) || !manifest.matches("[a-f0-9]{64}") || !archive.matches("[a-f0-9]{64}"))
                || !directory.isAbsolute() || !directory.normalize().equals(directory) || !directory.startsWith(Path.of("/tmp")) || directory.getNameCount() < 2
                || bytes < 0 || bytes > 64L * 1024 * 1024 || entries < 1 || entries > 1024)
            throw new IllegalStateException("Invalid cloud publication configuration");
        return new PublicationSettings(true, region, account, bucket, release, manifest, archive, directory, bytes, entries);
    }
    private static String required(Environment env, String field) {
        String value = optional(env, field);
        if (value == null) throw new IllegalStateException("Missing cloud publication " + field);
        return value;
    }
    private static String optional(Environment env, String field) {
        String value = env.getProperty("app.publication." + field);
        return value == null || value.isBlank() ? null : value;
    }
}
