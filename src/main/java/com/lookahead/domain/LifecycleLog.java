package com.lookahead.domain;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * DLV-969 (owner, 2026-11-04): the lines on {@code lookahead.lifecycle} that explain a task's life in CloudWatch.
 * <ul>
 *   <li>every liveness and readiness change Spring Boot publishes (INFO; a BROKEN liveness is WARN);</li>
 *   <li>one "Started" line when the app first accepts traffic: version, release tag, source revision, profiles;</li>
 *   <li>one "Stopping" line when the context closes, which is what the platform's SIGTERM does (an ECS stop or a
 *   failed container health check, exit code 143), with the uptime, the readiness before the stop and the last state of
 *   each readiness check ({@link HealthReport}).</li>
 * </ul>
 */
@Component
public class LifecycleLog {
    static final Logger LOG = LoggerFactory.getLogger("lookahead.lifecycle");
    private final ApplicationContext context;
    private final Environment environment;
    private final ObjectProvider<BuildProperties> build;
    private volatile ReadinessState readiness;
    private volatile ReadinessState beforeRefusing;
    private volatile boolean started;

    public LifecycleLog(ApplicationContext context, Environment environment, ObjectProvider<BuildProperties> build) {
        this.context = context; this.environment = environment; this.build = build;
    }

    @EventListener
    public void availability(AvailabilityChangeEvent<?> event) {
        if (event.getState() instanceof LivenessState liveness) {
            var line = liveness == LivenessState.BROKEN ? Events.failed(LOG, "availability", "LIVENESS_BROKEN") : Events.success(LOG, "availability");
            line.addKeyValue("availability.liveness", liveness.name()).log("Liveness {}: {}", liveness,
                    liveness == LivenessState.BROKEN ? "the app reports itself broken and should be restarted" : "the app is running");
        } else if (event.getState() instanceof ReadinessState state) {
            if (state == ReadinessState.REFUSING_TRAFFIC) beforeRefusing = readiness;
            readiness = state;
            Events.success(LOG, "availability").addKeyValue("availability.readiness", state.name()).log("Readiness state {}: {}", state,
                    state == ReadinessState.ACCEPTING_TRAFFIC ? "the app accepts traffic once its readiness checks are UP (lookahead.health)"
                            : "the app takes no new traffic (it is starting or stopping)");
            if (state == ReadinessState.ACCEPTING_TRAFFIC && !started) {
                started = true;
                startedLine(state);
            }
        }
    }

    private void startedLine(ReadinessState state) {
        BuildProperties properties = build.getIfAvailable();
        String version = properties != null && properties.getVersion() != null ? properties.getVersion() : environment.getProperty("app.version", "unknown");
        String tag = known(properties == null ? null : properties.get("releaseTag"));
        String revision = known(properties == null ? null : properties.get("sourceRevision"));
        String profiles = String.join(",", Arrays.asList(environment.getActiveProfiles()));
        Events.success(LOG, "startup").addKeyValue("domain.version", version).addKeyValue("release.tag", tag)
                .addKeyValue("source.revision", revision).addKeyValue("profiles", profiles).addKeyValue("availability.readiness", state.name())
                .log("Started: version {} (release tag {}, revision {}), environment {}, profiles [{}], readiness {}", version,
                        tag, revision,
                        environment.getProperty("app.deployment-environment", "unknown"), profiles, state);
    }

    private static String known(String value) { return value == null || value.isBlank() ? "unknown" : value; }

    @EventListener
    public void closed(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) return;
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        ReadinessState before = readiness == ReadinessState.REFUSING_TRAFFIC && beforeRefusing != null ? beforeRefusing : readiness;
        String state = before == null ? "unknown" : before.name();
        String checks = HealthReport.last();
        Events.success(LOG, "shutdown").addKeyValue("uptimeSeconds", uptime).addKeyValue("availability.readiness", state)
                .addKeyValue("health.last", checks)
                .log("Stopping: shutdown requested (SIGTERM from the platform, e.g. ECS stop or failed health check) after {} s;"
                        + " readiness before the stop {}; last readiness checks: {}", uptime, state, checks);
    }
}
