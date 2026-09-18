package com.lookahead.learning.content.filter;

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
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/v1/plans") || request.getRequestURI().equals("/api/v1/support") || request.getRequestURI().equals("/api/v1/auth/register")) || !"POST".equals(request.getMethod());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = request.getRequestURI().equals("/api/v1/auth/register") ? 8192 : request.getRequestURI().endsWith("/activity") ? 256 * 1024 : MAX_BYTES;
        if (request.getContentLengthLong() > limit) { fail(response, 413, "PAYLOAD_TOO_LARGE"); return; }
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) { fail(response, 413, "PAYLOAD_TOO_LARGE"); return; }
        try (var parser = json.createParser(bytes)) { while (parser.nextToken() != null) { /* bounded syntax/depth scan */ } }
        catch (Exception ex) { fail(response, 400, "MALFORMED_JSON"); return; }
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
    private void fail(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"Request exceeds JSON limits or is malformed\"}");
    }
}
