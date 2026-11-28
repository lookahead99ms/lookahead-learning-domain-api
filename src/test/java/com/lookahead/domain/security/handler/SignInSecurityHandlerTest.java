package com.lookahead.domain.security.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.token.SignInTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The answer of the internal sign-in routes when the sign-in could not be verified. */
class SignInSecurityHandlerTest {
  static final String BODY =
      "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Unable to verify sign-in.\"}";
  final SignInSecurityHandler handler = new SignInSecurityHandler();
  final MockHttpServletRequest request =
      new MockHttpServletRequest("POST", "/internal/v1/cloud-sign-ins/admit");

  @Test
  void aRefusedTokenAnswers401WithTheFixedBodyAndItsReasonIsLogged() throws Exception {
    var response = new MockHttpServletResponse();
    try (var log = LogCapture.start()) {
      handler.commence(request, response, SignInTokenVerifier.invalid("GATEWAY_SECRET_MISMATCH"));
      assertThat(log.lines("authentication")).hasSize(1);
      assertThat(LogCapture.keys(log.lines("authentication").getFirst()))
          .containsEntry("reason", "GATEWAY_SECRET_MISMATCH")
          .containsEntry("http.status", 401);
    }
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString()).isEqualTo(BODY);
  }

  @Test
  void anUnavailableCheckAnswers503WithTheSameBody() throws Exception {
    var response = new MockHttpServletResponse();
    handler.commence(request, response, new SignInCheckUnavailableException());
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString()).isEqualTo(BODY);
  }
}
