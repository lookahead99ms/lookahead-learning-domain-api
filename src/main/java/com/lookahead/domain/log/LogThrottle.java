package com.lookahead.domain.log;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DLV-969 (owner, 2026-11-04): keeps a failure line on a hot path readable. A line is due the first
 * time its key (a reason code, with exception types when they matter) is seen, and again once
 * {@link #REPEAT} has passed while the failure persists; in between the request summary line
 * ({@link EventLog#requestSummary}) still records each request.
 */
public final class LogThrottle {
  public static final Duration REPEAT = Duration.ofMinutes(1);

  /** Keys are reason codes, so the map stays small; it is cleared rather than grown past this. */
  static final int KEYS = 256;

  private static final List<LogThrottle> ALL = new CopyOnWriteArrayList<>();
  private final Clock clock;
  private final ConcurrentHashMap<String, Instant> written = new ConcurrentHashMap<>();

  public LogThrottle() {
    this(Clock.systemUTC());
  }

  LogThrottle(Clock clock) {
    this.clock = clock;
    ALL.add(this);
  }

  /** For tests: every throttle forgets what it wrote, so the next line of each key is due again. */
  public static void forgetAll() {
    ALL.forEach(throttle -> throttle.written.clear());
  }

  /** True when the line for {@code key} should be written now. */
  public boolean due(String key) {
    Instant now = clock.instant();
    boolean[] due = {false};
    if (written.size() >= KEYS && !written.containsKey(key)) written.clear();
    written.compute(
        key,
        (name, at) -> {
          if (at != null && now.isBefore(at.plus(REPEAT))) return at;
          due[0] = true;
          return now;
        });
    return due[0];
  }
}
