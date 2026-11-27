package com.lookahead.domain.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import com.lookahead.domain.security.filter.RequestLogFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain logs (owner, 2026-11-17): every line is "{@code <UTC timestamp with millis> <LEVEL padded to 5> <sentence>}",
 * then two spaces and "{@code [request <first 8 chars of the request id>]}" only while a request is served; a stack
 * trace follows only an ERROR line of the unexpected-failure handlers. The pattern is set once in application.yml
 * and this test renders that very pattern through Logback, so the exact line shape is proven, not described.
 */
class EventLogPlainLoggingTest {
    private static final String REQUEST_ID = "5e17c0aa-4d3a-4e8b-9c7d-0a1b2c3d4e5f";
    private final LoggerContext context = new LoggerContext();
    private PatternLayout layout;

    @BeforeEach
    void layoutFromTheRealPattern() throws IOException {
        layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(consolePattern());
        layout.start();
        assertThat(context.getStatusManager().getCopyOfStatusList()).noneMatch(status -> status.getLevel() >= Status.WARN);
    }

    @AfterEach
    void clean() { MDC.clear(); }

    @Test
    void anInfoRequestSummaryEndsWithTheRequestBracket() {
        var event = event("lookahead.http", Level.INFO, "POST /api/v1/plans -> 200 in 371 ms", REQUEST_ID, null);

        assertThat(layout.doLayout(event)).isEqualTo("2026-11-17T20:41:18.144Z INFO  POST /api/v1/plans -> 200 in 371 ms  [request 5e17c0aa]\n");
    }

    @Test
    void aWarnRefusalOutsideARequestEndsAfterTheSentenceWithNoBracketAndNoTrailingSpaces() {
        var event = event("lookahead.limit", Level.WARN, "Free plan limit hit for account faaac829-0000-4000-8000-000000000001: at most 2 active free plans at once", null, null);

        String line = layout.doLayout(event);

        assertThat(line).isEqualTo("2026-11-17T20:41:18.144Z WARN  Free plan limit hit for account faaac829-0000-4000-8000-000000000001: at most 2 active free plans at once\n");
        assertThat(line).doesNotContain("[request", " \n");
    }

    @Test
    void anErrorLineIsFollowedByItsStackTraceAndNothingElse() {
        var failure = new IllegalStateException("Cannot invoke \"java.util.List.size()\" because \"sessions\" is null");
        var event = event("lookahead.failure", Level.ERROR, "POST /api/v1/plans/3/versions failed after 12 ms: unexpected IllegalStateException in"
                + " PlanVersionService.publish (PlanVersionService.java:148): Cannot invoke \"java.util.List.size()\" because \"sessions\" is null;"
                + " answered 500 UNEXPECTED_ERROR; reference " + REQUEST_ID, REQUEST_ID, failure);

        String text = layout.doLayout(event);
        String[] lines = text.split("\n");

        assertThat(lines[0]).isEqualTo("2026-11-17T20:41:18.144Z ERROR POST /api/v1/plans/3/versions failed after 12 ms: unexpected IllegalStateException in"
                + " PlanVersionService.publish (PlanVersionService.java:148): Cannot invoke \"java.util.List.size()\" because \"sessions\" is null;"
                + " answered 500 UNEXPECTED_ERROR; reference 5e17c0aa-4d3a-4e8b-9c7d-0a1b2c3d4e5f  [request 5e17c0aa]");
        assertThat(lines[1]).isEqualTo("java.lang.IllegalStateException: Cannot invoke \"java.util.List.size()\" because \"sessions\" is null");
        assertThat(lines[2]).startsWith("\tat " + EventLogPlainLoggingTest.class.getName() + ".anErrorLineIsFollowedByItsStackTraceAndNothingElse(EventLogPlainLoggingTest.java:");
        assertThat(text).endsWith("\n");
        // The level word sits right after the timestamp (the deploy's log scan searches for it).
        assertThat(lines[0].indexOf("ERROR")).isEqualTo("2026-11-17T20:41:18.144Z ".length());
    }

    @Test
    void aReadinessDownLineRendersAsTheStandardShows() {
        var event = event("lookahead.health", Level.ERROR, "Readiness DOWN for dynamodb: the DynamoDB data is at version 4 but this release requires 5"
                + " (DATA_VERSION_BEHIND); run 07 · Migrate DEV data with this release, then deploy again; the container health check fails until then", null, null);

        assertThat(layout.doLayout(event)).isEqualTo("2026-11-17T20:41:18.144Z ERROR Readiness DOWN for dynamodb: the DynamoDB data is at version 4 but"
                + " this release requires 5 (DATA_VERSION_BEHIND); run 07 · Migrate DEV data with this release, then deploy again; the container health check fails until then\n");
    }

    /** The real WARN and INFO templates, rendered end to end: none of them carries the word ERROR, with or without a request. */
    @Test
    void realWarnAndInfoLinesNeverContainTheWordError() throws Exception {
        // MDC is read lazily by Logback: capture it at log time, as a real appender does, so the bracket reflects the request.
        var captured = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        captured.start();
        var names = List.of("lookahead.http", "lookahead.event", "lookahead.limit", "lookahead.upstream", "lookahead.lifecycle", "lookahead.failure");
        for (String name : names) ((Logger) LoggerFactory.getLogger(name)).addAppender(captured);
        try {
            var filter = new RequestLogFilter(new MockEnvironment());
            filter.doFilter(request("GET", "/api/v1/plans", REQUEST_ID), new MockHttpServletResponse(),
                    (req, res) -> ((HttpServletResponse) res).setStatus(503));
            filter.doFilter(request("POST", "/api/v1/admin/test-accounts/x/delete", null), new MockHttpServletResponse(), (req, res) -> {
                ((HttpServletResponse) res).setStatus(403);
                res.getWriter().write("{\"status\":403,\"code\":\"ROSTER_ACCOUNT_PERMANENT\",\"message\":\"Roster accounts are never deleted.\"}");
            });
            filter.doFilter(request("GET", "/api/v1/content/lesson", REQUEST_ID), new MockHttpServletResponse(), (req, res) -> {
                res.getWriter().write("partial"); res.flushBuffer();
                throw new IllegalStateException("token=secret", new IOException("Broken pipe"));
            });
            LogThrottle.forgetAll();
            EventLog.refused(EventLog.EVENT, "account.downgrade", "OWNER_REQUIRED").log("Account {} downgrade refused for {}: OWNER_REQUIRED", REQUEST_ID, EventLog.actor("Account", REQUEST_ID));
            EventLog.limit("plan.create", "FREE_PLAN_LIMIT", "active-free-plans", 2, "at once").log("Free plan limit hit for account {}: at most 2 active free plans at once", REQUEST_ID);
            EventLog.upstreamFailed("Cognito", "GetUser", EventLog.started(), new IllegalStateException("synthetic"));
            EventLog.upstreamFailed("S3", "GetObject", "bucket/reviews/x.json", EventLog.started(), null, 500, "InternalError");
            EventLog.upstreamRefused("Identity", "VerifyToken", EventLog.started(), "TOKEN_INACTIVE");
            EventLog.retry(EventLog.EVENT, "delivery.write", "WRITE_CONFLICT", 1, 3).log("The delivery plan was changed by another writer at the same moment: attempt {} of {}; reading it again", 1, 3);
            EventLog.success(EventLog.EVENT, "account.downgrade").log("{} downgraded account {} to a free learner: roles, course grants and memberships removed", EventLog.actor("Owner", REQUEST_ID), REQUEST_ID);
        } finally {
            for (String name : names) ((Logger) LoggerFactory.getLogger(name)).detachAppender(captured);
        }
        assertThat(captured.list).hasSizeGreaterThanOrEqualTo(10);
        for (var event : captured.list) {
            String rendered = layout.doLayout(event);
            assertThat(event.getLevel()).as(rendered).isIn(Level.INFO, Level.WARN);
            assertThat(rendered).as("WARN and INFO sentences never carry the word ERROR").doesNotContain("ERROR").endsWith("\n").doesNotContain(" \n");
            assertThat(rendered).matches("(?s)\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z (INFO |WARN ) .*");
            String requestId = event.getMDCPropertyMap().get("requestId");
            if (requestId != null) assertThat(rendered).endsWith("  [request " + requestId.substring(0, 8) + "]\n");
            else assertThat(rendered).doesNotContain("[request");
        }
        assertThat(captured.list.stream().map(e -> layout.doLayout(e))).anyMatch(line -> line.contains("Owner 5e17c0aa downgraded account"));
        assertThat(captured.list.stream().map(e -> layout.doLayout(e))).anyMatch(line -> line.contains("GET /api/v1/plans -> 503 HTTP_503: Unavailable"));
    }

    @Test
    void thePatternIsSetOnceForEveryProfileAndTheDevProfileHasNoStructuredFormat() throws IOException {
        var dev = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("dev", new ClassPathResource("application-lower-cost-dev.yml")))
            dev.getPropertySources().addLast(source);
        assertThat(dev.getProperty("logging.structured.format.console")).isNull();
        assertThat(dev.getProperty("logging.pattern.console")).isNull();
        assertThat(consolePattern()).startsWith("%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z', UTC} %-5level %msg").endsWith("%n%ex").contains("%.-8X{requestId}");
    }

    /** A sentence with a line break could forge a second line; the templates strip them, and the pattern keeps a stack trace below the line. */
    @Test
    void theRequestBracketIsTheFirstEightCharactersOnly() {
        var event = event("lookahead.event", Level.INFO, "Sign-in admitted", "0d3f8a2b-ffff-4000-8000-000000000000", null);
        assertThat(layout.doLayout(event)).endsWith("Sign-in admitted  [request 0d3f8a2b]\n");
    }

    /**
     * Spring Boot itself, not only Logback: the logging system is initialised the way the app does it, from the
     * Environment application.yml produces, and the console appender it builds renders the exact line.
     */
    @Test
    void springBootBuildsTheConsoleAppenderFromTheApplicationYmlPattern() throws IOException {
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml")))
            environment.getPropertySources().addLast(source);
        var system = new LogbackLoggingSystem(getClass().getClassLoader());
        system.cleanUp(); // another test in this JVM may have let Boot initialise logging already; start afresh
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(environment), null, null);

        var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var console = root.getAppender("CONSOLE");
        assertThat(console).as("Boot's default console appender").isInstanceOf(ConsoleAppender.class);
        var encoder = (PatternLayoutEncoder) ((ConsoleAppender<?>) console).getEncoder();
        assertThat(encoder.getPattern()).isEqualTo(consolePattern());
        var event = event("lookahead.http", Level.INFO, "POST /api/v1/plans -> 200 in 371 ms", REQUEST_ID, null);
        assertThat(new String(encoder.encode(event), StandardCharsets.UTF_8))
                .isEqualTo("2026-11-17T20:41:18.144Z INFO  POST /api/v1/plans -> 200 in 371 ms  [request 5e17c0aa]\n");
        var outside = event("lookahead.lifecycle", Level.INFO, "Started: version 2026.11.17.20.00 (release tag v2026.11.17.20.00, revision abc1234), environment dev, profiles [accounts,resource,lower-cost-dev], readiness ACCEPTING_TRAFFIC", null, null);
        assertThat(new String(encoder.encode(outside), StandardCharsets.UTF_8))
                .isEqualTo("2026-11-17T20:41:18.144Z INFO  Started: version 2026.11.17.20.00 (release tag v2026.11.17.20.00, revision abc1234), environment dev, profiles [accounts,resource,lower-cost-dev], readiness ACCEPTING_TRAFFIC\n");
    }

    static String consolePattern() throws IOException {
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml")))
            environment.getPropertySources().addLast(source);
        String pattern = environment.getProperty("logging.pattern.console");
        assertThat(pattern).as("logging.pattern.console in application.yml").isNotBlank();
        return pattern;
    }

    private LoggingEvent event(String logger, Level level, String message, String requestId, Throwable failure) {
        var event = new LoggingEvent(EventLogPlainLoggingTest.class.getName(), context.getLogger(logger), level, message, failure, null);
        event.setInstant(Instant.parse("2026-11-17T20:41:18.144Z"));
        event.setMDCPropertyMap(requestId == null ? Map.of() : Map.of("requestId", requestId));
        return event;
    }

    private static MockHttpServletRequest request(String method, String path, String requestId) {
        var request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (requestId != null) request.addHeader("X-Request-Id", requestId);
        return request;
    }
}
