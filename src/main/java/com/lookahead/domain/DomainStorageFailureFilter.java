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

/**
 * Dependency failures in bearer authentication occur before MVC handles a product request. DLV-969 (owner,
 * 2026-11-04): the 503 is explained by one WARN with the failure's types and SQL state when it starts and once a minute
 * while it lasts; the request summary line still records each request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DomainStorageFailureFilter extends OncePerRequestFilter {
    private static final Throttle THROTTLE = new Throttle();

    static void unavailable(HttpServletRequest request, String code, String what, Throwable error) {
        String types = Events.types(error), state = Events.sqlState(error);
        if (!THROTTLE.due(code + "|" + types + "|" + state)) return;
        var line = Events.failed(Events.EVENT, "request.dependency", code).addKeyValue("url.path", Events.path(request))
                .addKeyValue("http.status", 503).addKeyValue("error.type", types);
        if (state != null) line = line.addKeyValue("db.sqlState", state);
        line.log("{} on {}: {} ({}{}); answered 503 until it recovers", what, Events.path(request), code, types,
                state == null ? "" : ", SQL state " + state);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        catch (AuthenticationServiceException error) {
            if (response.isCommitted()) throw error;
            unavailable(request, "IDENTITY_UNAVAILABLE", "Sign-in check unavailable", error);
            response.reset(); response.setStatus(503); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"status\":503,\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Identity verification is unavailable; retain your work and retry\"}");
        }
        catch (DataAccessException | CannotCreateTransactionException | TransactionSystemException error) {
            if (response.isCommitted()) throw error;
            unavailable(request, "ACCOUNT_STORAGE_UNAVAILABLE", "Account database unavailable", error);
            response.reset(); response.setStatus(503); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"status\":503,\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\",\"message\":\"Account storage is unavailable; retain your work and retry\"}");
        }
    }
}
