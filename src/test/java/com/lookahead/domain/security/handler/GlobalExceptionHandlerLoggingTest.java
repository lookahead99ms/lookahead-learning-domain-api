package com.lookahead.domain.security.handler;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.RequestLog;
import java.net.ConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.core.exception.SdkClientException;

/** DLV-969 (owner, 2026-11-03): failures reach CloudWatch, so the owner can see what went wrong. */
class GlobalExceptionHandlerLoggingTest {
  private final GlobalExceptionHandler global = new GlobalExceptionHandler();
  private final AccountErrorHandler accounts = new AccountErrorHandler();
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @BeforeEach
  void capture() {
    logged.start();
    logger().addAppender(logged);
  }

  @AfterEach
  void release() {
    logger().detachAppender(logged);
  }

  @Test
  void anUnexpectedFailureIsLoggedWithItsStackAndAReferenceTheCallerAlsoGets() {
    var failure = new IllegalStateException("Required scenario template is missing");
    var response =
        global.handleUnexpected(failure, request("POST", "/internal/v1/test-accounts/roster/next"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    assertThat(response.getBody().details()).singleElement().asString().startsWith("Reference: ");
    String reference = response.getBody().details().getFirst().substring("Reference: ".length());

    assertThat(logged.list)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.ERROR);
          // Plain logs (owner, 2026-11-17): what was being done, why and where in our code, what
          // the caller got, the reference.
          assertThat(event.getFormattedMessage())
              .matches(
                  "POST /internal/v1/test-accounts/roster/next failed: unexpected"
                      + " IllegalStateException in"
                      + " GlobalExceptionHandlerLoggingTest\\.anUnexpectedFailureIsLog"
                      + "gedWithItsStackAndAReferenceTheCallerAlsoGets"
                      + " \\(GlobalExceptionHandlerLoggingTest\\.java:\\d+\\): Required scenario"
                      + " template is missing; answered 500 INTERNAL_SERVER_ERROR; reference "
                      + reference);
          assertThat(event.getThrowableProxy().getClassName())
              .isEqualTo(IllegalStateException.class.getName());
          assertThat(event.getThrowableProxy().getMessage())
              .isEqualTo("Required scenario template is missing");
        });
  }

  /**
   * DLV-969 (owner, 2026-11-04): the reference the caller quotes is the request ID on every line of
   * the request.
   */
  @Test
  void theReferenceIsTheRequestIdOfTheRequestBeingServed() {
    String requestId = "6f1c2b9e-4d3a-4e8b-9c7d-0a1b2c3d4e5f";
    MDC.put("requestId", requestId);
    try {
      var response =
          global.handleUnexpected(
              new IllegalStateException("boom"), request("GET", "/api/v1/plans"));

      assertThat(response.getBody().details()).containsExactly("Reference: " + requestId);
      assertThat(logged.list)
          .singleElement()
          .extracting(ILoggingEvent::getFormattedMessage)
          .asString()
          .startsWith("GET /api/v1/plans failed: unexpected IllegalStateException in ")
          .endsWith(": boom; answered 500 INTERNAL_SERVER_ERROR; reference " + requestId);
    } finally {
      MDC.remove("requestId");
    }
  }

  @Test
  void theCallersOwnMistakesKeepTheir4xxAndLogOneWarningWithoutAStack() throws Exception {
    var missing =
        global.handleUnexpected(
            new NoResourceFoundException(HttpMethod.GET, "/nowhere", null),
            request("GET", "/nowhere"));
    var method =
        global.handleUnexpected(
            new HttpRequestMethodNotSupportedException("DELETE"),
            request("DELETE", "/api/v1/plans"));

    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(method.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(method.getBody().details()).isEmpty();
    assertThat(logged.list)
        .extracting(ILoggingEvent::getLevel)
        .containsExactly(Level.WARN, Level.WARN);
    assertThat(logged.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "Request refused: GET /nowhere -> 404 NoResourceFoundException",
            "Request refused: DELETE /api/v1/plans -> 405 HttpRequestMethodNotSupportedException");
    assertThat(logged.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
  }

  @Test
  void aServerSideStatusExceptionIsStillAnUnexpectedFailure() {
    var response =
        global.handleUnexpected(
            new ResponseStatusException(HttpStatus.BAD_GATEWAY), request("GET", "/api/v1/plans"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(logged.list)
        .singleElement()
        .extracting(ILoggingEvent::getLevel)
        .isEqualTo(Level.ERROR);
  }

  @Test
  void aLineBreakInThePathCannotForgeASecondLogLine() {
    global.handleUnexpected(
        new IllegalStateException("boom"), request("GET", "/api/v1/plans\r\nERROR forged"));

    assertThat(logged.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .startsWith("GET /api/v1/plans__ERROR forged failed: unexpected IllegalStateException in ")
        .doesNotContain("\n", "\r");
    assertThat(EventLog.forLog(null)).isEmpty();
  }

  /**
   * Plain logs (owner, 2026-11-17): a dependency down is an ERROR that names the call, quotes the
   * SDK's words and gives the reference.
   */
  @Test
  void aStorageOutageIsAnErrorNamingTheStoreAndItsCauseWhileTheCallerSeesOnlyTheSafeMessage() {
    var outage =
        SdkClientException.create(
            "Unable to execute HTTP request: connection to dynamodb.internal refused",
            new ConnectException("connection refused"));
    var request = request("PUT", "/api/v1/plans/current");
    request.setAttribute(RequestLog.STARTED, System.nanoTime());
    var response = accounts.storeUnavailable(outage, request);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().toString())
        .doesNotContain("connection refused", "dynamodb.internal");
    assertThat(logged.list)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.ERROR);
          assertThat(event.getFormattedMessage())
              .matches(
                  "PUT /api/v1/plans/current failed after \\d+ ms: DynamoDB call failed"
                      + " \\(ConnectException: connection refused\\); answered 503"
                      + " ACCOUNT_STORAGE_UNAVAILABLE; reference [0-9a-f-]{36}; check the VPC"
                      + " endpoint and the security group between the task and the service");
          assertThat(event.getThrowableProxy().getMessage())
              .isEqualTo("Unable to execute HTTP request: connection to dynamodb.internal refused");
        });
  }

  private static MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setRequestURI(path);
    return request;
  }

  /** DLV-1016: both handlers write their ERROR and WARN lines through EventLog's failure logger. */
  private static Logger logger() {
    return (Logger) LoggerFactory.getLogger("lookahead.failure");
  }
}
