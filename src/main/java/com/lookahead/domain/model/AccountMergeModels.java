package com.lookahead.domain.model;

import java.time.Instant;
import java.util.*;

/**
 * The shapes of the account merge. DLV-1016: these were nested in AccountMergeService; the store
 * uses them too, so they live with the model.
 */
public final class AccountMergeModels {
  private AccountMergeModels() {}

  public record Plan(UUID id, String title, int progressPercent) {}

  /**
   * paidMembership: an active or scheduled paid purchase, which the merge ends (history kept) for
   * an account not kept.
   */
  public record Candidate(
      UUID accountId,
      String displayName,
      List<Map<String, Object>> signInMethods,
      boolean admitted,
      Instant createdAt,
      Instant lastSignInAt,
      List<Plan> studyPlans,
      boolean paidMembership) {}

  public record Candidates(String email, Instant expiresAt, List<Candidate> candidates) {}

  public record Kept(UUID keptAccountId) {}

  /**
   * One account of the email's pool as the store read it; {@code state} is the account state (null,
   * disabled, removed).
   */
  public record Member(
      UUID id,
      String issuer,
      String subject,
      String username,
      String email,
      boolean admitted,
      boolean enabled,
      boolean author,
      String state) {}

  /**
   * The request's outcome inside its unit: the fresh token of the link to send, or the reason code
   * for sending nothing.
   */
  public record Requested(String token, String skipped, int accounts) {}

  /**
   * One account that went in a merge: whether it was admitted (its data is erased after the commit)
   * and how many sign-ins moved.
   */
  public record Removed(UUID id, boolean admitted, int moved) {}

  /** What the merge unit committed: the accounts that went, in the order the unit handled them. */
  public record Merged(List<Removed> removed) {}
}
