package com.lookahead.domain.security.config;

/**
 * The secret the Gateway BFF shares with this Domain: a call that carries it comes from the Gateway
 * BFF. The internal sign-in routes and the DEV test sessions compare it. It is read and checked
 * with the sign-in settings, in the one pass of {@code validator.SignInTokenTrustValidator}; it is
 * never printed.
 */
public record SignInBffSecret(String value) {
  /** The request header a call of the Gateway BFF carries it in. */
  public static final String HEADER = "X-LookAhead-Gateway-Secret";

  @Override
  public String toString() {
    return "SignInBffSecret[redacted]";
  }
}
