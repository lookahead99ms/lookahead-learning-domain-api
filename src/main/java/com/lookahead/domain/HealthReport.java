package com.lookahead.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DLV-969 (owner, 2026-11-04): the log of one readiness check on {@code lookahead.health}. The health answer carries no
 * details, so the log says why: DOWN with a stable reason code and words when it changes and again every minute while
 * it lasts, so each failed container health check has its reason nearby; UP once when it is reached. The last state
 * of every check is kept for the shutdown line ({@link LifecycleLog}).
 */
public final class HealthReport {
    public static final Duration REPEAT = Duration.ofMinutes(1);
    static final Logger LOG = LoggerFactory.getLogger("lookahead.health");
    private static final Map<String, String> LAST = new ConcurrentHashMap<>();

    /** Why a check is DOWN: a stable code such as {@code MIGRATIONS_MISSING} and a sentence without secrets. */
    public record Diagnosis(String reason, String words) { }

    private final String component;
    private final String upWords;
    private final Clock clock;
    private String reported;
    private Instant reportedAt = Instant.MIN;

    /** {@code component} names the check in the log, e.g. "database"; {@code upWords} is the UP line. */
    public HealthReport(String component, String upWords, Clock clock) {
        this.component = component; this.upWords = upWords; this.clock = clock;
    }

    /** {@code down} is null when the check passed. */
    public synchronized void report(Diagnosis down) {
        LAST.put(component, down == null ? "UP" : "DOWN " + down.reason());
        String now = down == null ? "UP" : down.reason() + ": " + down.words();
        Instant at = clock.instant();
        boolean changed = !now.equals(reported);
        if (!changed && (down == null || at.isBefore(reportedAt.plus(REPEAT)))) return;
        reported = now;
        reportedAt = at;
        if (down == null) {
            Events.success(LOG, "readiness").addKeyValue("health.component", component).log("Readiness UP: {}", upWords);
            return;
        }
        Events.failed(LOG, "readiness", down.reason()).addKeyValue("health.component", component)
                .log("Readiness DOWN: {}; the container health check fails until this is fixed", down.words());
    }

    /** The last state of every check, e.g. "content publication UP, database DOWN MIGRATIONS_MISSING". */
    public static String last() {
        if (LAST.isEmpty()) return "no readiness check has run yet";
        var sorted = new TreeMap<>(LAST);
        var text = new StringBuilder();
        sorted.forEach((name, state) -> text.append(text.isEmpty() ? "" : ", ").append(name).append(' ').append(state));
        return text.toString();
    }

    /** For tests: forget the states kept for the shutdown line. */
    static void forget() { LAST.clear(); }
}
