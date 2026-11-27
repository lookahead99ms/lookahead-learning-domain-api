package com.lookahead.domain.security.filter;

import com.lookahead.domain.log.EventLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
@Profile("accounts")
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AccountRequestLimitsFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final JsonFactory json = JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_BYTES).build()).build();
    // Exception, decided 2026-11-25 (DLV-1016): the two branches for /api/v1/auth/register (here and in the limit below) are
    // not reached in a running application: no controller maps that path and the API security chain refuses it (401 without
    // a token, 403 with one, anyRequest().denyAll()) before this filter runs. They are kept because the 8192-byte limit
    // exists only on that path: the paths this filter serves have 16384 (author review events), 262144 (plan activity) and
    // 8388608 bytes (plans, support), and security.filter.AccountRequestLimitsFilterLoggingTest pins the 413, limit.max
    // 8192 and the exact line on it. Coordinator's decision: delete the branches only if a served path had that limit.
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/v1/author/review-artifacts/") || request.getRequestURI().startsWith("/api/v1/plans") || request.getRequestURI().equals("/api/v1/support") || request.getRequestURI().equals("/api/v1/auth/register")) || !"POST".equals(request.getMethod());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The /api/v1/auth/register limit: see the exception comment above shouldNotFilter.
        int limit = request.getRequestURI().startsWith("/api/v1/author/review-artifacts/") ? 16 * 1024 : request.getRequestURI().equals("/api/v1/auth/register") ? 8192 : request.getRequestURI().endsWith("/activity") ? 256 * 1024 : MAX_BYTES;
        if (request.getContentLengthLong() > limit) { tooLarge(request, limit); fail(response, 413, "PAYLOAD_TOO_LARGE"); return; }
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) { tooLarge(request, limit); fail(response, 413, "PAYLOAD_TOO_LARGE"); return; }
        try (var parser = json.createParser(bytes)) { while (parser.nextToken() != null) { /* bounded syntax/depth scan */ } }
        catch (Exception ex) {
            EventLog.refused(EventLog.EVENT, "request.json", "MALFORMED_JSON").addKeyValue("url.path", EventLog.path(request)).addKeyValue("http.status", 400)
                    .addKeyValue("error.type", ex.getClass().getSimpleName())
                    .log("Request body on {} refused with 400: not valid JSON or nested deeper than 32 levels ({})", EventLog.path(request), ex.getClass().getSimpleName());
            fail(response, 400, "MALFORMED_JSON"); return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request only"); }
                };
            }
        }, response);
    }
    private static void tooLarge(HttpServletRequest request, int limit) {
        EventLog.limit("request.body", "PAYLOAD_TOO_LARGE", "request-body-bytes", limit, "per request").addKeyValue("url.path", EventLog.path(request))
                .addKeyValue("http.status", 413)
                .log("Request body limit hit on {}: more than {} bytes; refused with 413", EventLog.path(request), limit);
    }
    private void fail(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"Request exceeds JSON limits or is malformed\"}");
    }
}
