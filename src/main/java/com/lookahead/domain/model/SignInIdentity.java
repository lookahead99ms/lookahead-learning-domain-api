package com.lookahead.domain.model;

import java.time.Instant;
import java.util.Locale;

/** Validated provider claims, never an application authorization decision. */
public record SignInIdentity(
    String issuer,
    String subject,
    String family,
    String username,
    String displayName,
    Instant authenticatedAt,
    String verifiedEmail) {
  @Override
  public String toString() {
    return "SignInIdentity[redacted]";
  }

  // DLV-1016: these two were in service classes (TestAccountRoster, SignInIdentityRules); stores
  // read them, so they live with the model.
  /** The issuer of DEV test accounts, which have no sign-in provider. */
  public static final String TEST_ISSUER = "urn:lookahead:dev-test-account";

  /** The one comparison form of an email: lower case (ASCII addresses). */
  public static String normalizeEmail(String email) {
    return email.toLowerCase(Locale.ROOT);
  }
}
