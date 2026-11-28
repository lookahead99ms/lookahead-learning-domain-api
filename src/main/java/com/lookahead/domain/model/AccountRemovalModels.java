package com.lookahead.domain.model;

import java.util.List;

/**
 * What the removal store reads and reports. DLV-1016: these were nested in AccountRemovalService;
 * the store uses them too, so they live with the model. Values only: the service builds its
 * refusals itself.
 */
public final class AccountRemovalModels {
  private AccountRemovalModels() {}

  /**
   * {@code usernames} (DLV-969 S36): every sign-in pool user of the account, oldest first; the pool
   * is told once per user.
   */
  public record Target(
      String issuer,
      String subject,
      String username,
      boolean admitted,
      boolean author,
      String state,
      List<String> usernames) {}

  /**
   * {@code changed}: the database row changed now; false when an earlier call already made the
   * change.
   */
  public record Changed(Target target, boolean changed) {}
}
