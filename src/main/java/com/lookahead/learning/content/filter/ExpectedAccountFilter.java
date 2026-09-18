package com.lookahead.learning.content.filter;

import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** A comparison guard for shared-browser account changes, never an identity source. */
@Component
@Profile("accounts")
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class ExpectedAccountFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/v1/plans") || request.getRequestURI().equals("/api/v1/support"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String expected = request.getHeader("X-LookAhead-Account");
        if (expected != null) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)
                    || !principal.accountId().toString().equals(expected)) {
                response.setStatus(401); response.setContentType("application/json");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"status\":401,\"code\":\"ACCOUNT_CHANGED\",\"message\":\"The signed-in account changed; sign in again before saving this draft\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
