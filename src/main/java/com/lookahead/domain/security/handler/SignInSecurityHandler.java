package com.lookahead.domain.security.handler;

import com.lookahead.domain.log.EventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers a request on the internal sign-in routes whose sign-in could not be verified: 503 when
 * the check itself was unavailable, otherwise 401, both with the same "Unable to verify sign-in."
 * body. Created by the sign-in security configuration for its filter chain.
 */
public final class SignInSecurityHandler implements AuthenticationEntryPoint {
  @Override
  public void commence(
      HttpServletRequest req, HttpServletResponse res, AuthenticationException error)
      throws IOException {
    EventLog.authenticationRefused(req, error);
    res.setStatus(error instanceof AuthenticationServiceException ? 503 : 401);
    res.setHeader("Cache-Control", "no-store");
    res.setContentType("application/json");
    res.getWriter()
        .write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Unable to verify sign-in.\"}");
  }
}
