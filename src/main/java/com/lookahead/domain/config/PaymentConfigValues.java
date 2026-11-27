package com.lookahead.domain.config;

/**
 * The checked payment value: the mock scenario. {@code validator.PaymentConfigValidator} reads and checks it; payments
 * are approved only for Local and DEV with the mock provider.
 */
public record PaymentConfigValues(String mockScenario) {
}
