package com.lookahead.domain.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On the internal test-account routes: lets only non-browser JSON maintenance calls through. A request with an
 * {@code Origin} or {@code Sec-Fetch-Site} header, or a POST that is not JSON, is answered 403.
 */
public final class TestAccountOperatorFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Basic credentials can be ambient in browsers. Permit only non-browser JSON
        // maintenance calls; no CORS/preflight or simple form request may reach seeding.
        String contentType = request.getContentType();
        boolean json = contentType != null && contentType.split(";", 2)[0].strip()
                .equalsIgnoreCase("application/json");
        if (request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null
                || ("POST".equals(request.getMethod()) && !json)) {
            response.setStatus(403);
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        chain.doFilter(request, response);
    }
}
