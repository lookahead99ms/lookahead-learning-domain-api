package com.lookahead.domain.publication;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Explicit deployment selection; cloud content never falls back to a local mount. */
public record PublicationSettings(boolean cloud, String region, String accountId, String bucket,
        String releaseId, String manifestSha256, String archiveSha256, Path directory,
        long cacheBytes, int cacheEntries) {
    public static PublicationSettings from(Environment env) {
        String mode = env.getRequiredProperty("app.deployment-environment");
        if (!Set.of("local", "dev", "prod").contains(mode)
                || "local".equals(mode) && env.acceptsProfiles(Profiles.of("dev", "prod", "production"))
                || !"local".equals(mode) && env.acceptsProfiles(Profiles.of("local", "local-test")))
            throw new IllegalStateException("Explicit consistent publication environment required");
        if (mode.equals("local")) return new PublicationSettings(false, null, null, null, null, null, null, null, 0, 0);
        String region = required(env, "region"), account = required(env, "account-id"), bucket = required(env, "bucket");
        String release = required(env, "release-id"), manifest = required(env, "manifest-sha256"), archive = required(env, "archive-sha256");
        Path directory = Path.of(required(env, "directory"));
        long bytes = env.getProperty("app.publication.cache-bytes", Long.class, 32L * 1024 * 1024);
        int entries = env.getProperty("app.publication.cache-entries", Integer.class, 256);
        if (!region.matches("[a-z]{2}-[a-z]+-[1-9]") || !account.matches("[0-9]{12}")
                || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                || !release.matches("release-[a-f0-9]{24}") || !manifest.matches("[a-f0-9]{64}") || !archive.matches("[a-f0-9]{64}")
                || !directory.isAbsolute() || !directory.normalize().equals(directory) || !directory.startsWith(Path.of("/tmp")) || directory.getNameCount() < 2
                || bytes < 0 || bytes > 64L * 1024 * 1024 || entries < 1 || entries > 1024)
            throw new IllegalStateException("Invalid cloud publication configuration");
        return new PublicationSettings(true, region, account, bucket, release, manifest, archive, directory, bytes, entries);
    }
    private static String required(Environment env, String field) {
        String value = env.getProperty("app.publication." + field);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing cloud publication " + field);
        return value;
    }
}
