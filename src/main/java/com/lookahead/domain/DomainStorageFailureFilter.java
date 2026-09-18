package com.lookahead.domain;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dependency failures in bearer authentication occur before MVC handles a product request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DomainStorageFailureFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        catch (AuthenticationServiceException error) {
            if (response.isCommitted()) throw error;
            response.reset(); response.setStatus(503); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"status\":503,\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Identity verification is unavailable; retain your work and retry\"}");
        }
        catch (DataAccessException | CannotCreateTransactionException | TransactionSystemException error) {
            if (response.isCommitted()) throw error;
            response.reset(); response.setStatus(503); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"status\":503,\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\",\"message\":\"Account storage is unavailable; retain your work and retry\"}");
        }
    }
}
