package com.lookahead.domain;

import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import jakarta.servlet.FilterChain;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
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
public class DomainStorageFailureFilter extends OncePerRequestFilter {
    private static final Throttle THROTTLE = new Throttle();

    static void unavailable(HttpServletRequest request, String code, String what, Throwable error) {
        String types = Events.types(error);
        if (!THROTTLE.due(code + "|" + types)) return;
        var line = Events.EVENT.atError().addKeyValue("event.action", "request.dependency").addKeyValue("event.outcome", "failure")
                .addKeyValue("reason", code).addKeyValue("url.path", Events.path(request))
                .addKeyValue("http.status", 503).addKeyValue("error.type", types);
        String hint = Events.hint(error);
        line.log("{} on {}: {} ({}); answered 503 until it recovers{}", what, Events.path(request), code, Events.quoted(error),
                hint == null ? "" : "; " + hint);
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
        // DLV-970: the DynamoDB store before MVC (bearer authentication, session checks): the same 503 for a failed write
        // (Unavailable), a failed read (DynamoOwnerUnavailable, an Unavailable) or the SDK's own failure, and a condition
        // that did not hold is the existing conflict answer. The SDK message never reaches the log or the body.
        catch (DynamoWrites.Unavailable | SdkClientException | DynamoDbException error) {
            if (response.isCommitted()) throw error;
            unavailable(request, "ACCOUNT_STORAGE_UNAVAILABLE", "Account storage unavailable", error);
            response.reset(); response.setStatus(503); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"status\":503,\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\",\"message\":\"Account storage is unavailable; retain your work and retry\"}");
        }
        catch (DynamoWrites.Conflict error) {
            if (response.isCommitted()) throw error;
            Events.failed(Events.EVENT, "request.dependency", "REVISION_CONFLICT").addKeyValue("url.path", Events.path(request))
                    .addKeyValue("http.status", 409).addKeyValue("error.type", Events.types(error))
                    .log("Account storage conflict on {}: REVISION_CONFLICT ({}); answered 409", Events.path(request), Events.types(error));
            response.reset(); response.setStatus(409); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"status\":409,\"code\":\"REVISION_CONFLICT\",\"message\":\"The record changed while you were saving; reload before retrying\"}");
        }
    }
}
