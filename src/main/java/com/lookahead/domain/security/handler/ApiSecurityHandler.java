package com.lookahead.domain.security.handler;

import com.lookahead.domain.log.EventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers an {@code /api} request that is not signed in: 401 "Sign in to continue" with a {@code
 * WWW-Authenticate: Bearer} header, or 503 "Identity verification is unavailable" when the sign-in
 * check itself could not be made.
 */
public final class ApiSecurityHandler implements AuthenticationEntryPoint {
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException error)
      throws IOException {
    EventLog.authenticationRefused(request, error);
    boolean unavailable = error instanceof AuthenticationServiceException;
    response.setStatus(unavailable ? 503 : 401);
    if (!unavailable) response.setHeader("WWW-Authenticate", "Bearer");
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response
        .getWriter()
        .write(
            unavailable ? ErrorBodies.IDENTITY_UNAVAILABLE : ErrorBodies.AUTHENTICATION_REQUIRED);
  }
}
