package com.lookahead.domain.model;

import java.nio.file.Path;

/**
 * Explicit deployment selection; cloud content never falls back to a local mount.
 * The serving release exists in one place, live/&lt;tag&gt;/ (user decision 2026-10-28); retired
 * releases sit under archive/&lt;tag&gt;/ and are never read by a running task. A deployment either
 * pins one release (its Content calendar release tag plus both digests, read under live/&lt;tag&gt;/)
 * or pins nothing and serves the single complete release found under live/. The release identity
 * is the tag {@code vYYYY.MM.DD.HH.NN} (UTC hour plus a counter that starts at 00 and may exceed
 * 99 without leading zeros); it is never a content hash. The settings are read and checked by
 * {@code validator.PublicationSelectionValidator} (DLV-1016).
 */
public record PublicationSelection(boolean cloud, String region, String accountId, String bucket,
        String releaseId, String manifestSha256, String archiveSha256, Path directory,
        long cacheBytes, int cacheEntries) {
    public static final String LIVE_PREFIX = "live/";
    /** Content calendar release tag; the only accepted release identity in S3 keys, pins and manifests. */
    public static final String RELEASE_TAG = "v[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}\\.(?:[0-9]{2}|[1-9][0-9]{2,})";

    /** True when no release is pinned and the serving release is discovered under live/. */
    public boolean live() { return cloud && releaseId == null; }

    /** The environment stamp on every delivery ticket: {@code dev} when the cloud policy is on, else {@code local}. */
    public String ticketEnvironment() { return cloud ? "dev" : "local"; }
}
