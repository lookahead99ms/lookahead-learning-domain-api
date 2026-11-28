package com.lookahead.domain.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The internal test-session routes take only calls that carry the Gateway BFF's secret. */
class TestSessionBffFilterTest {
  final TestSessionService sessions = mock(TestSessionService.class);
  final MockHttpServletRequest request =
      new MockHttpServletRequest("POST", "/internal/v1/test-sessions/start");

  @Test
  void aCallWithoutTheSecretIsRefusedWith401AndGoesNoFurther() throws Exception {
    when(sessions.secretMatches("secret")).thenReturn(true);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    try (var log = LogCapture.start()) {
      new TestSessionBffFilter(sessions).doFilter(request, response, chain);
      var line = log.only("test-session.gateway-check");
      assertThat(line.getLevel()).isEqualTo(Level.WARN);
      assertThat(line.getFormattedMessage())
          .isEqualTo(
              "Test-session route /internal/v1/test-sessions/start refused: the call did not carry"
                  + " the Gateway secret");
      assertThat(LogCapture.keys(line))
          .containsEntry("reason", "GATEWAY_SECRET_INVALID")
          .containsEntry("url.path", "/internal/v1/test-sessions/start");
    }
    // The comparison ran although the call carried no secret at all.
    verify(sessions).secretMatches(null);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in to"
                + " continue\"}");
    assertThat(chain.getRequest()).as("the route was not reached").isNull();
  }

  @Test
  void aCallWithTheSecretGoesOn() throws Exception {
    when(sessions.secretMatches("secret")).thenReturn(true);
    request.addHeader(SignInBffSecret.HEADER, "secret");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    new TestSessionBffFilter(sessions).doFilter(request, response, chain);
    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(response.getStatus()).isEqualTo(200);
  }

  /**
   * DLV-1016: the same check is the CSRF exemption of the internal routes; it reads the secret
   * header and nothing else.
   */
  @Test
  void fromBffReadsTheSecretHeaderAndAsksTheServiceEveryTime() {
    when(sessions.secretMatches("secret")).thenReturn(true);
    var filter = new TestSessionBffFilter(sessions);
    assertThat(filter.fromBff(request)).isFalse();
    request.addHeader(SignInBffSecret.HEADER, "wrong");
    assertThat(filter.fromBff(request)).isFalse();
    var withSecret = new MockHttpServletRequest("GET", "/internal/v1/test-sessions/accounts");
    withSecret.addHeader(SignInBffSecret.HEADER, "secret");
    assertThat(filter.fromBff(withSecret)).isTrue();
    var order = Mockito.inOrder(sessions);
    order.verify(sessions).secretMatches(null);
    order.verify(sessions).secretMatches("wrong");
    order.verify(sessions).secretMatches("secret");
    order.verifyNoMoreInteractions();
  }

  @Test
  void aRequestAsksForATestSessionOnlyWithTheAccountHeader() {
    assertThat(TestSessionFilter.requested(request)).isFalse();
    request.addHeader(TestSessionFilter.ACCOUNT_HEADER, "00000000-0000-4000-8000-000000000001");
    assertThat(TestSessionFilter.requested(request)).isTrue();
  }
}
