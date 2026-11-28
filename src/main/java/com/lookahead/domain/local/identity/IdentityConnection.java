package com.lookahead.domain.local.identity;

import java.time.Duration;

/**
 * Deployment-owned destinations. No browser-controlled issuer, host, or credentials. The settings
 * are read and checked by {@code validator.IdentityConnectionValidator} (DLV-1016).
 */
public record IdentityConnection(
    String issuer,
    String upstream,
    String bffClientId,
    String verifierSecret,
    Duration connectTimeout,
    Duration readTimeout) {
  @Override
  public String toString() {
    return "IdentityConnection[redacted]";
  }
}
