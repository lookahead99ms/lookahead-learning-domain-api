package com.lookahead.domain.config;

/**
 * The checked values of the merge email: whether it is sent ({@code app.email.enabled}), the sender
 * address and the SES region. {@code validator.AccountMergeEmailConfigValidator} reads and checks
 * them (DLV-1016). The origin of the link in the email is a value of its own, {@link
 * AccountMergeLinkConfigValues}.
 */
public record AccountMergeEmailConfigValues(boolean enabled, String from, String region) {}
