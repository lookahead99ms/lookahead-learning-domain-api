package com.lookahead.domain.security.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class TestAccountSecurityHandlerTest {
  @Test
  void aCallWithoutValidOperatorCredentialsGets401WithNoChallengeAndNoBody() throws Exception {
    var response = new MockHttpServletResponse();
    new TestAccountSecurityHandler()
        .commence(
            new MockHttpServletRequest(),
            response,
            new BadCredentialsException("Invalid operator credentials"));
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("WWW-Authenticate")).isNull();
    assertThat(response.getContentAsString()).isEmpty();
  }
}
