package com.lookahead.domain.validator;

import com.lookahead.domain.config.PaymentConfigValues;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Startup check of the payment settings: payments are approved only for Local and DEV with the mock
 * provider; any other combination refuses startup. DLV-1016: this check was {@code
 * PaymentConfigValues.from} and moved here word for word.
 */
public final class PaymentConfigValidator {
  private PaymentConfigValidator() {}

  /** The payment values, after the check. */
  public static PaymentConfigValues validated(Environment environment) {
    String deployment = environment.getProperty("app.deployment-environment", "local");
    String provider = environment.getProperty("app.payments.provider", "mock");
    if (!Set.of("local", "dev").contains(deployment) || !provider.equals("mock"))
      throw new IllegalStateException("Payments are approved only for Local/DEV mock operation");
    return new PaymentConfigValues(
        environment.getProperty("app.payments.mock-scenario", "success"));
  }
}
