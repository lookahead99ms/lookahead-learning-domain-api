package com.lookahead.domain.log;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * DLV-969 (owner, 2026-11-04): the request ID that ties the log lines of one request together, and when the request
 * started. The request filter ({@code security.filter.RequestLogFilter}) sets both for each request and writes the
 * request's lines through {@link EventLog}; the error handlers ask this class for the reference they give the caller.
 */
public final class RequestLog {
    /** The MDC key of the request ID; it is on every log line written while the request runs. */
    public static final String KEY = "requestId";
    /** The request attribute holding {@link System#nanoTime()} at the start, so a handler can say "failed after N ms". */
    public static final String STARTED = "lookahead.requestStarted";

    private RequestLog() {}

    /** The request ID of the request this thread is serving, or a new one outside a request. */
    public static String reference() {
        String id = MDC.get(KEY);
        return id != null ? id : UUID.randomUUID().toString();
    }

    /** "failed after 41 ms" for a request the request filter timed, "failed" when it was not (tests, other entry points). */
    public static String failedAfter(HttpServletRequest request) {
        Object started = request == null ? null : request.getAttribute(STARTED);
        return started instanceof Long nanos ? "failed after " + (System.nanoTime() - nanos) / 1_000_000 + " ms" : "failed";
    }
}
