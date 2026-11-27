package com.lookahead.domain.exception;

import org.springframework.security.authentication.AuthenticationServiceException;

/** No underlying request, token, response body, or secret is included in this exception. */
public final class SignInCheckUnavailableException extends AuthenticationServiceException {
    public SignInCheckUnavailableException() { super("Identity verification is temporarily unavailable"); }
}
