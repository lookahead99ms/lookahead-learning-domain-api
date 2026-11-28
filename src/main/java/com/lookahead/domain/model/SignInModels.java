package com.lookahead.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sign-in answers, and the stored facts a sign-in unit works on. DLV-1016: the answers were
 * nested in SignInService; the store uses them too, so they live with the model.
 */
public final class SignInModels {
  private SignInModels() {}

  public record Entry(
      UUID signInId,
      boolean current,
      String label,
      String clientDescription,
      Instant createdAt,
      Instant lastActiveAt) {}

  public record Inventory(int limit, List<Entry> entries) {}

  /**
   * mergeAvailable (DLV-969 S36): this sign-in's verified email has two or more accounts that can
   * take part (not test accounts, not deleted) and none of them is disabled, so the page may offer
   * the merge email (AccountMergeService).
   */
  public record Admission(
      UUID accountId,
      UUID signInId,
      String challengeToken,
      Instant expiresAt,
      boolean mergeAvailable) {
    public Admission(UUID accountId, UUID signInId, String challengeToken, Instant expiresAt) {
      this(accountId, signInId, challengeToken, expiresAt, false);
    }

    @Override
    public String toString() {
      return "Admission[redacted]";
    }
  }

  public record ChallengeView(int limit, List<Entry> entries, Instant expiresAt) {}

  // ---- what the store hands SignInService inside a unit (DLV-1016: the service decides on these,
  // the store only reads and writes them) ----

  /**
   * The account behind a sign-in as its control item was read. {@code provider}: the sign-in method
   * of the identity that is signing in. {@code state}: ACTIVE, DISABLED or REMOVED.
   */
  public record Account(
      UUID id,
      String issuer,
      String subject,
      String username,
      String displayName,
      String verifiedEmail,
      String provider,
      boolean admitted,
      boolean enabled,
      boolean author,
      boolean credentialChangePending,
      String state,
      long authGeneration) {
    public boolean active() {
      return "ACTIVE".equals(state);
    }

    public boolean removed() {
      return "REMOVED".equals(state);
    }
  }

  /**
   * One stored sign-in: {@code active} is when it was last used, {@code recent} when its person
   * last authenticated, {@code generation} the sign-in generation it was issued under.
   */
  public record Session(
      UUID id,
      String family,
      String binding,
      Instant created,
      Instant active,
      Instant recent,
      Instant revoked,
      long generation,
      String label,
      String client) {}

  /**
   * One stored challenge; {@code selected} and {@code admitted} are set once it has replaced a
   * sign-in.
   */
  public record Challenge(
      String digest,
      String family,
      String binding,
      Instant created,
      Instant expires,
      Instant auth,
      boolean cancelled,
      UUID selected,
      UUID admitted,
      long generation) {}

  /**
   * Which sign-in a token family was admitted as; it stays after the sign-in ends, so a revoked
   * family is never admitted again.
   */
  public record FamilyBinding(UUID accountId, UUID signInId) {}

  /**
   * An account that carries a verified email (deleted accounts and the test issuer's are never
   * listed).
   */
  public record EmailAccount(
      UUID id, boolean admitted, boolean enabled, boolean active, boolean managedTest) {}

  /**
   * The stored records behind the profile answer: the theme (null when none was ever saved), the
   * links by kind, the sign-in methods, oldest first.
   */
  public record StoredProfile(
      String theme, Map<String, String> links, List<Map<String, Object>> signInMethods) {}
}
