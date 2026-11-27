package com.lookahead.domain.security.filter;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.security.handler.ErrorBodies;
import com.lookahead.domain.db.store.dynamodb.DynamoFailures;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dependency failures in bearer authentication occur before MVC handles a product request. DLV-969 (owner,
 * 2026-11-04): the 503 is explained by one line with the failure's types when it starts and once a minute while it
 * lasts; the request summary line still records each request. Plain logs (owner, 2026-11-17): a dependency
 * down is an ERROR that quotes the dependency's own message and the hint where the code knows the cause; no stack
 * trace, since the line repeats while the outage lasts.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class StorageFailureFilter extends OncePerRequestFilter {
    private static final LogThrottle THROTTLE = new LogThrottle();

    static void unavailable(HttpServletRequest request, String code, String what, Throwable error) {
        String types = EventLog.types(error);
        if (!THROTTLE.due(code + "|" + types)) return;
        var line = EventLog.error(EventLog.EVENT, "request.dependency", code).addKeyValue("url.path", EventLog.path(request))
                .addKeyValue("http.status", 503).addKeyValue("error.type", types);
        String hint = EventLog.hint(error);
        line.log("{} on {}: {} ({}); answered 503 until it recovers{}", what, EventLog.path(request), code, EventLog.quoted(error),
                hint == null ? "" : "; " + hint);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        catch (AuthenticationServiceException error) {
            if (response.isCommitted()) throw error;
            unavailable(request, "IDENTITY_UNAVAILABLE", "Sign-in check unavailable", error);
            answer(response, 503, ErrorBodies.IDENTITY_UNAVAILABLE);
        }
        // DLV-970: the DynamoDB store before MVC (bearer authentication, session checks): a condition that did not hold is
        // the existing conflict answer, and a failed write (Unavailable), a failed read (OwnerReadUnavailable, an
        // Unavailable) or the SDK's own failure is the same 503. DynamoFailures.unavailable says which failures those
        // are; any other failure goes on unchanged. The SDK message never reaches the body.
        catch (DynamoWrites.Conflict error) {
            if (response.isCommitted()) throw error;
            EventLog.failed(EventLog.EVENT, "request.dependency", "REVISION_CONFLICT").addKeyValue("url.path", EventLog.path(request))
                    .addKeyValue("http.status", 409).addKeyValue("error.type", EventLog.types(error))
                    .log("Account storage conflict on {}: REVISION_CONFLICT ({}); answered 409", EventLog.path(request), EventLog.types(error));
            answer(response, 409, ErrorBodies.REVISION_CONFLICT);
        }
        catch (RuntimeException error) {
            if (!DynamoFailures.unavailable(error) || response.isCommitted()) throw error;
            unavailable(request, "ACCOUNT_STORAGE_UNAVAILABLE", "Account storage unavailable", error);
            answer(response, 503, ErrorBodies.ACCOUNT_STORAGE_UNAVAILABLE);
        }
    }

    /** Whatever was written so far is dropped; the answer is JSON and never cached. */
    private static void answer(HttpServletResponse response, int status, String body) throws IOException {
        response.reset(); response.setStatus(status); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(body);
    }
}
