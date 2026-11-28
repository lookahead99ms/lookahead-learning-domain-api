package com.lookahead.domain.log;

import com.lookahead.domain.exception.SignInCheckUnavailableException;
import com.lookahead.domain.model.HealthDiagnosis;
import jakarta.servlet.http.HttpServletRequest;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientResponseException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * DLV-969 (owner, 2026-11-04): operational log lines the owner can read in CloudWatch. Plain logs
 * (owner, 2026-11-17): every line is one plain sentence a person reads without a schema; the
 * key-value pairs stay on the call (they cost nothing on a plain line, the tests assert on them,
 * and a log pipeline could render them again) but the sentence must say everything a reader needs:
 * who acted ("Owner 1a2b3c4d …"), the counts and the stable codes. Keys in use: {@code
 * event.action}, {@code event.outcome} ({@code success}, {@code failure} or {@code refused}),
 * {@code account.id} (who acted), {@code target.id} (what it acted on), {@code reason} (a stable
 * code), {@code retry.*}, {@code limit.*} and {@code durationMs}. Four logger names:
 *
 * <ul>
 *   <li>{@link #EVENT} {@code lookahead.event}: important actions and their refusals;
 *   <li>{@link #PAYMENT} {@code lookahead.payment}: purchases, payment attempts and memberships;
 *   <li>{@link #LIMIT} {@code lookahead.limit}: a rate limit or quota was hit;
 *   <li>{@link #UPSTREAM} {@code lookahead.upstream}: one line per call to Cognito, Identity, S3 or
 *       Stripe.
 * </ul>
 *
 * Levels: INFO is normal progress; WARN is an expected refusal (wrong password, limit hit, locked
 * content, validation), a retry or a retry sequence that gives up, with short wording and no stack
 * trace; ERROR is something an operator must act on (misconfiguration, permission denied,
 * dependency down, a bug). An ERROR line answers five questions in one sentence: what was being
 * done, which dependency and operation on which resource failed, why (the exception type and the
 * dependency's own message, HTTP status, AWS error code), what the caller got, and where the stack
 * trace is. On ERROR lines the dependency's message IS quoted ({@link #quoted}); never secrets,
 * tokens, passwords, cookies, Authorization headers or request bodies ({@link #scrub} removes the
 * obvious ones). INFO and WARN lines never carry exception messages, names or emails: IDs, stable
 * codes, counts and exception types only. The word ERROR appears on a line only as the level of an
 * ERROR line (the deploy's log scan searches for it).
 */
public final class EventLog {
  public static final Logger EVENT = LoggerFactory.getLogger("lookahead.event");
  public static final Logger PAYMENT = LoggerFactory.getLogger("lookahead.payment");
  public static final Logger LIMIT = LoggerFactory.getLogger("lookahead.limit");
  public static final Logger UPSTREAM = LoggerFactory.getLogger("lookahead.upstream");

  /** Hint for a connection that never reached the service: the usual cause in a private subnet. */
  public static final String NETWORK_HINT =
      "check the VPC endpoint and the security group between the task and the service";

  /** Hint for a permission refused by an IAM-governed service. */
  public static final String PERMISSION_HINT =
      "the task role lacks this permission: fix the IAM policy, then deploy again";

  private static final int MESSAGE_LIMIT = 600;
  private static final Pattern SECRETS =
      Pattern.compile(
          "(?i)\\b(password|passwd|pwd|secret|token|authorization|cookie|set-cookie|"
              + "x-api-key|api[_-]?key)\\b\\s*[=:]\\s*\"?(?:bearer\\s+)?[^\\s\"&;,)]+");
  private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(//)[^/@\\s]+@");

  private EventLog() {}

  // ---- Who acted, said in the sentence

  /**
   * "Owner 1a2b3c4d" / "Admin 1a2b3c4d" / "Account 1a2b3c4d": the role as the call site knows it
   * and the first 8 characters of the id.
   */
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
   * "{@code AccessDeniedException: User arn:… is not authorized …}": the deepest cause that has a
   * message, by simple type name, with the message scrubbed of secrets and line breaks. For ERROR
   * lines only (WARN lines use {@link #types}). Without any message the types are returned.
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
   * Removes what must never be on a log line: {@code password=…}, {@code token: …}, {@code
   * Authorization: …}, {@code Cookie: …}, {@code Bearer …}, {@code user:password@} in a URL;
   * collapses line breaks (a message must not forge a second line) and caps the length.
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
   * Where an unexpected failure came from: "{@code StudyPlanService.saveVersion
   * (StudyPlanService.java:285)}", the first frame of our own code in the failure or its causes,
   * else the top frame of the failure.
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
    String file =
        frame.getFileName() == null
            ? "unknown source"
            : frame.getFileName() + ":" + frame.getLineNumber();
    return type + "." + frame.getMethodName() + " (" + file + ")";
  }

  /**
   * "unexpected IllegalStateException in Foo.bar (Foo.java:12): the message": the why of a crash
   * line.
   */
  public static String unexpected(Throwable failure) {
    return "unexpected "
        + failure.getClass().getSimpleName()
        + " in "
        + origin(failure)
        + ": "
        + scrub(failure.getMessage() == null ? "no message" : failure.getMessage());
  }

  /**
   * A hint when the code knows the cause: a connection that never reached the service (connect
   * timeout, connection refused, unknown host) points at the VPC endpoint and security group; a
   * permission refusal at the IAM policy. Null when nothing is known.
   */
  public static String hint(Throwable failure) {
    if (permissionRefused(status(failure), code(failure))) return PERMISSION_HINT;
    int depth = 0;
    for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++) {
      if (at instanceof ConnectException
          || at instanceof UnknownHostException
          || at instanceof HttpConnectTimeoutException
          || at instanceof NoRouteToHostException) return NETWORK_HINT;
      if (at instanceof SocketTimeoutException socket
          && socket.getMessage() != null
          && socket.getMessage().toLowerCase(Locale.ROOT).contains("connect")) return NETWORK_HINT;
      if (at.getMessage() != null
          && at.getMessage()
              .toLowerCase(Locale.ROOT)
              .matches(".*connect(ion)? (timed out|refused).*")) return NETWORK_HINT;
    }
    return null;
  }

  /** INFO: the action happened. */
  public static LoggingEventBuilder success(Logger log, String action) {
    return log.atInfo().addKeyValue("event.action", action).addKeyValue("event.outcome", "success");
  }

  /**
   * WARN: the action was refused for {@code reason} (a stable code such as {@code
   * ROSTER_ACCOUNT_PERMANENT}).
   */
  public static LoggingEventBuilder refused(Logger log, String action, String reason) {
    return log.atWarn()
        .addKeyValue("event.action", action)
        .addKeyValue("event.outcome", "refused")
        .addKeyValue("reason", reason);
  }

  /** WARN: the action was tried and did not complete, or a retry sequence gave up. */
  public static LoggingEventBuilder failed(Logger log, String action, String reason) {
    return log.atWarn()
        .addKeyValue("event.action", action)
        .addKeyValue("event.outcome", "failure")
        .addKeyValue("reason", reason);
  }

  /**
   * ERROR: an operator must act (a dependency is down, a permission is missing); the sentence
   * answers the five questions above.
   */
  public static LoggingEventBuilder error(Logger log, String action, String reason) {
    return log.atError()
        .addKeyValue("event.action", action)
        .addKeyValue("event.outcome", "failure")
        .addKeyValue("reason", reason);
  }

  /** WARN: one more try follows. {@code attempt} is the try that just failed, counted from 1. */
  public static LoggingEventBuilder retry(
      Logger log, String action, String reason, int attempt, int max) {
    return failed(log, action, reason)
        .addKeyValue("retry.attempt", attempt)
        .addKeyValue("retry.max", max);
  }

  /** WARN on {@code lookahead.limit}: a limit or quota refused the request. */
  public static LoggingEventBuilder limit(
      String action, String reason, String name, long max, String window) {
    return refused(LIMIT, action, reason)
        .addKeyValue("limit.name", name)
        .addKeyValue("limit.max", max)
        .addKeyValue("limit.window", window);
  }

  /**
   * Runs {@code line} once the current transaction commits, so a success line never describes a
   * change that was rolled back; outside a transaction it runs at once.
   */
  public static void afterCommit(Runnable line) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      line.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            line.run();
          }
        });
  }

  // ---- Upstream calls

  /** The start of an upstream call, for {@link #upstreamDone} and friends. */
  public static long started() {
    return System.nanoTime();
  }

  static long millis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private static LoggingEventBuilder upstream(
      LoggingEventBuilder line, String service, String operation, String outcome, long millis) {
    return line.addKeyValue("event.action", "upstream.call")
        .addKeyValue("event.outcome", outcome)
        .addKeyValue("upstream.service", service.toLowerCase(Locale.ROOT))
        .addKeyValue("upstream.operation", operation)
        .addKeyValue("durationMs", millis);
  }

  /** INFO: e.g. "Cognito AdminDisableUser succeeded in 42 ms". */
  public static void upstreamDone(String service, String operation, long started) {
    long millis = millis(started);
    upstream(UPSTREAM.atInfo(), service, operation, "success", millis)
        .log("{} {} succeeded in {} ms", service, operation, millis);
  }

  /**
   * WARN: the provider answered and said no, e.g. a revoked token; {@code reason} is a stable code.
   */
  public static void upstreamRefused(
      String service, String operation, long started, String reason) {
    long millis = millis(started);
    upstream(UPSTREAM.atWarn(), service, operation, "refused", millis)
        .addKeyValue("reason", reason)
        .log("{} {} refused in {} ms: {}", service, operation, millis, reason);
  }

  /**
   * WARN: the call failed; logs the exception types and, for AWS and HTTP errors, the provider's
   * status and code.
   */
  public static void upstreamFailed(
      String service, String operation, long started, Throwable failure) {
    upstreamFailed(service, operation, null, started, failure, status(failure), code(failure));
  }

  /**
   * As above, naming the resource the call addressed (a bucket/key, a table, a queue), which every
   * ERROR line must say.
   */
  public static void upstreamFailed(
      String service, String operation, String resource, long started, Throwable failure) {
    upstreamFailed(service, operation, resource, started, failure, status(failure), code(failure));
  }

  /**
   * As above, with the provider's HTTP status and error code given by the caller (null when
   * unknown); {@code failure} is null when the provider answered with an unexpected status and
   * nothing was thrown.
   */
  public static void upstreamFailed(
      String service,
      String operation,
      long started,
      Throwable failure,
      Integer status,
      String code) {
    upstreamFailed(service, operation, null, started, failure, status, code);
  }

  /**
   * WARN: "{@code Cognito AdminEnableUser failed in 12 ms: NotAuthorizedException (HTTP 400,
   * NotAuthorizedException)}", types and codes only. A permission refused by an IAM-governed
   * service (HTTP 403 or an AccessDenied code) is an ERROR (owner, 2026-11-09: a grant the
   * deployment lacks is a configuration defect, never a user's doing) and answers the five
   * questions: "{@code S3 GetObject on bucket/key was refused in 12 ms: HTTP 403, AccessDenied
   * (S3Exception: Access Denied (Service: S3, Status Code: 403, Request ID: …)); the task role
   * lacks this permission: fix the IAM policy, then deploy again; the caller's answer is on this
   * request's summary line}" (the last part only while a request is being served).
   */
  public static void upstreamFailed(
      String service,
      String operation,
      String resource,
      long started,
      Throwable failure,
      Integer status,
      String code) {
    long millis = millis(started);
    boolean refused = permissionRefused(status, code);
    var line =
        upstream(
            refused ? UPSTREAM.atError() : UPSTREAM.atWarn(),
            service,
            operation,
            "failure",
            millis);
    var parts = new ArrayList<String>();
    if (status != null) {
      line = line.addKeyValue("upstream.status", status);
      parts.add("HTTP " + status);
    }
    if (code != null) {
      line = line.addKeyValue("upstream.code", code);
      parts.add(code);
    }
    if (resource != null) line = line.addKeyValue("upstream.resource", resource);
    String detail = String.join(", ", parts);
    if (failure != null) line = line.addKeyValue("error.type", types(failure));
    String target = resource == null ? "" : " on " + resource;
    if (refused) {
      String why = detail.isEmpty() ? "permission refused" : detail;
      if (failure != null) why += " (" + quoted(failure) + ")";
      line.log(
          "{} {}{} was refused in {} ms: {}; {}{}",
          service,
          operation,
          target,
          millis,
          why,
          PERMISSION_HINT,
          MDC.get(RequestLog.KEY) == null
              ? ""
              : "; the caller's answer is on this request's summary line");
      return;
    }
    if (failure != null)
      detail = parts.isEmpty() ? types(failure) : types(failure) + " (" + detail + ")";
    line.log("{} {}{} failed in {} ms: {}", service, operation, target, millis, detail);
  }

  /**
   * A 403 or an AccessDenied code: the deployment lacks a grant, which is an ERROR, not a warning
   * (owner, 2026-11-09).
   */
  public static boolean permissionRefused(Integer status, String code) {
    return (status != null && status == 403) || (code != null && code.startsWith("AccessDenied"));
  }

  /**
   * The provider's HTTP status, when the failure or one of its causes carries one (a store wraps
   * the SDK exception).
   */
  public static Integer status(Throwable failure) {
    int depth = 0;
    for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++) {
      if (at instanceof SdkServiceException aws) return aws.statusCode();
      if (at instanceof RestClientResponseException http) return http.getStatusCode().value();
    }
    return null;
  }

  /**
   * The AWS error code (for example {@code NotAuthorizedException}) of the failure or its first AWS
   * cause; never the provider's message.
   */
  public static String code(Throwable failure) {
    int depth = 0;
    for (Throwable at = failure; at != null && depth < 10; at = at.getCause(), depth++)
      if (at instanceof AwsServiceException aws && aws.awsErrorDetails() != null)
        return safe(aws.awsErrorDetails().errorCode());
    return null;
  }

  /**
   * A provider code is only kept when it looks like one: letters, digits, dots, dashes and
   * underscores.
   */
  public static String safe(String code) {
    return code != null && code.matches("[A-Za-z0-9._-]{1,80}") ? code : null;
  }

  /**
   * The exception and its causes by type only, e.g. "ResourceAccessException < ConnectException":
   * messages can hold secrets.
   */
  public static String types(Throwable failure) {
    var names = new ArrayList<String>();
    for (Throwable at = failure; at != null && names.size() < 5; at = at.getCause())
      names.add(at.getClass().getSimpleName());
    return String.join(" < ", names);
  }

  /**
   * The request path only: never the query, at most 200 characters, and no line breaks to forge a
   * second line.
   */
  public static String path(HttpServletRequest request) {
    String path =
        request.getRequestURI() == null
            ? ""
            : request.getRequestURI().replaceAll("[\\r\\n\\t]", "_");
    return path.length() > 200 ? path.substring(0, 200) + "…" : path;
  }

  // ---- Startup refusals
  // ------------------------------------------------------------------------------
  /**
   * DLV-969 (owner, 2026-11-04): a startup check that refuses to start writes one line on {@code
   * lookahead.startup} first, naming the setting, secret or file by its NAME (environment variable
   * or property) and what is wrong with it, never its value, and the exception types when there are
   * any. The exception that stops startup keeps its fixed text (Spring then logs "Application run
   * failed" with the stack trace). Plain logs (owner, 2026-11-17): a refused startup is a
   * misconfiguration an operator must fix, so the line is an ERROR; the sentence already names the
   * setting and the problem, which is the hint. The failure's message is NOT quoted here, on
   * purpose: these checks validate the values of settings (endpoint URLs, secrets, certificate
   * files), and a validation exception may echo the value it rejected.
   */
  public static final Logger STARTUP = LoggerFactory.getLogger("lookahead.startup");

  // ---- Failures nobody expected
  // ---------------------------------------------------------------------------

  /**
   * DLV-1016: the one logger of ERROR lines that carry a stack trace (the error handlers and the
   * request filter).
   */
  public static final Logger FAILURE = LoggerFactory.getLogger("lookahead.failure");

  /** One ERROR line with the stack trace; the last argument is the exception. */
  public static void errorWithStack(String message, Object... argumentsThenFailure) {
    FAILURE.error(message, argumentsThenFailure);
  }

  /**
   * One WARN line on the same logger, without keys and without a stack trace: a request that was
   * refused or ended early.
   */
  public static void warnWithoutStack(String message, Object... arguments) {
    FAILURE.warn(message, arguments);
  }

  // ---- The request summary line
  // ---------------------------------------------------------------------------

  public static final Logger HTTP = LoggerFactory.getLogger("lookahead.http");

  /**
   * One line per request. Successes: "GET /x -> 200 in 3 ms". Failures add the code and why: "POST
   * /x -> 403 DELETE_FORBIDDEN: Only the owner… in 9 ms". 403, 429 and 5xx are WARN, everything
   * else INFO.
   */
  public static void requestSummary(
      String method, String path, int status, long millis, RequestStatusReason.Reason reason) {
    var line =
        (status >= 500 || status == 403 || status == 429 ? HTTP.atWarn() : HTTP.atInfo())
            .addKeyValue("http.method", method)
            .addKeyValue("url.path", path)
            .addKeyValue("http.status", status)
            .addKeyValue("durationMs", millis);
    if (reason == null) {
      line.log("{} {} -> {} in {} ms", method, path, status, millis);
      return;
    }
    line.addKeyValue("error.code", reason.code())
        .addKeyValue("error.reason", reason.words())
        .log(
            "{} {} -> {} {}: {} in {} ms",
            method,
            path,
            status,
            reason.code(),
            reason.words(),
            millis);
  }

  // ---- Readiness
  // ------------------------------------------------------------------------------------------

  public static final Logger HEALTH = LoggerFactory.getLogger("lookahead.health");

  /** What to do, by reason code, where the code knows it. */
  static final Map<String, String> HEALTH_HINTS =
      Map.of(
          "DATA_VERSION_BEHIND", "run 07 · Migrate DEV data with this release, then deploy again",
          "ACCESS_DENIED",
              "the task role lacks dynamodb:GetItem on this table: fix the IAM policy, then deploy"
                  + " again",
          "TABLE_MISSING", "create the table with the Infra stack, then deploy again");

  /**
   * Records the state of a readiness check ({@code down} is null when it passed) and writes its
   * line when one is due, while holding the check's lock so that the lines of one check keep their
   * order.
   */
  public static void readiness(HealthLog check, HealthDiagnosis down) {
    synchronized (check) {
      if (!check.due(down)) return;
      if (down == null) readinessUp(check.component(), check.upWords());
      else readinessDown(check.component(), down.reason(), down.words());
    }
  }

  /** A readiness check reached UP: INFO, once. */
  static void readinessUp(String component, String upWords) {
    success(HEALTH, "readiness")
        .addKeyValue("health.component", component)
        .log("Readiness UP for {}: {}", component, upWords);
  }

  /**
   * A readiness check is DOWN: ERROR with the reason code in the sentence and a hint where the code
   * knows the cure.
   */
  static void readinessDown(String component, String reason, String words) {
    String hint = HEALTH_HINTS.get(reason);
    error(HEALTH, "readiness", reason)
        .addKeyValue("health.component", component)
        .log(
            "Readiness DOWN for {}: {} ({}); {}",
            component,
            words,
            reason,
            hint == null
                ? "the container health check fails until this is fixed"
                : hint + "; the container health check fails until then");
  }

  /**
   * ERROR: e.g. "Startup refused: LOOKAHEAD_TLS_SERVER_KEY does not match
   * LOOKAHEAD_TLS_SERVER_CERTIFICATE".
   */
  public static void startupRefused(String setting, String reason, String problem) {
    startupRefused(setting, reason, problem, null);
  }

  /** As above, with the types of the failure that showed the problem (never its message). */
  public static void startupRefused(
      String setting, String reason, String problem, Throwable failure) {
    var line =
        STARTUP
            .atError()
            .addKeyValue("event.action", "startup.check")
            .addKeyValue("event.outcome", "refused")
            .addKeyValue("reason", reason)
            .addKeyValue("setting", setting);
    if (failure == null) {
      line.log("Startup refused: {} {}; the app stops before it takes traffic", setting, problem);
      return;
    }
    String types = EventLog.types(failure);
    line.addKeyValue("error.type", types)
        .log(
            "Startup refused: {} {} ({}); the app stops before it takes traffic",
            setting,
            problem,
            types);
  }

  /**
   * Logs as {@link #startupRefused(String, String, String)} and returns the exception to throw,
   * with its fixed text.
   */
  public static IllegalStateException startupRefuse(
      String setting, String reason, String problem, String message) {
    startupRefused(setting, reason, problem);
    return new IllegalStateException(message);
  }

  // ---- Refused bearer tokens
  // --------------------------------------------------------------------------
  /**
   * DLV-969 (owner, 2026-11-04): why a bearer token was refused. The 401 answer and its summary
   * line say only AUTHENTICATION_REQUIRED, so a wrong issuer, client ID or Gateway secret made
   * every call fail with nothing to go on. One WARN per reason when it starts and once a minute
   * while it lasts: the reason code the check attached (for example {@code ISSUER_MISMATCH}), else
   * the OAuth error code, and the exception types. Never the token or a claim value. A request
   * without a token is ordinary (no line), and an unavailable identity check is logged where it
   * failed.
   */
  private static final LogThrottle AUTHENTICATION = new LogThrottle();

  public static void authenticationRefused(
      HttpServletRequest request, AuthenticationException error) {
    if (error instanceof InsufficientAuthenticationException
        || error instanceof SignInCheckUnavailableException) return;
    String types = EventLog.types(error);
    String reason =
        error instanceof AuthenticationServiceException
            ? "AUTHENTICATION_UNAVAILABLE"
            : "TOKEN_INVALID";
    if (error instanceof OAuth2AuthenticationException oauth && oauth.getError() != null) {
      String described = EventLog.safe(oauth.getError().getDescription());
      reason =
          described != null && described.matches("[A-Z][A-Z0-9_]*")
              ? described
              : EventLog.safe(oauth.getError().getErrorCode()) != null
                  ? oauth.getError().getErrorCode()
                  : reason;
    }
    if (!AUTHENTICATION.due(reason + "|" + types)) return;
    int status = error instanceof AuthenticationServiceException ? 503 : 401;
    EventLog.refused(EventLog.EVENT, "authentication", reason)
        .addKeyValue("url.path", EventLog.path(request))
        .addKeyValue("http.status", status)
        .addKeyValue("error.type", types)
        .log(
            "Bearer token refused on {} with {}: {} ({})",
            EventLog.path(request),
            status,
            reason,
            types);
  }

  // ---- Security audit line
  // ---------------------------------------------------------------------------------
  /** Fixed event codes only: no request, identity, token, exception or payload fields. */
  public enum SecurityAudit {
    AUTHENTICATION_REJECTED,
    AUTHORIZATION_REJECTED,
    GATEWAY_IDENTITY_REJECTED
  }

  public static void securityAudit(SecurityAudit event) {
    LoggerFactory.getLogger("lookahead.security.audit")
        .info("LOOKAHEAD_SECURITY_V1 event={} id={}", event.name(), UUID.randomUUID());
  }

  // ---- Values made safe for a log line or an error body (moved from GlobalExceptionHandler)
  // ----------------
  /**
   * The caller chooses the method and path; a line break in them must not forge a second log line.
   */
  public static String forLog(String value) {
    return value == null ? "" : redact(value).replaceAll("[\\r\\n]", "_");
  }

  private static final Pattern MERGE_TOKEN =
      Pattern.compile("/account/merge/[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])");

  /**
   * DLV-969 S36: a merge link's single-use token never reaches a log line or an error body; the
   * rest of the path does.
   */
  public static String redact(String path) {
    return path == null ? null : MERGE_TOKEN.matcher(path).replaceAll("/account/merge/[token]");
  }
}
