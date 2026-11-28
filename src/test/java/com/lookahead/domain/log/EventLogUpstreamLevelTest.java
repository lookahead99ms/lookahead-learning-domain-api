package com.lookahead.domain.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

/**
 * Owner, 2026-11-09: a permission refusal by an IAM-governed service is an ERROR line; other
 * upstream failures stay WARN.
 */
class EventLogUpstreamLevelTest {
  @Test
  void aForbiddenUpstreamCallIsAnErrorAndOtherFailuresStayWarnings() {
    MDC.put("requestId", "6f1c2b9e-4d3a-4e8b-9c7d-0a1b2c3d4e5f");
    try (var log = LogCapture.start()) {
      EventLog.upstreamFailed("s3", "ListObjectsV2", System.nanoTime(), null, 403, "AccessDenied");
      EventLog.upstreamFailed(
          "s3", "GetObject", System.nanoTime(), null, null, "AccessDeniedException");
      EventLog.upstreamFailed("s3", "PutObject", System.nanoTime(), null, 500, "InternalError");
      EventLog.upstreamFailed(
          "ses",
          "SendEmail",
          System.nanoTime(),
          new IllegalStateException("synthetic"),
          null,
          null);
      EventLog.upstreamRefused("cognito", "GetUser", System.nanoTime(), "NotAuthorizedException");
      var lines = log.lines("upstream.call");
      assertThat(lines).hasSize(5);
      assertThat(lines.get(0).getLevel().toString()).isEqualTo(Level.ERROR.toString());
      // Plain logs (owner, 2026-11-17): the ERROR line says what was refused, why, the hint, and
      // where the caller's answer is.
      assertThat(lines.get(0).getFormattedMessage())
          .isEqualTo(
              "s3 ListObjectsV2 was refused in 0 ms: HTTP 403, AccessDenied; the task role lacks"
                  + " this permission: fix the IAM policy, then deploy again; the caller's answer"
                  + " is on this request's summary line");
      assertThat(LogCapture.keys(lines.get(0)))
          .containsEntry("upstream.status", 403)
          .containsEntry("upstream.code", "AccessDenied")
          .containsEntry("event.outcome", "failure");
      assertThat(lines.get(1).getLevel().toString()).isEqualTo(Level.ERROR.toString());
      assertThat(lines.get(2).getLevel().toString()).isEqualTo(Level.WARN.toString());
      assertThat(lines.get(3).getLevel().toString()).isEqualTo(Level.WARN.toString());
      assertThat(lines.get(4).getLevel().toString()).isEqualTo(Level.WARN.toString());
      log.containsNone("synthetic");
    } finally {
      MDC.remove("requestId");
    }
  }

  /**
   * Plain logs (owner, 2026-11-17): a refusal wrapped by a store names the resource, quotes the SDK
   * and, outside a request, says nothing about a summary line.
   */
  @Test
  void aWrappedAccessDeniedNamesTheResourceAndQuotesTheSdk() {
    var denied =
        DynamoDbException.builder()
            .statusCode(400)
            .message(
                "User arn:aws:sts::123456789012:assumed-role/lookahead-dev-app-task/x is not"
                    + " authorized to perform: dynamodb:PartiQLInsert")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").build())
            .build();
    var wrapped = new IllegalStateException("store wrapper", denied);
    assertThat(EventLog.status(wrapped)).isEqualTo(400);
    assertThat(EventLog.code(wrapped)).isEqualTo("AccessDeniedException");
    assertThat(EventLog.hint(wrapped)).isEqualTo(EventLog.PERMISSION_HINT);
    assertThat(EventLog.quoted(wrapped))
        .startsWith(
            "DynamoDbException: User"
                + " arn:aws:sts::123456789012:assumed-role/lookahead-dev-app-task/x is not"
                + " authorized");
    try (var log = LogCapture.start()) {
      EventLog.upstreamFailed(
          "DynamoDB",
          "ExecuteTransaction",
          "lookahead-dev-user-study-plan-details",
          System.nanoTime(),
          wrapped);
      var line = log.only("upstream.call");
      assertThat(line.getLevel().toString()).isEqualTo(Level.ERROR.toString());
      assertThat(line.getFormattedMessage())
          .startsWith(
              "DynamoDB ExecuteTransaction on lookahead-dev-user-study-plan-details was refused in"
                  + " ")
          .contains(
              " ms: HTTP 400, AccessDeniedException (DynamoDbException: User"
                  + " arn:aws:sts::123456789012:assumed-role/lookahead-dev-app-task/x is not"
                  + " authorized to perform: dynamodb:PartiQLInsert (Service: null, Status Code:"
                  + " 400, Request ID: null)); "
                  + EventLog.PERMISSION_HINT)
          .doesNotContain("summary line");
      assertThat(LogCapture.keys(line))
          .containsEntry("upstream.resource", "lookahead-dev-user-study-plan-details");
    }
  }

  /**
   * The scrub keeps the dependency's words but never a secret-shaped value, a bearer token or a
   * URL's credentials.
   */
  @Test
  void quotedMessagesAreScrubbedOfSecretsAndLineBreaks() {
    assertThat(
            EventLog.scrub(
                "FATAL: password authentication failed for user \"lookahead_platform_app\""))
        .isEqualTo("FATAL: password authentication failed for user \"lookahead_platform_app\"");
    assertThat(
            EventLog.scrub(
                "connect failed password=hunter2 token: abc.def Authorization: Bearer"
                    + " eyJhbGci.x.y\n"
                    + "second line"))
        .isEqualTo(
            "connect failed password=[redacted] token=[redacted] Authorization=[redacted] second"
                + " line");
    assertThat(EventLog.scrub("jdbc:postgresql://user:hunter2@db.example/lookahead_platform"))
        .isEqualTo("jdbc:postgresql://[redacted]@db.example/lookahead_platform");
    assertThat(EventLog.scrub("x".repeat(700))).hasSize(601).endsWith("…");
    assertThat(EventLog.quoted(new IllegalStateException(null, new IOException())))
        .isEqualTo("IllegalStateException < IOException");
    assertThat(EventLog.quoted(null)).isEqualTo("no exception");
    assertThat(EventLog.hint(new RuntimeException("x", new ConnectException("Connection refused"))))
        .isEqualTo(EventLog.NETWORK_HINT);
    assertThat(
            EventLog.hint(
                new RuntimeException("Unable to execute HTTP request: connect timed out")))
        .isEqualTo(EventLog.NETWORK_HINT);
    assertThat(EventLog.hint(new IllegalStateException("plain"))).isNull();
    assertThat(EventLog.actor("Owner", UUID.fromString("6f1c2b9e-4d3a-4e8b-9c7d-0a1b2c3d4e5f")))
        .isEqualTo("Owner 6f1c2b9e");
    assertThat(EventLog.actor("Account", null)).isEqualTo("Account unknown");
    assertThat(EventLog.origin(new IllegalStateException("here")))
        .startsWith(
            "EventLogUpstreamLevelTest.quotedMessagesAreScrubbedOfSecretsAndLineBreaks"
                + " (EventLogUpstreamLevelTest.java:");
  }
}
