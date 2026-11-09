package com.lookahead.domain;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import static org.assertj.core.api.Assertions.assertThat;

/** Owner, 2026-11-09: a permission refusal by an IAM-governed service is an ERROR line; other upstream failures stay WARN. */
class EventsUpstreamLevelTest {
    @Test void aForbiddenUpstreamCallIsAnErrorAndOtherFailuresStayWarnings() {
        try (var log = LogCapture.start()) {
            Events.upstreamFailed("s3", "ListObjectsV2", System.nanoTime(), null, 403, "AccessDenied");
            Events.upstreamFailed("s3", "GetObject", System.nanoTime(), null, null, "AccessDeniedException");
            Events.upstreamFailed("s3", "PutObject", System.nanoTime(), null, 500, "InternalError");
            Events.upstreamFailed("ses", "SendEmail", System.nanoTime(), new IllegalStateException("synthetic"), null, null);
            Events.upstreamRefused("cognito", "GetUser", System.nanoTime(), "NotAuthorizedException");
            var lines = log.lines("upstream.call");
            assertThat(lines).hasSize(5);
            assertThat(lines.get(0).getLevel().toString()).isEqualTo(Level.ERROR.toString());
            assertThat(lines.get(0).getFormattedMessage()).isEqualTo("s3 ListObjectsV2 failed in 0 ms: HTTP 403, AccessDenied");
            assertThat(LogCapture.keys(lines.get(0))).containsEntry("upstream.status", 403).containsEntry("upstream.code", "AccessDenied").containsEntry("event.outcome", "failure");
            assertThat(lines.get(1).getLevel().toString()).isEqualTo(Level.ERROR.toString());
            assertThat(lines.get(2).getLevel().toString()).isEqualTo(Level.WARN.toString());
            assertThat(lines.get(3).getLevel().toString()).isEqualTo(Level.WARN.toString());
            assertThat(lines.get(4).getLevel().toString()).isEqualTo(Level.WARN.toString());
            log.containsNone("synthetic");
        }
    }
}
