package com.lookahead.domain.util;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.model.AccountMergeModels.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The checks and limits of the account merge. DLV-1016: these were static members of
 * AccountMergeService and the store called them; now only the service does, between the store's
 * reads and its fenced commit.
 */
public final class AccountMergeRules {
  private AccountMergeRules() {}

  public static final Duration LINK_LIFETIME = Duration.ofMinutes(30);
  public static final int REQUESTS_PER_HOUR = 3;

  /** The hour of {@link #REQUESTS_PER_HOUR}: links made longer ago do not count. */
  public static final Duration REQUEST_WINDOW = Duration.ofHours(1);

  /** A link older than this is deleted by the next request for its email. */
  public static final Duration LINK_RETENTION = Duration.ofDays(1);

  /**
   * A request that loses the race for its email reads again; after this many attempts it gives up.
   */
  public static final int REQUEST_ATTEMPTS = 3;

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The stored form of a link's token; a token of another shape is no link at all. */
  public static String digest(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
    return Sha256.hex(token);
  }

  /**
   * An account the owner or operator disabled is neither kept nor deleted here; fewer than two
   * leaves nothing to merge.
   */
  public static void requireMergeable(List<Member> members) {
    if (members.size() < 2 || members.stream().anyMatch(m -> !m.enabled() || m.state() != null))
      throw conflict();
  }

  /** The kept account must be able to sign in, and an author account is never the one deleted. */
  public static void requireKeepable(List<Member> members, UUID keep) {
    Member kept =
        members.stream()
            .filter(m -> m.id().equals(keep))
            .findFirst()
            .orElseThrow(AccountMergeRules::keepNotCandidate);
    if (!kept.admitted() || members.stream().anyMatch(m -> m.author() && !m.id().equals(keep)))
      throw keepNotCandidate();
  }

  public static List<Candidate> sorted(List<Candidate> candidates) {
    return candidates.stream()
        .sorted(
            Comparator.comparing(
                Candidate::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  public static Map<String, Object> method(String provider, String email) {
    var method = new LinkedHashMap<String, Object>();
    method.put("provider", provider);
    if (email != null) method.put("email", email);
    return Collections.unmodifiableMap(method);
  }

  /**
   * Completed scheduled sessions of the current version, as on the plan card; 0 when the plan has
   * none or no card facts.
   */
  public static int percent(String snapshot, String progress) {
    if (snapshot == null || progress == null) return 0;
    try {
      return percent(JSON.readTree(snapshot), JSON.readTree(progress));
    } catch (RuntimeException unreadable) {
      unreadable(unreadable);
      return 0;
    }
  }

  public static int percent(JsonNode snapshot, JsonNode progress) {
    if (snapshot == null || progress == null || snapshot.isNull() || progress.isNull()) return 0;
    try {
      var card = StudyPlanCardMetadata.from(snapshot, progress, JSON);
      int total = card.path("totalSessionCount").asInt(0);
      return total <= 0
          ? 0
          : Math.min(100, card.path("completedSessionCount").asInt(0) * 100 / total);
    } catch (RuntimeException unreadable) {
      unreadable(unreadable);
      return 0;
    }
  }

  private static void unreadable(RuntimeException unreadable) {
    // DLV-969 (owner, 2026-11-04): the merge page would show 0% with no reason.
    String types = EventLog.types(unreadable);
    if (PERCENT.due(types))
      EventLog.failed(EventLog.EVENT, "merge.candidates", "PLAN_CARD_UNREADABLE")
          .addKeyValue("error.type", types)
          .log(
              "A study plan's progress could not be read for the merge page ({}); it shows 0%",
              types);
  }

  private static final LogThrottle PERCENT = new LogThrottle();

  public static AccountFailure invalidToken() {
    return new AccountFailure(
        404, "MERGE_TOKEN_INVALID", "This link is not valid any more. Ask for a new email.");
  }

  public static AccountFailure keepNotCandidate() {
    return new AccountFailure(422, "MERGE_KEEP_NOT_CANDIDATE", "Choose one of the accounts shown.");
  }

  public static AccountFailure conflict() {
    return new AccountFailure(
        409,
        "MERGE_CONFLICT",
        "One of these accounts changed. Open the page again or ask the administrator.");
  }
}
