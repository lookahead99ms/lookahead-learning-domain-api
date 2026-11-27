package com.lookahead.domain.security.handler;

import java.util.function.Supplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Who may ask for {@code /content/**}: anyone not signed in (public content; the content service decides per asset), or
 * a signed-in caller whose token carries the content scope.
 */
public final class ProtectedContentAuthorizationHandler implements AuthorizationManager<RequestAuthorizationContext> {
    @Override public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext request) {
                            var value = authentication.get();
                            return new AuthorizationDecision(value instanceof AnonymousAuthenticationToken || value.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_content")));
    }
}
