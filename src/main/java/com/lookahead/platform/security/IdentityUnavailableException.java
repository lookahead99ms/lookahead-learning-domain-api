package com.lookahead.platform.security;

import org.springframework.security.authentication.AuthenticationServiceException;

/** No underlying request, token, response body, or secret is included in this exception. */
public final class IdentityUnavailableException extends AuthenticationServiceException {
    public IdentityUnavailableException() { super("Identity verification is temporarily unavailable"); }
}
