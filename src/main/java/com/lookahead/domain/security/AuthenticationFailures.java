package com.lookahead.domain.security;

import com.lookahead.domain.Events;
import com.lookahead.domain.Throttle;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * DLV-969 (owner, 2026-11-04): why a bearer token was refused. The 401 answer and its summary line say only
 * AUTHENTICATION_REQUIRED, so a wrong issuer, client ID or Gateway secret made every call fail with nothing to go on.
 * One WARN per reason when it starts and once a minute while it lasts: the reason code the check attached (for example
 * {@code ISSUER_MISMATCH}), else the OAuth error code, and the exception types. Never the token or a claim value.
 * A request without a token is ordinary (no line), and an unavailable identity check is logged where it failed.
 */
public final class AuthenticationFailures {
    private static final Throttle THROTTLE = new Throttle();

    private AuthenticationFailures() { }

    public static void log(HttpServletRequest request, AuthenticationException error) {
        if (error instanceof InsufficientAuthenticationException || error instanceof IdentityUnavailableException) return;
        String types = Events.types(error);
        String reason = error instanceof AuthenticationServiceException ? "AUTHENTICATION_UNAVAILABLE" : "TOKEN_INVALID";
        if (error instanceof OAuth2AuthenticationException oauth && oauth.getError() != null) {
            String described = Events.safe(oauth.getError().getDescription());
            reason = described != null && described.matches("[A-Z][A-Z0-9_]*") ? described
                    : Events.safe(oauth.getError().getErrorCode()) != null ? oauth.getError().getErrorCode() : reason;
        }
        if (!THROTTLE.due(reason + "|" + types)) return;
        int status = error instanceof AuthenticationServiceException ? 503 : 401;
        Events.refused(Events.EVENT, "authentication", reason).addKeyValue("url.path", Events.path(request))
                .addKeyValue("http.status", status).addKeyValue("error.type", types)
                .log("Bearer token refused on {} with {}: {} ({})", Events.path(request), status, reason, types);
    }
}
