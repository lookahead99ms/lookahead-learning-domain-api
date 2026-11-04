package com.lookahead.learning.content.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/** DLV-969 (owner, 2026-11-03): failures reach CloudWatch, so the owner can see what went wrong. */
class ErrorLoggingTest {
    private final GlobalExceptionHandler global = new GlobalExceptionHandler();
    private final AccountErrorHandler accounts = new AccountErrorHandler();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void capture() {
        logged.start();
        logger(GlobalExceptionHandler.class).addAppender(logged);
        logger(AccountErrorHandler.class).addAppender(logged);
    }

    @AfterEach
    void release() {
        logger(GlobalExceptionHandler.class).detachAppender(logged);
        logger(AccountErrorHandler.class).detachAppender(logged);
    }

    @Test
    void anUnexpectedFailureIsLoggedWithItsStackAndAReferenceTheCallerAlsoGets() {
        var failure = new IllegalStateException("Required scenario template is missing");
        var response = global.handleUnexpected(failure, request("POST", "/internal/v1/test-accounts/roster/next"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().details()).singleElement().asString().startsWith("Reference: ");
        String reference = response.getBody().details().getFirst().substring("Reference: ".length());

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Unexpected failure reference=" + reference + " POST /internal/v1/test-accounts/roster/next");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("Required scenario template is missing");
        });
    }

    @Test
    void theCallersOwnMistakesKeepTheir4xxAndLogOneWarningWithoutAStack() throws Exception {
        var missing = global.handleUnexpected(new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/nowhere", null),
                request("GET", "/nowhere"));
        var method = global.handleUnexpected(new HttpRequestMethodNotSupportedException("DELETE"), request("DELETE", "/api/v1/plans"));

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(method.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(method.getBody().details()).isEmpty();
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.WARN);
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Request refused: GET /nowhere -> 404 NoResourceFoundException",
                "Request refused: DELETE /api/v1/plans -> 405 HttpRequestMethodNotSupportedException");
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void aServerSideStatusExceptionIsStillAnUnexpectedFailure() {
        var response = global.handleUnexpected(new ResponseStatusException(HttpStatus.BAD_GATEWAY), request("GET", "/api/v1/plans"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.ERROR);
    }

    @Test
    void aLineBreakInThePathCannotForgeASecondLogLine() {
        global.handleUnexpected(new IllegalStateException("boom"), request("GET", "/api/v1/plans\r\nERROR forged"));

        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .endsWith("GET /api/v1/plans__ERROR forged")
                .doesNotContain("\n", "\r");
        assertThat(GlobalExceptionHandler.forLog(null)).isEmpty();
    }

    @Test
    void aStorageOutageIsLoggedAsAWarningWithItsCauseWhileTheCallerSeesOnlyTheSafeMessage() {
        var outage = new DataAccessResourceFailureException("connection refused");
        var response = accounts.unavailable(outage, request("PUT", "/api/v1/plans/current"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString()).doesNotContain("connection refused");
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("Account storage unavailable: PUT /api/v1/plans/current");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("connection refused");
        });
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private static Logger logger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }
}
