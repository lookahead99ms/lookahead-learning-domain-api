package com.lookahead.domain;

import ch.qos.logback.classic.Level;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** DLV-969 (owner, 2026-11-04): the start, availability and stop of a task are explained in the log. */
class LifecycleLogTest {
    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> build(BuildProperties properties) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
    }

    private static BuildProperties properties() {
        var values = new Properties();
        values.setProperty("version", "2026.11.4.19");
        values.setProperty("releaseTag", "v2026.11.04.19.00");
        values.setProperty("sourceRevision", "0123456789abcdef0123456789abcdef01234567");
        return new BuildProperties(values);
    }

    @Test void everyAvailabilityChangeIsLoggedAndTheFirstAcceptingTrafficWritesOneStartedSummary() {
        try (var context = new GenericApplicationContext(); var log = LogCapture.start("lookahead.lifecycle")) {
            var environment = new MockEnvironment().withProperty("app.deployment-environment", "dev").withProperty("spring.datasource.password", "hunter2-secret-value");
            environment.setActiveProfiles("accounts", "resource");
            var lifecycle = new LifecycleLog(context, environment, build(properties()));
            lifecycle.availability(new AvailabilityChangeEvent<>(context, LivenessState.CORRECT));
            lifecycle.availability(new AvailabilityChangeEvent<>(context, ReadinessState.ACCEPTING_TRAFFIC));
            lifecycle.availability(new AvailabilityChangeEvent<>(context, ReadinessState.REFUSING_TRAFFIC));
            lifecycle.availability(new AvailabilityChangeEvent<>(context, ReadinessState.ACCEPTING_TRAFFIC));
            lifecycle.availability(new AvailabilityChangeEvent<>(context, LivenessState.BROKEN));
            assertThat(log.messages()).containsExactly(
                    "Liveness CORRECT: the app is running",
                    "Readiness state ACCEPTING_TRAFFIC: the app accepts traffic once its readiness checks are UP (lookahead.health)",
                    "Started: version 2026.11.4.19 (release tag v2026.11.04.19.00, revision 0123456789abcdef0123456789abcdef01234567),"
                            + " environment dev, profiles [accounts,resource], readiness ACCEPTING_TRAFFIC",
                    "Readiness state REFUSING_TRAFFIC: the app takes no new traffic (it is starting or stopping)",
                    "Readiness state ACCEPTING_TRAFFIC: the app accepts traffic once its readiness checks are UP (lookahead.health)",
                    "Liveness BROKEN: the app reports itself broken and should be restarted");
            assertThat(log.lines()).extracting(line -> line.getLevel()).containsExactly(Level.INFO, Level.INFO, Level.INFO, Level.INFO, Level.INFO, Level.WARN);
            assertThat(LogCapture.keys(log.only("startup"))).containsEntry("domain.version", "2026.11.4.19").containsEntry("release.tag", "v2026.11.04.19.00")
                    .containsEntry("profiles", "accounts,resource").containsEntry("availability.readiness", "ACCEPTING_TRAFFIC");
            assertThat(LogCapture.keys(log.lines().getLast())).containsEntry("reason", "LIVENESS_BROKEN");
            log.containsNone("hunter2");
        }
    }

    @Test void withoutBuildInformationTheConfiguredVersionIsUsed() {
        try (var context = new GenericApplicationContext(); var log = LogCapture.start("lookahead.lifecycle")) {
            var lifecycle = new LifecycleLog(context, new MockEnvironment().withProperty("app.version", "1.2.3"), build(null));
            lifecycle.availability(new AvailabilityChangeEvent<>(context, ReadinessState.ACCEPTING_TRAFFIC));
            assertThat(log.only("startup").getFormattedMessage())
                    .isEqualTo("Started: version 1.2.3 (release tag unknown, revision unknown), environment unknown, profiles [], readiness ACCEPTING_TRAFFIC");
        }
    }

    @Test void aStopSaysItWasRequestedWithTheUptimeTheReadinessBeforeTheStopAndTheLastChecks() {
        HealthReport.forget();
        try (var context = new GenericApplicationContext(); var other = new GenericApplicationContext();
             var log = LogCapture.start("lookahead.lifecycle", "lookahead.health")) {
            var lifecycle = new LifecycleLog(context, new MockEnvironment(), build(null));
            lifecycle.closed(new ContextClosedEvent(context));
            assertThat(log.only("shutdown").getFormattedMessage()).matches("Stopping: shutdown requested \\(SIGTERM from the platform, e\\.g\\. ECS stop or failed"
                    + " health check\\) after \\d+ s; readiness before the stop unknown; last readiness checks: no readiness check has run yet");
            var clock = java.time.Clock.fixed(Instant.parse("2026-11-04T19:00:00Z"), java.time.ZoneOffset.UTC);
            new HealthReport("database", "fine", clock).report(new HealthReport.Diagnosis("MIGRATIONS_MISSING", "migration 21 is missing"));
            new HealthReport("content publication", "fine", clock).report(null);
            lifecycle.availability(new AvailabilityChangeEvent<>(context, ReadinessState.ACCEPTING_TRAFFIC));
            lifecycle.availability(new AvailabilityChangeEvent<>(context, ReadinessState.REFUSING_TRAFFIC));
            lifecycle.closed(new ContextClosedEvent(other));
            lifecycle.closed(new ContextClosedEvent(context));
            var stops = log.lines("shutdown");
            assertThat(stops).hasSize(2);
            var stop = stops.getLast();
            assertThat(stop.getLevel()).isEqualTo(Level.INFO);
            assertThat(stop.getFormattedMessage()).endsWith("readiness before the stop ACCEPTING_TRAFFIC;"
                    + " last readiness checks: content publication UP, database DOWN MIGRATIONS_MISSING");
            assertThat(LogCapture.keys(stop)).containsKey("uptimeSeconds").containsEntry("availability.readiness", "ACCEPTING_TRAFFIC");
        } finally { HealthReport.forget(); }
    }
}
