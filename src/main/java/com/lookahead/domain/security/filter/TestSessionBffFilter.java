package com.lookahead.domain.security.filter;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On the internal test-session routes: only a call that carries the Gateway BFF's secret goes on;
 * any other call is refused with 401 and one log line. It runs before the CSRF check, which such a
 * call skips.
 */
public final class TestSessionBffFilter extends OncePerRequestFilter {
  private final TestSessionService sessions;

  public TestSessionBffFilter(TestSessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!fromBff(request)) {
      EventLog.refused(EventLog.EVENT, "test-session.gateway-check", "GATEWAY_SECRET_INVALID")
          .addKeyValue("url.path", EventLog.path(request))
          .log(
              "Test-session route {} refused: the call did not carry the Gateway secret",
              EventLog.path(request));
      TestSessionFilter.refuse(response);
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * True when the call carries the Gateway BFF's secret. The header is read and compared for every
   * request, also one without it.
   */
  public boolean fromBff(HttpServletRequest request) {
    return sessions.secretMatches(request.getHeader(SignInBffSecret.HEADER));
  }
}
