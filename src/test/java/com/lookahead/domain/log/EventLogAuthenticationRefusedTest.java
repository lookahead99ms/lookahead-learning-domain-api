package com.lookahead.domain.log;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.security.token.SignInTokenVerifier;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import static org.assertj.core.api.Assertions.assertThat;

/** DLV-969 (owner, 2026-11-04): a refused bearer token says why in the log, once per reason a minute. */
class EventLogAuthenticationRefusedTest {
    @Test void refusalsAreLoggedByReasonCodeAndOrdinaryOrAlreadyLoggedCasesStayQuiet() {
        var request = new MockHttpServletRequest("GET", "/api/v1/plans");
        request.setQueryString("token=synthetic-secret-token");
        try (var log = LogCapture.start()) {
            EventLog.authenticationRefused(request, new InsufficientAuthenticationException("Full authentication is required"));
            EventLog.authenticationRefused(request, new SignInCheckUnavailableException());
            EventLog.authenticationRefused(request, SignInTokenVerifier.invalid("ISSUER_MISMATCH"));
            EventLog.authenticationRefused(request, SignInTokenVerifier.invalid("ISSUER_MISMATCH"));
            EventLog.authenticationRefused(request, SignInTokenVerifier.invalid("GATEWAY_SECRET_MISMATCH"));
            EventLog.authenticationRefused(request, new InvalidBearerTokenException("An error occurred while attempting to decode the Jwt: Jwt expired at synthetic-secret-token",
                    new IllegalArgumentException("claims")));
            EventLog.authenticationRefused(request, new OAuth2AuthenticationException(new OAuth2Error("Not A Code!")));
            EventLog.authenticationRefused(request, new AuthenticationServiceException("decoder down"));
            assertThat(log.messages()).containsExactly(
                    "Bearer token refused on /api/v1/plans with 401: ISSUER_MISMATCH (OAuth2AuthenticationException)",
                    "Bearer token refused on /api/v1/plans with 401: GATEWAY_SECRET_MISMATCH (OAuth2AuthenticationException)",
                    "Bearer token refused on /api/v1/plans with 401: invalid_token (InvalidBearerTokenException < IllegalArgumentException)",
                    "Bearer token refused on /api/v1/plans with 401: TOKEN_INVALID (OAuth2AuthenticationException)",
                    "Bearer token refused on /api/v1/plans with 503: AUTHENTICATION_UNAVAILABLE (AuthenticationServiceException)");
            assertThat(log.lines()).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
            assertThat(LogCapture.keys(log.lines().getFirst())).containsEntry("reason", "ISSUER_MISMATCH").containsEntry("http.status", 401);
            log.containsNone("synthetic-secret-token", "decoder down", "expired");
        }
    }
}
