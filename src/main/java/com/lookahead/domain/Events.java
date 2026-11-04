package com.lookahead.domain;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DLV-969 (owner, 2026-11-04): operational log lines the owner can read and query in CloudWatch. Each line is a plain
 * sentence, and its key-value pairs (rendered as JSON members in DEV) make it searchable: {@code event.action},
 * {@code event.outcome} ({@code success}, {@code failure} or {@code refused}), {@code account.id} (who acted),
 * {@code target.id} (what it acted on), {@code reason} (a stable code), {@code retry.*}, {@code limit.*} and
 * {@code durationMs}. Four logger names:
 * <ul>
 *   <li>{@link #EVENT} {@code lookahead.event}: important actions and their refusals;</li>
 *   <li>{@link #PAYMENT} {@code lookahead.payment}: purchases, payment attempts and memberships;</li>
 *   <li>{@link #LIMIT} {@code lookahead.limit}: a rate limit or quota was hit;</li>
 *   <li>{@link #UPSTREAM} {@code lookahead.upstream}: one line per call to Cognito, Identity, S3 or Stripe.</li>
 * </ul>
 * Never names, emails, passwords, tokens, codes, bodies or exception messages: IDs (UUIDs), stable codes, counts and
 * exception types only. Successes are INFO; refusals, limit hits, retries and a retry sequence that gives up are WARN.
 * ERROR stays for real crashes, which {@link RequestLog} and the global handler already log (the deploy fails on it).
 */
public final class Events {
    public static final Logger EVENT = LoggerFactory.getLogger("lookahead.event");
    public static final Logger PAYMENT = LoggerFactory.getLogger("lookahead.payment");
    public static final Logger LIMIT = LoggerFactory.getLogger("lookahead.limit");
    public static final Logger UPSTREAM = LoggerFactory.getLogger("lookahead.upstream");

    private Events() {}

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
        upstreamFailed(service, operation, started, failure, status(failure), code(failure));
    }

    /**
     * As above, with the provider's HTTP status and error code given by the caller (null when unknown); {@code failure}
     * is null when the provider answered with an unexpected status and nothing was thrown.
     */
    public static void upstreamFailed(String service, String operation, long started, Throwable failure, Integer status, String code) {
        long millis = millis(started);
        var line = upstream(UPSTREAM.atWarn(), service, operation, "failure", millis);
        var parts = new ArrayList<String>();
        if (status != null) { line = line.addKeyValue("upstream.status", status); parts.add("HTTP " + status); }
        if (code != null) { line = line.addKeyValue("upstream.code", code); parts.add(code); }
        String detail = String.join(", ", parts);
        if (failure != null) {
            line = line.addKeyValue("error.type", types(failure));
            detail = parts.isEmpty() ? types(failure) : types(failure) + " (" + detail + ")";
        }
        line.log("{} {} failed in {} ms: {}", service, operation, millis, detail);
    }

    /** The provider's HTTP status, when the failure carries one. */
    public static Integer status(Throwable failure) {
        if (failure instanceof software.amazon.awssdk.core.exception.SdkServiceException aws) return aws.statusCode();
        if (failure instanceof org.springframework.web.client.RestClientResponseException http) return http.getStatusCode().value();
        return null;
    }

    /** The AWS error code (for example {@code NotAuthorizedException}); never the provider's message. */
    static String code(Throwable failure) {
        if (failure instanceof software.amazon.awssdk.awscore.exception.AwsServiceException aws && aws.awsErrorDetails() != null)
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
