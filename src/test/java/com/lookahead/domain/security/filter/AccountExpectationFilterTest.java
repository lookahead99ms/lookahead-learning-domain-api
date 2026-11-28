package com.lookahead.domain.security.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AccountExpectationFilterTest {
  @Test
  void supportDraftCannotFollowAChangedBrowserAccount() throws Exception {
    var owner = UUID.randomUUID();
    var principal = new AccountPrincipal(owner, "synthetic", "Synthetic", true);
    try {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
      var request = new MockHttpServletRequest("POST", "/api/v1/support");
      request.addHeader("X-LookAhead-Account", UUID.randomUUID().toString());
      var response = new MockHttpServletResponse();
      var called = new AtomicBoolean();
      new AccountExpectationFilter().doFilter(request, response, (a, b) -> called.set(true));
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getContentAsString()).contains("ACCOUNT_CHANGED");
      assertThat(called.get()).isFalse();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void matchingSupportOwnerMayContinueToNormalAuthorization() throws Exception {
    var owner = UUID.randomUUID();
    var principal = new AccountPrincipal(owner, "synthetic", "Synthetic", true);
    try {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
      var request = new MockHttpServletRequest("POST", "/api/v1/support");
      request.addHeader("X-LookAhead-Account", owner.toString());
      var response = new MockHttpServletResponse();
      var called = new AtomicBoolean();
      new AccountExpectationFilter().doFilter(request, response, (a, b) -> called.set(true));
      assertThat(called.get()).isTrue();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
