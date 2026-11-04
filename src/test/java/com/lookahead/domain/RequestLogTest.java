package com.lookahead.domain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** DLV-969 (owner, 2026-11-04): one request ID per request, shared with the Gateway, and one summary line. */
class RequestLogTest {
    private static final String GATEWAY_ID = "6f1c2b9e-4d3a-4e8b-9c7d-0a1b2c3d4e5f";
    private final RequestLog filter = new RequestLog();
    private final ListAppender<ILoggingEvent> requests = new ListAppender<>();
    private final ListAppender<ILoggingEvent> failures = new ListAppender<>();

    @BeforeEach
    void capture() {
        requests.start();
        failures.start();
        logger("lookahead.http").addAppender(requests);
        logger(RequestLog.class.getName()).addAppender(failures);
    }

    @AfterEach
    void release() {
        logger("lookahead.http").detachAppender(requests);
        logger(RequestLog.class.getName()).detachAppender(failures);
        MDC.clear();
    }

    @Test
    void theGatewaysRequestIdIsReusedOnEveryLineAndEchoedBack() throws Exception {
        var request = request("GET", "/api/v1/plans");
        request.addHeader("X-Request-Id", GATEWAY_ID);
        var response = new MockHttpServletResponse();
        var during = new AtomicReference<String>();

        filter.doFilter(request, response, (req, res) -> during.set(MDC.get("requestId")));

        assertThat(during.get()).isEqualTo(GATEWAY_ID);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(GATEWAY_ID);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(requests.list).singleElement().extracting(event -> event.getMDCPropertyMap().get("requestId"))
                .isEqualTo(GATEWAY_ID);
    }

    @Test
    void anUppercaseUuidIsKeptInItsCanonicalLowercaseForm() {
        assertThat(RequestLog.requestId(GATEWAY_ID.toUpperCase())).isEqualTo(GATEWAY_ID);
    }

    @Test
    void aMissingOrForeignRequestIdIsReplacedByANewUuid() throws Exception {
        for (String inbound : new String[] {null, "", "not-a-uuid", "1-1-1-1-1", GATEWAY_ID + "\nERROR forged",
                "{\"injected\":true}"}) {
            var request = request("GET", "/api/v1/plans");
            if (inbound != null) request.addHeader("X-Request-Id", inbound);
            var response = new MockHttpServletResponse();
            var during = new AtomicReference<String>();

            filter.doFilter(request, response, (req, res) -> during.set(MDC.get("requestId")));

            String id = response.getHeader("X-Request-Id");
            assertThat(id).isNotEqualTo(inbound).isEqualTo(during.get()).isEqualTo(UUID.fromString(id).toString());
            assertThat(MDC.get("requestId")).isNull();
        }
    }

    @Test
    void outsideARequestTheReferenceIsAFreshUuidAndInsideItIsTheRequestId() {
        String first = RequestLog.reference();
        assertThat(UUID.fromString(first).toString()).isEqualTo(first);
        assertThat(RequestLog.reference()).isNotEqualTo(first);

        MDC.put("requestId", GATEWAY_ID);
        assertThat(RequestLog.reference()).isEqualTo(GATEWAY_ID);
    }

    @Test
    void eachRequestWritesOneSummaryLineAtInfoWithItsKeyValuePairs() throws Exception {
        var request = request("POST", "/api/v1/plans/current");
        request.setQueryString("token=secret");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ((MockHttpServletResponse) res).setStatus(201));

        assertThat(requests.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).matches("POST /api/v1/plans/current -> 201 in \\d+ ms");
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key)
                    .containsExactly("http.method", "url.path", "http.status", "durationMs");
            assertThat(value(event, "http.method")).isEqualTo("POST");
            assertThat(value(event, "url.path")).isEqualTo("/api/v1/plans/current");
            assertThat(value(event, "http.status")).isEqualTo(201);
            assertThat(value(event, "durationMs")).isInstanceOf(Long.class);
            assertThat(event.toString() + event.getKeyValuePairs()).doesNotContain("token", "secret");
        });
        assertThat(failures.list).isEmpty();
    }

    @Test
    void aCallerMistakeIsInfoAndAServerFailureIsWarn() throws Exception {
        filter.doFilter(request("GET", "/api/v1/nowhere"), new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletResponse) res).setStatus(404));
        filter.doFilter(request("GET", "/api/v1/plans"), new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletResponse) res).setStatus(503));

        assertThat(requests.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.INFO, Level.WARN);
        assertThat(requests.list.get(1).getFormattedMessage()).startsWith("GET /api/v1/plans -> 503 in ");
    }

    @Test
    void healthChecksAndSuccessfulFilesStayQuietButAFailedFileIsLogged() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/health", "/favicon.ico",
                "/docs/v3/api-docs.json"}) {
            filter.doFilter(request("GET", path), new MockHttpServletResponse(), (req, res) -> { });
        }
        assertThat(requests.list).isEmpty();

        filter.doFilter(request("GET", "/files/missing.css"), new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletResponse) res).setStatus(404));
        filter.doFilter(request("GET", "/healthy"), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(requests.list).extracting(ILoggingEvent::getFormattedMessage)
                .satisfiesExactly(line -> assertThat(line).startsWith("GET /files/missing.css -> 404"),
                        line -> assertThat(line).startsWith("GET /healthy -> 200"));
        assertThat(RequestLog.quiet("/actuator/health", 500)).isTrue();
    }

    @Test
    void aLongOrForgedPathIsCappedAndCannotStartASecondLine() throws Exception {
        filter.doFilter(request("GET", "/api/v1/plans\r\nERROR forged\tline"), new MockHttpServletResponse(), (req, res) -> { });
        filter.doFilter(request("GET", "/" + "a".repeat(300)), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(requests.list.get(0).getFormattedMessage()).startsWith("GET /api/v1/plans__ERROR forged_line -> 200")
                .doesNotContain("\r", "\n", "\t");
        assertThat(value(requests.list.get(1), "url.path")).asString().hasSize(201).endsWith("a…");

        var noUri = request("GET", "/");
        noUri.setRequestURI(null);
        assertThat(RequestLog.path(noUri)).isEmpty();
    }

    @Test
    void aMethodOutsideTheKnownSetIsLoggedAsOther() throws Exception {
        filter.doFilter(request("PROPFIND\nX", "/api/v1/plans"), new MockHttpServletResponse(), (req, res) -> { });
        filter.doFilter(request("OPTIONS", "/api/v1/plans"), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(requests.list).extracting(event -> value(event, "http.method")).containsExactly("OTHER", "OPTIONS");
    }

    @Test
    void anEscapedFailureIsOneErrorWithItsStackAndTheCallerGetsOnlyTheReference() throws Exception {
        var request = request("PUT", "/api/v1/plans/current");
        request.addHeader("X-Request-Id", GATEWAY_ID);
        var response = new MockHttpServletResponse();
        response.setHeader("X-Leaky", "partial");
        FilterChain chain = (req, res) -> {
            res.getWriter().write("half an answer");
            throw new IllegalStateException("database password is hunter2");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(GATEWAY_ID);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Leaky")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"UNEXPECTED_ERROR\",\"message\":\"Something went "
                + "wrong. Quote reference " + GATEWAY_ID + " when you report it.\",\"reference\":\"" + GATEWAY_ID + "\"}");
        assertThat(failures.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Unexpected failure reference=" + GATEWAY_ID
                    + " PUT /api/v1/plans/current");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", GATEWAY_ID);
        });
        assertThat(requests.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(value(event, "http.status")).isEqualTo(500);
        });
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void checkedFailuresAreHandledTheSameWay() throws Exception {
        for (Exception failure : new Exception[] {new IOException("disk"), new ServletException("servlet")}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("GET", "/api/v1/plans"), response, (req, res) -> {
                if (failure instanceof IOException io) throw io;
                throw (ServletException) failure;
            });
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).contains("\"code\":\"UNEXPECTED_ERROR\"");
        }
        assertThat(failures.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR, Level.ERROR);
    }

    @Test
    void aFailureAfterTheAnswerLeftIsOneWarningAndTheAnswerIsLeftAlone() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/content/lesson"), response, (req, res) -> {
            res.getWriter().write("partial lesson");
            res.flushBuffer();
            throw new IllegalStateException("token=secret", new IOException("Broken pipe"));
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("partial lesson");
        assertThat(failures.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "Request ended early: GET /api/v1/content/lesson IllegalStateException < IOException");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(requests.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.INFO);
    }

    @Test
    void anEarlyEndNamesAtMostFiveCauseTypesAndNoMessages() {
        Throwable failure = new IOException("password=1");
        for (int i = 0; i < 6; i++) failure = new IllegalStateException("secret " + i, failure);

        assertThat(RequestLog.types(failure)).isEqualTo(String.join(" < ", java.util.Collections.nCopies(5,
                "IllegalStateException"))).doesNotContain("secret", "password");
    }

    @Test
    void theFilterWrapsEveryOtherFilter() {
        assertThat(RequestLog.class.getAnnotation(Order.class).value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE)
                .isLessThan(DomainStorageFailureFilter.class.getAnnotation(Order.class).value())
                .isLessThan(-250);
    }

    private static Object value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> pair.key.equals(key)).map(pair -> pair.value)
                .findFirst().orElseThrow();
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private static Logger logger(String name) {
        return (Logger) LoggerFactory.getLogger(name);
    }
}
