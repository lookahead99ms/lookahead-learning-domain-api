package com.lookahead.domain.security.handler;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import static org.assertj.core.api.Assertions.assertThat;

/** The answer of {@code /api} when the caller is not signed in, or the sign-in check could not be made. */
class ApiSecurityHandlerTest {
    final ApiSecurityHandler handler = new ApiSecurityHandler();
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/plans");

    @Test void aCallerWhoIsNotSignedInGets401WithTheBearerChallenge() throws Exception {
        var response = new MockHttpServletResponse();
        handler.commence(request, response, new InsufficientAuthenticationException("no token"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in to continue\"}");
    }

    @Test void anUnavailableSignInCheckGets503WithoutAChallenge() throws Exception {
        var response = new MockHttpServletResponse();
        handler.commence(request, response, new SignInCheckUnavailableException());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"status\":503,\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Identity verification is unavailable; retain your work and retry\"}");
    }
}
