package com.lookahead.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DomainDatabaseHealthIndicatorTest {
    @Test void readinessRequiresAnExplicitDatabaseConfirmation() {
        var jdbc = mock(JdbcTemplate.class);
        var indicator = new DomainDatabaseHealthIndicator(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true, false, null);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test void inaccessibleOrIncompleteSchemaFailsWithoutDatabaseDetails() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenThrow(
                new DataAccessResourceFailureException("private database endpoint and credentials"));
        var health = new DomainDatabaseHealthIndicator(jdbc).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void cloudReadinessRequiresCloudSchemaAndRestrictedIdentityPrivileges(String mode) {
        var jdbc=mock(JdbcTemplate.class);
        var cloud=new DomainDatabaseHealthIndicator(jdbc,new org.springframework.mock.env.MockEnvironment().withProperty("app.deployment-environment",mode));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true,false,true,true);
        assertThat(cloud.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(cloud.health().getStatus()).isEqualTo(Status.UP);
        verify(jdbc,times(4)).queryForObject(anyString(),eq(Boolean.class));
    }

    /** DLV-969 (owner, 2026-11-04): a DOWN readiness says why in the log, never in the answer. */
    static final class Ticking extends java.time.Clock {
        java.time.Instant now = java.time.Instant.parse("2026-11-04T19:04:55Z");
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listen() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("lookahead.health");
        var lines = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        lines.start();
        logger.addAppender(lines);
        return lines;
    }

    private static void quiet(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("lookahead.health")).detachAppender(lines);
    }

    @Test void aReleaseAheadOfItsMigrationsSaysWhichOnesAreMissingOnceAMinuteThenSaysUpOnceFixed() {
        var lines = listen();
        try {
            var jdbc = mock(JdbcTemplate.class);
            var clock = new Ticking();
            var indicator = new DomainDatabaseHealthIndicator(jdbc, true, clock);
            when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true, false, true, false, true, false, true, true, true, true);
            when(jdbc.queryForList(contains("flyway_schema_history"), eq(String.class))).thenReturn(java.util.List.of("19", "20"));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(indicator.health().getDetails()).isEmpty();
            clock.now = clock.now.plusSeconds(61);
            indicator.health();
            assertThat(lines.list).hasSize(2).allSatisfy(line -> {
                assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(line.getFormattedMessage()).isEqualTo("Readiness DOWN: the database is missing migration(s) 19, 20; run Flyway Utils"
                        + " migrate and verify before deploying this release; the container health check fails until this is fixed");
                assertThat(line.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                        .contains("event.action=readiness", "event.outcome=failure", "reason=MIGRATIONS_MISSING");
            });
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(lines.list).hasSize(3);
            assertThat(lines.list.getLast().getFormattedMessage()).isEqualTo("Readiness UP: the database checks pass; this task can take traffic");
            assertThat(lines.list.toString()).doesNotContain("ERROR");
        } finally { quiet(lines); }
    }

    @Test void missingTablesUnreachableDatabasesAndFailedDiagnosesAreNamedWithoutConnectionDetails() {
        var lines = listen();
        try {
            var jdbc = mock(JdbcTemplate.class);
            var indicator = new DomainDatabaseHealthIndicator(jdbc, false, new Ticking());
            when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
            when(jdbc.queryForList(contains("to_regclass"), eq(String.class))).thenReturn(java.util.List.of("public.plans"));
            indicator.health();
            when(jdbc.queryForList(contains("to_regclass"), eq(String.class))).thenReturn(java.util.List.of());
            indicator.health();
            when(jdbc.queryForList(contains("flyway_schema_history"), eq(String.class)))
                    .thenThrow(new DataAccessResourceFailureException("host db.internal user secret"));
            indicator.health();
            when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenThrow(new DataAccessResourceFailureException(
                    "private endpoint password=hunter2", new java.sql.SQLException("connection refused to db.internal", "08001")));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(lines.list).extracting(line -> line.getFormattedMessage().replaceAll("; the container health check fails until this is fixed$", ""))
                    .containsExactly(
                            "Readiness DOWN: the database is missing table(s) public.plans",
                            "Readiness DOWN: every migration and table is there, but the runtime role, its ownership or its table privileges"
                                    + " differ from what this release checks for (DomainDatabaseHealthIndicator)",
                            "Readiness DOWN: the check failed and its diagnosis could not run: DataAccessResourceFailureException",
                            "Readiness DOWN: the database check could not run: DataAccessResourceFailureException < SQLException (SQL state 08001)");
            assertThat(lines.list.toString()).doesNotContain("hunter2", "db.internal", "secret", "password");
        } finally { quiet(lines); }
    }
}
