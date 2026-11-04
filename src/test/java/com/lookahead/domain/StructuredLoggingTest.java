package com.lookahead.domain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DLV-969 (owner, 2026-11-04): in the DEV profile every log line is one ECS JSON record, so CloudWatch shows one
 * event per line with its request ID, the summary's fields, and any stack trace inside the same record. The deploy
 * pipeline's CloudWatch scan for the term {@code ERROR} must still find error records.
 */
class StructuredLoggingTest {
    private static final String REQUEST_ID = "6f1c2b9e-4d3a-4e8b-9c7d-0a1b2c3d4e5f";
    private static final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aSummaryLineIsOneJsonRecordWithTheRequestIdAndItsFields() throws Exception {
        var dev = devEncoder();
        var event = event(dev.context(), "lookahead.http", Level.INFO, "GET /api/v1/plans -> 200 in 3 ms", null);
        event.setKeyValuePairs(List.of(new KeyValuePair("http.method", "GET"), new KeyValuePair("url.path", "/api/v1/plans"),
                new KeyValuePair("http.status", 200), new KeyValuePair("durationMs", 3L)));

        JsonNode record = oneRecord(dev.encoder().encode(event));

        assertThat(record.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(record.at("/log/logger").asString()).isEqualTo("lookahead.http");
        assertThat(record.at("/message").asString()).isEqualTo("GET /api/v1/plans -> 200 in 3 ms");
        assertThat(record.at("/requestId").asString()).isEqualTo(REQUEST_ID);
        assertThat(record.at("/service/name").asString()).isEqualTo("domain");
        assertThat(record.at("/service/environment").asString()).isEqualTo("dev");
        // ECS nests dotted key-value names: http.method becomes {"http":{"method":...}}.
        assertThat(record.at("/http/method").asString()).isEqualTo("GET");
        assertThat(record.at("/http/status").asInt()).isEqualTo(200);
        assertThat(record.at("/url/path").asString()).isEqualTo("/api/v1/plans");
        assertThat(record.at("/durationMs").asLong()).isEqualTo(3L);
        assertThat(record.at("/ecs/version").asString()).isEqualTo("8.11");
    }

    @Test
    void anErrorIsOneRecordWithItsStackInsideAndTheErrorTermTheDeployScanLooksFor() throws Exception {
        var dev = devEncoder();
        var failure = new IllegalStateException("outer", new IOException("inner"));
        var event = event(dev.context(), RequestLog.class.getName(), Level.ERROR,
                "Unexpected failure reference=" + REQUEST_ID + " GET /api/v1/plans", failure);

        byte[] encoded = dev.encoder().encode(event);
        JsonNode record = oneRecord(encoded);

        assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("\"ERROR\"");
        assertThat(record.at("/log/level").asString()).isEqualTo("ERROR");
        assertThat(record.at("/requestId").asString()).isEqualTo(REQUEST_ID);
        assertThat(record.at("/error/type").asString()).isEqualTo(IllegalStateException.class.getName());
        assertThat(record.at("/error/message").asString()).isEqualTo("outer");
        String stack = record.at("/error/stack_trace").asString();
        // root: first puts the cause on top; the wrapper follows, still inside this one record.
        assertThat(stack).startsWith("java.io.IOException: inner")
                .contains("Wrapped by: java.lang.IllegalStateException: outer")
                .contains("\tat " + StructuredLoggingTest.class.getName());
        assertThat(stack.length()).isLessThanOrEqualTo(20_000);
    }

    @Test
    void theStackTraceIsCappedAtTheConfiguredLength() throws Exception {
        var dev = devEncoder();
        Throwable failure = new IllegalStateException("bottom");
        for (int i = 0; i < 400; i++) failure = new IllegalStateException("level " + i + " " + "x".repeat(100), failure);

        JsonNode record = oneRecord(dev.encoder().encode(event(dev.context(), "any", Level.ERROR, "deep", failure)));

        assertThat(record.at("/error/stack_trace").asString().length()).isLessThanOrEqualTo(20_000);
    }

    @Test
    void onlyTheDevProfileSwitchesToJsonSoLocalRunsStayReadable() throws Exception {
        var local = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("local", new ClassPathResource("application.yml")))
            local.getPropertySources().addLast(source);

        assertThat(local.getProperty("logging.structured.format.console")).isNull();
        assertThat(devEnvironment().getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }

    /**
     * DLV-969 (2026-11-04): a key that collides with a member ECS writes itself (service.*, log.*, process.*, ecs.*, or
     * error.* next to an exception) makes the encoder fail and the appender drop the whole line without a trace. The
     * new lines are rendered here through the real DEV encoder, so a colliding key cannot come back unnoticed.
     */
    @Test
    void theNewLifecycleHealthAndRefusalLinesRenderAndACollidingKeyIsCaught() throws Exception {
        var dev = devEncoder();
        var started = event(dev.context(), "lookahead.lifecycle", Level.INFO, "Started: version 2026.11.04.19.00", null);
        started.setKeyValuePairs(List.of(new KeyValuePair("event.action", "startup"), new KeyValuePair("event.outcome", "success"),
                new KeyValuePair("domain.version", "2026.11.04.19.00"), new KeyValuePair("release.tag", "v2026.11.04.19.00"),
                new KeyValuePair("source.revision", "abc1234"), new KeyValuePair("profiles", "accounts,resource,lower-cost-dev"),
                new KeyValuePair("availability.readiness", "ACCEPTING_TRAFFIC")));
        assertThat(oneRecord(dev.encoder().encode(started)).at("/domain/version").asString()).isEqualTo("2026.11.04.19.00");

        var down = event(dev.context(), "lookahead.health", Level.WARN, "Readiness DOWN: the database is missing migration(s) 19, 20", null);
        down.setKeyValuePairs(List.of(new KeyValuePair("event.action", "readiness"), new KeyValuePair("event.outcome", "failure"),
                new KeyValuePair("reason", "MIGRATIONS_MISSING"), new KeyValuePair("health.component", "database")));
        assertThat(oneRecord(dev.encoder().encode(down)).at("/reason").asString()).isEqualTo("MIGRATIONS_MISSING");

        var refused = event(dev.context(), "lookahead.startup", Level.WARN, "Startup refused: LOOKAHEAD_DATABASE_URL is not valid", null);
        refused.setKeyValuePairs(List.of(new KeyValuePair("event.action", "startup"), new KeyValuePair("reason", "JDBC_URL_INVALID"),
                new KeyValuePair("error.type", "IllegalArgumentException")));
        assertThat(oneRecord(dev.encoder().encode(refused)).at("/error/type").asString()).isEqualTo("IllegalArgumentException");

        var colliding = event(dev.context(), "lookahead.lifecycle", Level.INFO, "Started", null);
        colliding.setKeyValuePairs(List.of(new KeyValuePair("service.version", "x")));
        assertThatThrownBy(() -> dev.encoder().encode(colliding)).isInstanceOf(RuntimeException.class);
    }

    private record Dev(LoggerContext context, StructuredLogEncoder encoder) { }

    private static Dev devEncoder() throws IOException {
        var environment = devEnvironment();
        var context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        var encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(environment.getProperty("logging.structured.format.console"));
        encoder.start();
        return new Dev(context, encoder);
    }

    private static StandardEnvironment devEnvironment() throws IOException {
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("dev", new ClassPathResource("application-lower-cost-dev.yml")))
            environment.getPropertySources().addLast(source);
        return environment;
    }

    private static LoggingEvent event(LoggerContext context, String logger, Level level, String message, Throwable failure) {
        var event = new LoggingEvent(StructuredLoggingTest.class.getName(), context.getLogger(logger), level, message,
                failure, null);
        event.setMDCPropertyMap(Map.of("requestId", REQUEST_ID));
        return event;
    }

    /** Exactly one line (the record, then its line break) holding one JSON object. */
    private static JsonNode oneRecord(byte[] encoded) {
        String text = new String(encoded, StandardCharsets.UTF_8);
        assertThat(text).endsWith("\n");
        String line = text.substring(0, text.length() - 1);
        assertThat(line).doesNotContain("\n", "\r");
        JsonNode record = json.readTree(line);
        assertThat(record.isObject()).isTrue();
        return record;
    }
}
