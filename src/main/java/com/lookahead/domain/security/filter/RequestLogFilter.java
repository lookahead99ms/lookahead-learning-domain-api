package com.lookahead.domain.security.filter;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.RequestLog;
import com.lookahead.domain.log.RequestStatusReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DLV-969 (owner, 2026-11-04): every request carries the request ID the Gateway gave it in {@code
 * X-Request-Id}, so one search in CloudWatch shows the request in both services. Only the Gateway
 * can reach Domain (mTLS), so its ID is trusted when it is a UUID; anything else gets a new one.
 * The ID is on every log line written while the request runs and goes back in the response. Each
 * request also writes one summary line: method, path (never the query), status and time taken, and
 * for every 4xx and 5xx its code and the reason in words ({@link RequestStatusReason}). 403, 429
 * and 5xx are WARN. Health checks and successful static files stay quiet.
 *
 * <p>A failure that escapes everything else is logged at ERROR with its stack trace and the request
 * ID as its reference; the caller gets that reference and nothing else. Plain logs (owner,
 * 2026-11-17): the ERROR line says what was being done, why (the exception, where in our code it
 * came from, its message), what the caller got and the reference: "{@code PUT /api/v1/plans/current
 * failed after 3 ms: unexpected IllegalStateException in Foo.bar (Foo.java:12): the message;
 * answered 500 UNEXPECTED_ERROR; reference <request id>}".
 *
 * <p>With the {@code lower-cost-dev} profile (DEV behind application mTLS) a request that ends 401
 * or 403 also writes one security audit event, before its summary line. DLV-1016: that was a filter
 * of its own at order -250; no filter between that place and this one answers 401 or 403 or changes
 * the status of an answer on its way back, and neither place writes the event for a request that
 * ended in an exception, so the event is written for the same requests.
 *
 * <p>DLV-1016: this filter was {@code log.RequestLog}. Its bean name stays {@code requestLog} and
 * its order stays the highest, so it is still the outermost servlet filter, ahead of Spring Boot's
 * character-encoding filter of the same order (an application component is registered before an
 * auto-configured one).
 */
@Component("requestLog")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Request-Id";
  private static final Set<String> METHODS =
      Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
  private final boolean securityAudited;

  public RequestLogFilter(Environment environment) {
    this.securityAudited = environment.acceptsProfiles(Profiles.of("lower-cost-dev"));
  }

  /** The Gateway's ID when it is a UUID (in its canonical lowercase form), otherwise a new one. */
  static String requestId(String inbound) {
    if (inbound != null) {
      try {
        String id = UUID.fromString(inbound).toString();
        if (id.equalsIgnoreCase(inbound)) return id;
      } catch (IllegalArgumentException notAUuid) {
        // Not one of ours: start a new ID.
      }
    }
    return UUID.randomUUID().toString();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = requestId(request.getHeader(HEADER));
    long started = System.nanoTime();
    request.setAttribute(RequestLog.STARTED, started);
    MDC.put(RequestLog.KEY, id);
    response.setHeader(HEADER, id);
    var watched = new RequestStatusReason(response);
    RequestStatusReason.Reason reason = null;
    try {
      chain.doFilter(request, watched);
      if (securityAudited) securityAudit(watched.getStatus());
      reason = watched.reason();
    } catch (IOException | ServletException | RuntimeException failure) {
      reason = failed(request, response, id, failure);
    } finally {
      summary(request, response.getStatus(), (System.nanoTime() - started) / 1_000_000, reason);
      MDC.remove(RequestLog.KEY);
    }
  }

  private static RequestStatusReason.Reason failed(
      HttpServletRequest request, HttpServletResponse response, String id, Exception failure)
      throws IOException {
    if (response.isCommitted()) {
      // The answer was already on its way; usually the caller left. Nothing can be sent, so no
      // ERROR.
      EventLog.warnWithoutStack(
          "Request ended early: {} {} {}",
          method(request),
          EventLog.path(request),
          EventLog.types(failure));
      return response.getStatus() >= 400
          ? new RequestStatusReason.Reason(
              "HTTP_" + response.getStatus(), RequestStatusReason.describe(response.getStatus()))
          : null;
    }
    EventLog.errorWithStack(
        "{} {} {}: {}; answered 500 UNEXPECTED_ERROR; reference {}",
        method(request),
        EventLog.path(request),
        RequestLog.failedAfter(request),
        EventLog.unexpected(failure),
        id,
        failure);
    response.reset();
    response.setHeader(HEADER, id);
    response.setHeader("Cache-Control", "no-store");
    response.setStatus(500);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            "{\"code\":\"UNEXPECTED_ERROR\",\"message\":\"Something went wrong. Quote reference "
                + id
                + " when you report it.\",\"reference\":\""
                + id
                + "\"}");
    return new RequestStatusReason.Reason(
        "UNEXPECTED_ERROR",
        EventLog.types(failure) + ": see the failure line with the same request id");
  }

  /** Fixed event codes only: no request, identity, token, exception or payload fields. */
  private static void securityAudit(int status) {
    if (status == 401) EventLog.securityAudit(EventLog.SecurityAudit.AUTHENTICATION_REJECTED);
    else if (status == 403) EventLog.securityAudit(EventLog.SecurityAudit.AUTHORIZATION_REJECTED);
  }

  private static void summary(
      HttpServletRequest request, int status, long millis, RequestStatusReason.Reason reason) {
    String path = EventLog.path(request);
    if (!quiet(path, status))
      EventLog.requestSummary(method(request), path, status, millis, reason);
  }

  static boolean quiet(String path, int status) {
    if (path.startsWith("/actuator/") || path.equals("/health")) return true;
    String last = path.substring(path.lastIndexOf('/') + 1);
    return status < 400 && last.contains(".");
  }

  static String method(HttpServletRequest request) {
    return METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
  }
}
