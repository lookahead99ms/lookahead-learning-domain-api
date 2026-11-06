package com.lookahead.domain.publication;

import com.lookahead.domain.HealthReport.Diagnosis;

/**
 * DLV-971 (owner, 2026-11-06): the publication tar served from {@code release/live/} (setting
 * {@code app.publication.live-prefix}). {@code version} is the tar's file name without {@code .tar}
 * ({@code v2026.11.06.16.00}) once loaded; {@code problem} says why readiness is DOWN when it is not: zero or more
 * than one tar, or a tar that failed its checks. The task stays up and unready instead of restarting.
 */
public record LiveRelease(String prefix, String version, Diagnosis problem) {
    /** Today's live/<tag>/ discovery or a pinned release: nothing to report here. */
    public static LiveRelease notConfigured() { return new LiveRelease(null, null, null); }
    public boolean configured() { return prefix != null; }
    public boolean loaded() { return configured() && problem == null; }
}
