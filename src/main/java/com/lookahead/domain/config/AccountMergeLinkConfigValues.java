package com.lookahead.domain.config;

/** The checked origin of the link in the merge email ({@code app.public-url}); {@code validator.AccountMergeEmailConfigValidator.publicUrl} checks it. */
public record AccountMergeLinkConfigValues(String publicUrl) {
}
