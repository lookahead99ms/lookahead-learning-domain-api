package com.lookahead.domain.security.filter;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.security.handler.ErrorBodies;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On {@code /api/**}: a request with {@link #ACCOUNT_HEADER} acts as that test account, but only
 * with the Gateway secret and no bearer token; anything else is refused, never passed on
 * unauthenticated. The filter reads the request's headers and writes the one refusal line; {@link
 * TestSessionService} compares the secret and decides who may act as which test account.
 */
public final class TestSessionFilter extends OncePerRequestFilter {
  // DLV-1016: the header and attribute names of a test-session request; they were constants of
  // TestSessionService, which no longer reads a request.
  // The Gateway BFF's secret travels in SignInBffSecret.HEADER, as on the internal sign-in routes.
  public static final String HOLDER_HEADER = "X-LookAhead-Gate-Subject";
  public static final String ACCOUNT_HEADER = "X-LookAhead-Test-Account";

  /**
   * DLV-969 (owner, 2026-11-04): request attribute holding the gate holder's account id (a {@link
   * UUID}, absent when unknown) once a test-session request was accepted, so a DEV purchase request
   * records who sent it.
   */
  public static final String REQUESTER_ATTRIBUTE = "com.lookahead.domain.testSession.holder";

  private static final LogThrottle UNAVAILABLE = new LogThrottle();
  private final TestSessionService sessions;

  public TestSessionFilter(TestSessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<AccountPrincipal> principal;
    try {
      principal = requestAccount(request);
    } catch (AccountFailure refused) {
      refuse(response);
      return;
    } catch (RuntimeException unavailable) {
      // DLV-969 (owner, 2026-11-04): the 503 names its cause once a minute at most.
      String types = EventLog.types(unavailable);
      if (UNAVAILABLE.due(types))
        EventLog.failed(EventLog.EVENT, "test-session.request", "TEST_SESSION_UNAVAILABLE")
            .addKeyValue("error.type", types)
            .log("Test session check failed ({}); answered 503 IDENTITY_UNAVAILABLE", types);
      response.setStatus(503);
      response.setHeader("Cache-Control", "no-store");
      response.setContentType("application/json");
      response.getWriter().write(ErrorBodies.IDENTITY_UNAVAILABLE);
      return;
    }
    if (principal.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal.get(),
            null,
            List.of(
                new SimpleGrantedAuthority("SCOPE_account"),
                new SimpleGrantedAuthority("SCOPE_content"),
                new SimpleGrantedAuthority("SCOPE_support")));
    var context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.getContextHolderStrategy().setContext(context);
    chain.doFilter(request, response);
  }

  /**
   * What an {@code /api/**} request is: not a test-session request (empty), the test account it
   * acts as, or refused ({@link AccountFailure} 401) without the Gateway secret, with a bearer
   * token, or with a malformed account id. The headers are read in one fixed order. The
   * test-account header comes first; then the Gateway BFF's secret is compared, for every request,
   * also one that asks for no test account. Nothing is decided before that comparison, and the
   * bearer header and the gate holder are read only after it passed.
   */
  private Optional<AccountPrincipal> requestAccount(HttpServletRequest request) {
    String account = request.getHeader(ACCOUNT_HEADER);
    boolean bff = sessions.secretMatches(request.getHeader(SignInBffSecret.HEADER));
    if (account == null) return Optional.empty();
    if (!bff) throw refused(request, "GATEWAY_SECRET_INVALID", null);
    if (request.getHeader("Authorization") != null)
      throw refused(request, "BEARER_TOKEN_WITH_TEST_ACCOUNT", null);
    UUID id;
    try {
      id = UUID.fromString(account);
    } catch (IllegalArgumentException malformed) {
      throw refused(request, "TEST_ACCOUNT_MALFORMED", null);
    }
    AccountPrincipal principal;
    try {
      principal = sessions.actAs(request.getHeader(HOLDER_HEADER), id);
    } catch (AccountFailure denied) {
      throw refused(request, denied.code(), id);
    }
    UUID holder = sessions.holderAccountId(request.getHeader(HOLDER_HEADER));
    if (holder != null) request.setAttribute(REQUESTER_ATTRIBUTE, holder);
    return Optional.of(principal);
  }

  /** One WARN line naming why, then the same 401 for every reason. */
  private static AccountFailure refused(HttpServletRequest request, String reason, UUID target) {
    EventLog.refused(EventLog.EVENT, "test-session.request", reason)
        .addKeyValue("target.id", target)
        .addKeyValue("url.path", EventLog.path(request))
        .log(
            "Test-session request {} {} refused: {}",
            request.getMethod(),
            EventLog.path(request),
            reason);
    return new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
  }

  /** True when the request asks to act as a test account: it carries {@link #ACCOUNT_HEADER}. */
  public static boolean requested(HttpServletRequest request) {
    return request.getHeader(ACCOUNT_HEADER) != null;
  }

  public static void refuse(HttpServletResponse response) throws IOException {
    response.setStatus(401);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    response.getWriter().write(ErrorBodies.AUTHENTICATION_REQUIRED);
  }
}
