package com.lookahead.domain.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers an internal test-account call without valid operator credentials: 401, never cached, no
 * body and no challenge.
 */
public final class TestAccountSecurityHandler implements AuthenticationEntryPoint {
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) {
    response.setStatus(401);
    response.setHeader("Cache-Control", "no-store");
  }
}
