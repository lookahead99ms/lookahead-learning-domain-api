package com.lookahead.domain;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DLV-969 (owner, 2026-11-04): operational log lines the owner can read in CloudWatch. Plain logs (owner, 2026-11-17):
 * every line is one plain sentence a person reads without a schema; the key-value pairs stay on the call (they cost
 * nothing on a plain line, the tests assert on them, and a log pipeline could render them again) but the sentence must
 * say everything a reader needs: who acted ("Owner 1a2b3c4d …"), the counts and the stable codes. Keys in use:
 * {@code event.action}, {@code event.outcome} ({@code success}, {@code failure} or {@code refused}), {@code account.id}
 * (who acted), {@code target.id} (what it acted on), {@code reason} (a stable code), {@code retry.*}, {@code limit.*}
 * and {@code durationMs}. Four logger names:
 * <ul>
 *   <li>{@link #EVENT} {@code lookahead.event}: important actions and their refusals;</li>
 *   <li>{@link #PAYMENT} {@code lookahead.payment}: purchases, payment attempts and memberships;</li>
 *   <li>{@link #LIMIT} {@code lookahead.limit}: a rate limit or quota was hit;</li>
 *   <li>{@link #UPSTREAM} {@code lookahead.upstream}: one line per call to Cognito, Identity, S3 or Stripe.</li>
 * </ul>
 * Levels: INFO is normal progress; WARN is an expected refusal (wrong password, limit hit, locked content, validation),
 * a retry or a retry sequence that gives up, with short wording and no stack trace; ERROR is something an operator
 * must act on (misconfiguration, permission denied, dependency down, a bug). An ERROR line answers five questions in
 * one sentence: what was being done, which dependency and operation on which resource failed, why (the exception type
 * and the dependency's own message, HTTP status, AWS error code), what the caller got, and where the stack trace is.
 * On ERROR lines the dependency's message IS quoted ({@link #quoted}); never secrets, tokens, passwords, cookies,
 * Authorization headers or request bodies ({@link #scrub} removes the obvious ones). INFO and WARN lines never carry
 * exception messages, names or emails: IDs, stable codes, counts and exception types only. The word ERROR appears on
 * a line only as the level of an ERROR line (the deploy's log scan searches for it).
 */
public final class Events {
    public static final Logger EVENT = LoggerFactory.getLogger("lookahead.event");
    public static final Logger PAYMENT = LoggerFactory.getLogger("lookahead.payment");
    public static final Logger LIMIT = LoggerFactory.getLogger("lookahead.limit");
    public static final Logger UPSTREAM = LoggerFactory.getLogger("lookahead.upstream");
    /** Hint for a connection that never reached the service: the usual cause in a private subnet. */
    public static final String NETWORK_HINT = "check the VPC endpoint and the security group between the task and the service";
    /** Hint for a permission refused by an IAM-governed service. */
    public static final String PERMISSION_HINT = "the task role lacks this permission: fix the IAM policy, then deploy again";
    private static final int MESSAGE_LIMIT = 600;
    private static final java.util.regex.Pattern SECRETS = java.util.regex.Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|authorization|cookie|set-cookie|x-api-key|api[_-]?key)\\b\\s*[=:]\\s*\"?(?:bearer\\s+)?[^\\s\"&;,)]+");
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final java.util.regex.Pattern URL_CREDENTIALS = java.util.regex.Pattern.compile("(?i)(//)[^/@\\s]+@");

    private Events() {}

    // ---- Who acted, said in the sentence

    /** "Owner 1a2b3c4d" / "Admin 1a2b3c4d" / "Account 1a2b3c4d": the role as the call site knows it and the first 8 characters of the id. */
    public static String actor(String role, Object accountId) {
        return role + " " + id8(accountId);
    }

    /** The first 8 characters of an id (a UUID's first group), or "unknown" without one. */
    public static String id8(Object id) {
        if (id == null) return "unknown";
        String text = id.toString();
        return text.length() > 8 ? text.substring(0, 8) : text;
    }

    // ---- ERROR lines: the exception and its message, the origin, the hint

    /**
     * "{@code AccessDeniedException: User arn:… is not authorized …}": the deepest cause that has a message, by simple
     * type name, with the message scrubbed of secrets and line breaks. For ERROR lines only (WARN lines use
     * {@link #types}). Without any message the types are returned.
     */
    public static String quoted(Throwable failure) {
        if (failure == null) return "no exception";
        Throwable withMessage = null;
        int depth = 0;
        for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++)
            if (at.getMessage() != null && !at.getMessage().isBlank()) withMessage = at;
        if (withMessage == null) return types(failure);
        return withMessage.getClass().getSimpleName() + ": " + scrub(withMessage.getMessage());
    }

    /**
     * Removes what must never be on a log line: {@code password=…}, {@code token: …}, {@code Authorization: …},
     * {@code Cookie: …}, {@code Bearer …}, {@code user:password@} in a URL; collapses line breaks (a message must not
     * forge a second line) and caps the length.
     */
    public static String scrub(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").strip();
        flat = SECRETS.matcher(flat).replaceAll("$1=[redacted]");
        flat = BEARER.matcher(flat).replaceAll("Bearer [redacted]");
        flat = URL_CREDENTIALS.matcher(flat).replaceAll("$1[redacted]@");
        return flat.length() > MESSAGE_LIMIT ? flat.substring(0, MESSAGE_LIMIT) + "…" : flat;
    }

    /**
     * Where an unexpected failure came from: "{@code PlanVersionService.publish (PlanVersionService.java:148)}", the
     * first frame of our own code in the failure or its causes, else the top frame of the failure.
     */
    public static String origin(Throwable failure) {
        StackTraceElement first = null;
        int depth = 0;
        for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++) {
            for (StackTraceElement frame : at.getStackTrace()) {
                if (first == null) first = frame;
                if (frame.getClassName().startsWith("com.lookahead.")) return frame(frame);
            }
        }
        return first == null ? "an unknown place" : frame(first);
    }

    private static String frame(StackTraceElement frame) {
        String type = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1);
        String file = frame.getFileName() == null ? "unknown source" : frame.getFileName() + ":" + frame.getLineNumber();
        return type + "." + frame.getMethodName() + " (" + file + ")";
    }

    /** "unexpected IllegalStateException in Foo.bar (Foo.java:12): the message": the why of a crash line. */
    public static String unexpected(Throwable failure) {
        return "unexpected " + failure.getClass().getSimpleName() + " in " + origin(failure) + ": " + scrub(failure.getMessage() == null ? "no message" : failure.getMessage());
    }

    /**
     * A hint when the code knows the cause: a connection that never reached the service (connect timeout, connection
     * refused, unknown host) points at the VPC endpoint and security group; a permission refusal at the IAM policy.
     * Null when nothing is known.
     */
    public static String hint(Throwable failure) {
        if (permissionRefused(status(failure), code(failure))) return PERMISSION_HINT;
        int depth = 0;
        for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++) {
            if (at instanceof java.net.ConnectException || at instanceof java.net.UnknownHostException
                    || at instanceof java.net.http.HttpConnectTimeoutException || at instanceof java.net.NoRouteToHostException) return NETWORK_HINT;
            if (at instanceof java.net.SocketTimeoutException socket && socket.getMessage() != null
                    && socket.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connect")) return NETWORK_HINT;
            if (at.getMessage() != null && at.getMessage().toLowerCase(java.util.Locale.ROOT).matches(".*connect(ion)? (timed out|refused).*")) return NETWORK_HINT;
        }
        return null;
    }

    /** INFO: the action happened. */
    public static LoggingEventBuilder success(Logger log, String action) {
        return log.atInfo().addKeyValue("event.action", action).addKeyValue("event.outcome", "success");
    }

    /** WARN: the action was refused for {@code reason} (a stable code such as {@code ROSTER_ACCOUNT_PERMANENT}). */
    public static LoggingEventBuilder refused(Logger log, String action, String reason) {
        return log.atWarn().addKeyValue("event.action", action).addKeyValue("event.outcome", "refused").addKeyValue("reason", reason);
    }

    /** WARN: the action was tried and did not complete, or a retry sequence gave up. */
    public static LoggingEventBuilder failed(Logger log, String action, String reason) {
        return log.atWarn().addKeyValue("event.action", action).addKeyValue("event.outcome", "failure").addKeyValue("reason", reason);
    }

    /** WARN: one more try follows. {@code attempt} is the try that just failed, counted from 1. */
    public static LoggingEventBuilder retry(Logger log, String action, String reason, int attempt, int max) {
        return failed(log, action, reason).addKeyValue("retry.attempt", attempt).addKeyValue("retry.max", max);
    }

    /** WARN on {@code lookahead.limit}: a limit or quota refused the request. */
    public static LoggingEventBuilder limit(String action, String reason, String name, long max, String window) {
        return refused(LIMIT, action, reason).addKeyValue("limit.name", name).addKeyValue("limit.max", max)
                .addKeyValue("limit.window", window);
    }

    /**
     * Runs {@code line} once the current transaction commits, so a success line never describes a change that was
     * rolled back; outside a transaction it runs at once.
     */
    public static void afterCommit(Runnable line) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { line.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { line.run(); }
        });
    }

    // ---- Upstream calls

    /** The start of an upstream call, for {@link #upstreamDone} and friends. */
    public static long started() { return System.nanoTime(); }

    static long millis(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private static LoggingEventBuilder upstream(LoggingEventBuilder line, String service, String operation, String outcome, long millis) {
        return line.addKeyValue("event.action", "upstream.call").addKeyValue("event.outcome", outcome)
                .addKeyValue("upstream.service", service.toLowerCase(java.util.Locale.ROOT)).addKeyValue("upstream.operation", operation)
                .addKeyValue("durationMs", millis);
    }

    /** INFO: e.g. "Cognito AdminDisableUser succeeded in 42 ms". */
    public static void upstreamDone(String service, String operation, long started) {
        long millis = millis(started);
        upstream(UPSTREAM.atInfo(), service, operation, "success", millis).log("{} {} succeeded in {} ms", service, operation, millis);
    }

    /** WARN: the provider answered and said no, e.g. a revoked token; {@code reason} is a stable code. */
    public static void upstreamRefused(String service, String operation, long started, String reason) {
        long millis = millis(started);
        upstream(UPSTREAM.atWarn(), service, operation, "refused", millis).addKeyValue("reason", reason)
                .log("{} {} refused in {} ms: {}", service, operation, millis, reason);
    }

    /** WARN: the call failed; logs the exception types and, for AWS and HTTP errors, the provider's status and code. */
    public static void upstreamFailed(String service, String operation, long started, Throwable failure) {
        upstreamFailed(service, operation, null, started, failure, status(failure), code(failure));
    }

    /** As above, naming the resource the call addressed (a bucket/key, a table, a queue), which every ERROR line must say. */
    public static void upstreamFailed(String service, String operation, String resource, long started, Throwable failure) {
        upstreamFailed(service, operation, resource, started, failure, status(failure), code(failure));
    }

    /**
     * As above, with the provider's HTTP status and error code given by the caller (null when unknown); {@code failure}
     * is null when the provider answered with an unexpected status and nothing was thrown.
     */
    public static void upstreamFailed(String service, String operation, long started, Throwable failure, Integer status, String code) {
        upstreamFailed(service, operation, null, started, failure, status, code);
    }

    /**
     * WARN: "{@code Cognito AdminEnableUser failed in 12 ms: NotAuthorizedException (HTTP 400, NotAuthorizedException)}",
     * types and codes only. A permission refused by an IAM-governed service (HTTP 403 or an AccessDenied code) is an
     * ERROR (owner, 2026-11-09: a grant the deployment lacks is a configuration defect, never a user's doing) and
     * answers the five questions: "{@code S3 GetObject on bucket/key was refused in 12 ms: HTTP 403, AccessDenied
     * (S3Exception: Access Denied (Service: S3, Status Code: 403, Request ID: …)); the task role lacks this permission:
     * fix the IAM policy, then deploy again; the caller's answer is on this request's summary line}" (the last part only
     * while a request is being served).
     */
    public static void upstreamFailed(String service, String operation, String resource, long started, Throwable failure, Integer status, String code) {
        long millis = millis(started);
        boolean refused = permissionRefused(status, code);
        var line = upstream(refused ? UPSTREAM.atError() : UPSTREAM.atWarn(), service, operation, "failure", millis);
        var parts = new ArrayList<String>();
        if (status != null) { line = line.addKeyValue("upstream.status", status); parts.add("HTTP " + status); }
        if (code != null) { line = line.addKeyValue("upstream.code", code); parts.add(code); }
        if (resource != null) line = line.addKeyValue("upstream.resource", resource);
        String detail = String.join(", ", parts);
        if (failure != null) line = line.addKeyValue("error.type", types(failure));
        String target = resource == null ? "" : " on " + resource;
        if (refused) {
            String why = detail.isEmpty() ? "permission refused" : detail;
            if (failure != null) why += " (" + quoted(failure) + ")";
            line.log("{} {}{} was refused in {} ms: {}; {}{}", service, operation, target, millis, why, PERMISSION_HINT,
                    org.slf4j.MDC.get(RequestLog.KEY) == null ? "" : "; the caller's answer is on this request's summary line");
            return;
        }
        if (failure != null) detail = parts.isEmpty() ? types(failure) : types(failure) + " (" + detail + ")";
        line.log("{} {}{} failed in {} ms: {}", service, operation, target, millis, detail);
    }

    /** A 403 or an AccessDenied code: the deployment lacks a grant, which is an ERROR, not a warning (owner, 2026-11-09). */
    public static boolean permissionRefused(Integer status, String code) {
        return (status != null && status == 403) || (code != null && code.startsWith("AccessDenied"));
    }

    /** The provider's HTTP status, when the failure or one of its causes carries one (a store wraps the SDK exception). */
    public static Integer status(Throwable failure) {
        int depth = 0;
        for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++) {
            if (at instanceof software.amazon.awssdk.core.exception.SdkServiceException aws) return aws.statusCode();
            if (at instanceof org.springframework.web.client.RestClientResponseException http) return http.getStatusCode().value();
        }
        return null;
    }

    /** The AWS error code (for example {@code NotAuthorizedException}) of the failure or its first AWS cause; never the provider's message. */
    public static String code(Throwable failure) {
        int depth = 0;
        for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++)
            if (at instanceof software.amazon.awssdk.awscore.exception.AwsServiceException aws && aws.awsErrorDetails() != null)
                return safe(aws.awsErrorDetails().errorCode());
        return null;
    }

    /** A provider code is only kept when it looks like one: letters, digits, dots, dashes and underscores. */
    public static String safe(String code) {
        return code != null && code.matches("[A-Za-z0-9._-]{1,80}") ? code : null;
    }

    /** The exception and its causes by type only, e.g. "ResourceAccessException < ConnectException": messages can hold secrets. */
    public static String types(Throwable failure) {
        var names = new ArrayList<String>();
        for (Throwable at = failure; at != null && names.size() < 5; at = at.getCause()) names.add(at.getClass().getSimpleName());
        return String.join(" < ", names);
    }

    /** The request path only: never the query, at most 200 characters, and no line breaks to forge a second line. */
    public static String path(HttpServletRequest request) {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI().replaceAll("[\\r\\n\\t]", "_");
        return path.length() > 200 ? path.substring(0, 200) + "…" : path;
    }
}
