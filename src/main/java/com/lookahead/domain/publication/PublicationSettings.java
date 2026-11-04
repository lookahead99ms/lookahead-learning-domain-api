package com.lookahead.domain.publication;

import com.lookahead.domain.StartupChecks;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Explicit deployment selection; cloud content never falls back to a local mount.
 * The serving release exists in one place, live/&lt;tag&gt;/ (user decision 2026-10-28); retired
 * releases sit under archive/&lt;tag&gt;/ and are never read by a running task. A deployment either
 * pins one release (its Content calendar release tag plus both digests, read under live/&lt;tag&gt;/)
 * or pins nothing and serves the single complete release found under live/. The release identity
 * is the tag {@code vYYYY.MM.DD.HH.NN} (UTC hour plus a counter that starts at 00 and may exceed
 * 99 without leading zeros); it is never a content hash.
 */
public record PublicationSettings(boolean cloud, String region, String accountId, String bucket,
        String releaseId, String manifestSha256, String archiveSha256, Path directory,
        long cacheBytes, int cacheEntries) {
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
            throw StartupChecks.refuse("app.deployment-environment (LOOKAHEAD_ENVIRONMENT)", "ENVIRONMENT_INCONSISTENT",
                    "must be local, dev or prod and agree with the active profiles (local and local-test only in local; dev, prod and production never in local)",
                    "Explicit consistent publication environment required");
        if (mode.equals("local")) return new PublicationSettings(false, null, null, null, null, null, null, null, 0, 0);
        String region = required(env, "region"), account = required(env, "account-id"), bucket = required(env, "bucket");
        String release = optional(env, "release-id"), manifest = optional(env, "manifest-sha256"), archive = optional(env, "archive-sha256");
        boolean pinned = release != null;
        if (pinned != (manifest != null) || pinned != (archive != null))
            throw StartupChecks.refuse("LOOKAHEAD_PUBLICATION_RELEASE_ID, LOOKAHEAD_PUBLICATION_MANIFEST_SHA256 and LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256",
                    "PUBLICATION_PIN_INCOMPLETE", "must be all three set (a pinned release) or all three unset (the live release); only some are set",
                    "Pin a release with its calendar tag and both digests, or leave all three unset to serve the live release");
        Path directory = Path.of(required(env, "directory"));
        long bytes = env.getProperty("app.publication.cache-bytes", Long.class, 32L * 1024 * 1024);
        int entries = env.getProperty("app.publication.cache-entries", Integer.class, 256);
        valid(region.matches("[a-z]{2}-[a-z]+-[1-9]"), "region", "is not an AWS region name such as us-east-1");
        valid(account.matches("[0-9]{12}"), "account-id", "is not a 12-digit AWS account ID");
        valid(bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"), "bucket", "is not a valid S3 bucket name");
        if (pinned) {
            valid(release.matches(RELEASE_TAG), "release-id", "is not a Content calendar release tag vYYYY.MM.DD.HH.NN");
            valid(manifest.matches("[a-f0-9]{64}"), "manifest-sha256", "is not 64 lowercase hexadecimal characters");
            valid(archive.matches("[a-f0-9]{64}"), "archive-sha256", "is not 64 lowercase hexadecimal characters");
        }
        valid(directory.isAbsolute() && directory.normalize().equals(directory) && directory.startsWith(Path.of("/tmp")) && directory.getNameCount() >= 2,
                "directory", "must be an absolute, normalized folder inside /tmp");
        valid(bytes >= 0 && bytes <= 64L * 1024 * 1024, "cache-bytes", "must be 0 to 67108864 (64 MiB)");
        valid(entries >= 1 && entries <= 1024, "cache-entries", "must be 1 to 1024");
        return new PublicationSettings(true, region, account, bucket, release, manifest, archive, directory, bytes, entries);
    }
    private static String required(Environment env, String field) {
        String value = optional(env, field);
        if (value == null) throw StartupChecks.refuse(setting(field), "SETTING_MISSING", "is not set", "Missing cloud publication " + field);
        return value;
    }
    private static void valid(boolean condition, String field, String problem) {
        if (!condition) throw StartupChecks.refuse(setting(field), "SETTING_INVALID", problem, "Invalid cloud publication configuration");
    }
    /** The property and the environment variable that sets it (application.yml). */
    static String setting(String field) {
        String variable = switch (field) {
            case "region" -> "AWS_REGION";
            case "account-id" -> "LOOKAHEAD_AWS_ACCOUNT_ID";
            case "bucket" -> "LOOKAHEAD_PUBLICATION_BUCKET";
            case "release-id" -> "LOOKAHEAD_PUBLICATION_RELEASE_ID";
            case "manifest-sha256" -> "LOOKAHEAD_PUBLICATION_MANIFEST_SHA256";
            case "archive-sha256" -> "LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256";
            case "directory" -> "LOOKAHEAD_PUBLICATION_DIRECTORY";
            case "cache-bytes" -> "LOOKAHEAD_CONTENT_CACHE_BYTES";
            default -> "LOOKAHEAD_CONTENT_CACHE_ENTRIES";
        };
        return "app.publication." + field + " (" + variable + ")";
    }
    private static String optional(Environment env, String field) {
        String value = env.getProperty("app.publication." + field);
        return value == null || value.isBlank() ? null : value;
    }
}
