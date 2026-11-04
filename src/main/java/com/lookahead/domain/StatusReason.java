package com.lookahead.domain;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DLV-969 (owner, 2026-11-04): why a request failed, for the request's summary line. Every 4xx and 5xx gets a code and a
 * reason in words: the {@code code} and {@code message} the answer itself carried, else the message given to
 * {@code sendError}, else a plain description of the status. Only the first 4 KB of an error answer are read, and only
 * once the status is already 400 or more, so normal pages and files are never copied. Email addresses are masked.
 */
final class StatusReason extends HttpServletResponseWrapper {
    static final int LIMIT = 4096;
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z0-9_.\\-]{1,80})\"");
    private static final Pattern WORDS = Pattern.compile("\"(?:message|detail|error_description|title)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,300})\"");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"'<>]+@[^\\s@\"'<>]+\\.[A-Za-z]{2,}");
    private static final Map<Integer, String> STATUS = Map.ofEntries(
            Map.entry(400, "Bad request: the request was malformed or a field failed validation"),
            Map.entry(401, "Not signed in, or the sign-in expired"),
            Map.entry(403, "Not allowed: a role, CSRF token, DEV gate or ownership rule refused it"),
            Map.entry(404, "Not found: no such page, record or route"),
            Map.entry(405, "Method not allowed on this path"),
            Map.entry(406, "Not acceptable: no answer in the format asked for"),
            Map.entry(408, "The request took too long to arrive"),
            Map.entry(409, "Conflict with the current state: it already exists, changed meanwhile, or is not allowed now"),
            Map.entry(410, "Gone: this no longer exists"),
            Map.entry(413, "Request too large"),
            Map.entry(415, "Unsupported content type"),
            Map.entry(422, "Understood but rejected: the content broke a rule"),
            Map.entry(429, "Too many requests: a rate limit was reached"),
            Map.entry(500, "Unexpected server failure: see the ERROR line with the same requestId"),
            Map.entry(501, "Not implemented: this action is not available in this environment"),
            Map.entry(502, "Bad gateway: a service behind this one gave an invalid answer"),
            Map.entry(503, "Unavailable: a service this request needs is down or busy"),
            Map.entry(504, "Gateway timeout: a service behind this one did not answer in time"));

    private final StringBuilder seen = new StringBuilder();
    private String sent;
    private ServletOutputStream stream;
    private PrintWriter writer;

    StatusReason(HttpServletResponse response) { super(response); }

    /** The failure's code and reason, or null for a success. */
    Reason reason() {
        int status = getStatus();
        if (status < 400) return null;
        String code = find(CODE, seen);
        String words = find(WORDS, seen);
        if (words == null) words = sent;
        if (words == null || words.isBlank()) words = STATUS.getOrDefault(status, "HTTP " + status);
        return new Reason(code != null ? code : "HTTP_" + status, mask(words.replace("\\\"", "\"").replaceAll("[\\r\\n\\t]", " ")));
    }

    static String describe(int status) { return STATUS.getOrDefault(status, "HTTP " + status); }

    static String mask(String text) {
        String masked = EMAIL.matcher(text).replaceAll("[email]");
        return masked.length() > 300 ? masked.substring(0, 300) + "…" : masked;
    }

    private static String find(Pattern pattern, CharSequence text) {
        Matcher match = pattern.matcher(text);
        return match.find() ? match.group(1) : null;
    }

    private void keep(CharSequence text) {
        if (getStatus() >= 400 && seen.length() < LIMIT) seen.append(text, 0, Math.min(text.length(), LIMIT - seen.length()));
    }

    @Override public void sendError(int status, String message) throws IOException {
        sent = message;
        super.sendError(status, message);
    }

    @Override public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) stream = new TeeStream(super.getOutputStream());
        return stream;
    }

    @Override public PrintWriter getWriter() throws IOException {
        if (writer == null) writer = new PrintWriter(new TeeWriter(super.getWriter()), false);
        return writer;
    }

    @Override public void flushBuffer() throws IOException {
        if (writer != null) writer.flush();
        super.flushBuffer();
    }

    record Reason(String code, String words) { }

    private final class TeeStream extends ServletOutputStream {
        private final ServletOutputStream target;
        TeeStream(ServletOutputStream target) { this.target = target; }
        @Override public void write(int b) throws IOException { target.write(b); if (getStatus() >= 400 && seen.length() < LIMIT) seen.append((char) b); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            target.write(bytes, offset, length);
            if (getStatus() >= 400 && seen.length() < LIMIT)
                keep(new String(bytes, offset, Math.min(length, LIMIT - seen.length()), StandardCharsets.UTF_8));
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() throws IOException { target.close(); }
        @Override public boolean isReady() { return target.isReady(); }
        @Override public void setWriteListener(WriteListener listener) { target.setWriteListener(listener); }
    }

    private final class TeeWriter extends Writer {
        private final PrintWriter target;
        TeeWriter(PrintWriter target) { this.target = target; }
        @Override public void write(char[] chars, int offset, int length) {
            target.write(chars, offset, length);
            keep(java.nio.CharBuffer.wrap(chars, offset, length));
        }
        @Override public void flush() { target.flush(); }
        @Override public void close() { target.close(); }
    }
}
