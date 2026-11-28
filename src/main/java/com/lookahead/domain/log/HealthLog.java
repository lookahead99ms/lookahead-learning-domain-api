package com.lookahead.domain.log;

import com.lookahead.domain.model.HealthDiagnosis;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DLV-969 (owner, 2026-11-04): the state of one readiness check behind its log line on {@code
 * lookahead.health} (DLV-1016: the line is written by {@link EventLog#readiness}, which the
 * indicator calls; this class only says when a line is due). The health answer carries no details,
 * so the log says why: DOWN with a stable reason code and words when it changes and again every
 * minute while it lasts, so each failed container health check has its reason nearby; UP once when
 * it is reached. The last state of every check is kept for the shutdown line ({@link
 * LifecycleLog}).
 *
 * <p>Plain logs (owner, 2026-11-17): a DOWN readiness is something an operator must act on, so it
 * is an ERROR line that names the component and the reason code in the sentence and adds a hint
 * where the code knows the cure: "{@code Readiness DOWN for dynamodb: the DynamoDB data is at
 * version 4 but this release requires 5 (DATA_VERSION_BEHIND); run 07 · Migrate DEV data with this
 * release, then deploy again; the container health check fails until then}". UP is INFO: "{@code
 * Readiness UP for dynamodb: DynamoDB tables answer and the release data requirement is met}".
 */
public final class HealthLog {
  public static final Duration REPEAT = Duration.ofMinutes(1);
  private static final Map<String, String> LAST = new ConcurrentHashMap<>();

  private final String component;
  private final String upWords;
  private final Clock clock;
  private String reported;
  private Instant reportedAt = Instant.MIN;

  /**
   * {@code component} names the check in the log, e.g. "dynamodb"; {@code upWords} is the UP line.
   */
  public HealthLog(String component, String upWords, Clock clock) {
    this.component = component;
    this.upWords = upWords;
    this.clock = clock;
  }

  public String component() {
    return component;
  }

  public String upWords() {
    return upWords;
  }

  /**
   * Records the state of this check and says whether its line is due: when the state changed, and
   * for DOWN again every minute while it lasts. {@code down} is null when the check passed. {@link
   * EventLog#readiness} calls this and writes the line while holding this object's lock, so the
   * lines of one check keep their order.
   */
  synchronized boolean due(HealthDiagnosis down) {
    LAST.put(component, down == null ? "UP" : "DOWN " + down.reason());
    String now = down == null ? "UP" : down.reason() + ": " + down.words();
    Instant at = clock.instant();
    boolean changed = !now.equals(reported);
    if (!changed && (down == null || at.isBefore(reportedAt.plus(REPEAT)))) return false;
    reported = now;
    reportedAt = at;
    return true;
  }

  /**
   * The last state of every check, e.g. "content publication UP, dynamodb DOWN
   * DATA_VERSION_BEHIND".
   */
  public static String last() {
    if (LAST.isEmpty()) return "no readiness check has run yet";
    var sorted = new TreeMap<>(LAST);
    var text = new StringBuilder();
    sorted.forEach((name, state) ->
        text.append(text.isEmpty() ? "" : ", ").append(name).append(' ').append(state));
    return text.toString();
  }

  /** For tests: forget the states kept for the shutdown line. */
  static void forget() {
    LAST.clear();
  }
}
