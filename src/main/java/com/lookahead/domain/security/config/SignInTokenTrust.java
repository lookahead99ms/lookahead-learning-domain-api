package com.lookahead.domain.security.config;

/**
 * Whose sign-in tokens this Domain trusts: the Cognito pool (issuer and region), the application client and the scope prefix.
 * DLV-1016: the Gateway BFF's shared secret is a value of its own ({@link SignInBffSecret}). Both are read and checked in
 * one pass by {@code validator.SignInTokenTrustValidator}, which yields them together as {@link Checked}.
 */
public record SignInTokenTrust(String issuer, String region, String clientId, String scopePrefix) {
    /** What the one checked pass over the sign-in settings yields: the token trust and the Gateway BFF's secret. */
    public record Checked(SignInTokenTrust trust, SignInBffSecret bffSecret) {
        @Override public String toString(){return "SignInTokenTrust.Checked[redacted]";}
    }
    @Override public String toString(){return "SignInTokenTrust[redacted]";}
}
