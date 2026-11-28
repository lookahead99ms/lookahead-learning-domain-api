package com.lookahead.domain.db.store.testaccounts;

import java.util.List;
import java.util.UUID;

/**
 * DLV-970: who is behind an account and which test accounts a signed-in person may act as.
 * DLV-1016: split from the test-account store interface; the transaction is {@link TestAccount}.
 */
public interface TestAccountIdentities {
  record Holder(UUID accountId, boolean authorAccess) {}

  /**
   * One active registry row with the account behind it, as stored: the sign-in issuer and whether
   * the account is admitted and enabled.
   */
  record SignInCandidate(
      UUID accountId,
      String username,
      String displayName,
      String issuer,
      boolean admitted,
      boolean enabled) {}

  List<String> staffRoles(UUID account);

  String displayName(UUID account);

  UUID accountOf(String issuer, String subject);

  Holder holder(String issuer, String subject);

  /**
   * The active registry rows that have an account record ({@code only}: just that account), with
   * the facts above. Which of them a person may sign in as is the caller's rule.
   */
  List<SignInCandidate> signInCandidates(UUID only);
}
