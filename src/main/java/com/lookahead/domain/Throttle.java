package com.lookahead.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DLV-969 (owner, 2026-11-04): keeps a failure line on a hot path readable. A line is due the first time its key (a
 * reason code, with exception types when they matter) is seen, and again once {@link #REPEAT} has passed while the
 * failure persists; in between the request summary line ({@link RequestLog}) still records each request.
 */
public final class Throttle {
    public static final Duration REPEAT = Duration.ofMinutes(1);
    /** Keys are reason codes, so the map stays small; it is cleared rather than grown past this. */
    static final int KEYS = 256;
    private static final java.util.List<Throttle> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> written = new ConcurrentHashMap<>();

    public Throttle() { this(Clock.systemUTC()); }
    Throttle(Clock clock) { this.clock = clock; ALL.add(this); }

    /** For tests: every throttle forgets what it wrote, so the next line of each key is due again. */
    public static void forgetAll() { ALL.forEach(throttle -> throttle.written.clear()); }

    /** True when the line for {@code key} should be written now. */
    public boolean due(String key) {
        Instant now = clock.instant();
        boolean[] due = {false};
        if (written.size() >= KEYS && !written.containsKey(key)) written.clear();
        written.compute(key, (name, at) -> {
            if (at != null && now.isBefore(at.plus(REPEAT))) return at;
            due[0] = true;
            return now;
        });
        return due[0];
    }
}
