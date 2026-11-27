package com.lookahead.domain.validator;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.PublicationSelection;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Startup check of the publication settings: the environment and its profiles, and in DEV and PROD the bucket, the
 * release pin (all three values or none), the folder and the cache bounds. Each refusal first names the setting and
 * what is wrong with it, never its value. DLV-1016: these checks were {@code PublicationSelection.from} and moved here
 * word for word, in the same order.
 */
public final class PublicationSelectionValidator {
    private PublicationSelectionValidator() {}

    /** The selection, after the checks; Local selects nothing. */
    public static PublicationSelection validated(Environment env) {
        String mode = env.getRequiredProperty("app.deployment-environment");
        if (!CloudRuntimeValidator.ENVIRONMENTS.contains(mode)
                || "local".equals(mode) && env.acceptsProfiles(Profiles.of("dev", "prod", "production"))
                || !"local".equals(mode) && env.acceptsProfiles(Profiles.of("local", "local-test")))
            throw EventLog.startupRefuse("app.deployment-environment (LOOKAHEAD_ENVIRONMENT)", "ENVIRONMENT_INCONSISTENT",
                    "must be local, dev or prod and agree with the active profiles (local and local-test only in local; dev, prod and production never in local)",
                    "Explicit consistent publication environment required");
        if (mode.equals("local")) return new PublicationSelection(false, null, null, null, null, null, null, null, 0, 0);
        String region = required(env, "region"), account = required(env, "account-id"), bucket = required(env, "bucket");
        String release = optional(env, "release-id"), manifest = optional(env, "manifest-sha256"), archive = optional(env, "archive-sha256");
        boolean pinned = release != null;
        if (pinned != (manifest != null) || pinned != (archive != null))
            throw EventLog.startupRefuse("LOOKAHEAD_PUBLICATION_RELEASE_ID, LOOKAHEAD_PUBLICATION_MANIFEST_SHA256 and LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256",
                    "PUBLICATION_PIN_INCOMPLETE", "must be all three set (a pinned release) or all three unset (the live release); only some are set",
                    "Pin a release with its calendar tag and both digests, or leave all three unset to serve the live release");
        Path directory = Path.of(required(env, "directory"));
        long bytes = env.getProperty("app.publication.cache-bytes", Long.class, 32L * 1024 * 1024);
        int entries = env.getProperty("app.publication.cache-entries", Integer.class, 256);
        valid(region.matches("[a-z]{2}-[a-z]+-[1-9]"), "region", "is not an AWS region name such as us-east-1");
        valid(account.matches("[0-9]{12}"), "account-id", "is not a 12-digit AWS account ID");
        valid(bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"), "bucket", "is not a valid S3 bucket name");
        if (pinned) {
            valid(release.matches(PublicationSelection.RELEASE_TAG), "release-id", "is not a Content calendar release tag vYYYY.MM.DD.HH.NN");
            valid(manifest.matches("[a-f0-9]{64}"), "manifest-sha256", "is not 64 lowercase hexadecimal characters");
            valid(archive.matches("[a-f0-9]{64}"), "archive-sha256", "is not 64 lowercase hexadecimal characters");
        }
        valid(directory.isAbsolute() && directory.normalize().equals(directory) && directory.startsWith(Path.of("/tmp")) && directory.getNameCount() >= 2,
                "directory", "must be an absolute, normalized folder inside /tmp");
        valid(bytes >= 0 && bytes <= 64L * 1024 * 1024, "cache-bytes", "must be 0 to 67108864 (64 MiB)");
        valid(entries >= 1 && entries <= 1024, "cache-entries", "must be 1 to 1024");
        return new PublicationSelection(true, region, account, bucket, release, manifest, archive, directory, bytes, entries);
    }
    private static String required(Environment env, String field) {
        String value = optional(env, field);
        if (value == null) throw EventLog.startupRefuse(setting(field), "SETTING_MISSING", "is not set", "Missing cloud publication " + field);
        return value;
    }
    private static void valid(boolean condition, String field, String problem) {
        if (!condition) throw EventLog.startupRefuse(setting(field), "SETTING_INVALID", problem, "Invalid cloud publication configuration");
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
