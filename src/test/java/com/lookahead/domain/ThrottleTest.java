package com.lookahead.domain;

import ch.qos.logback.classic.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import static org.assertj.core.api.Assertions.assertThat;

/** DLV-969 (owner, 2026-11-04): hot-path failure lines are written on change and at most once a minute while they last. */
class ThrottleTest {
    static final class Ticking extends Clock {
        Instant now = Instant.parse("2026-11-04T19:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test void aKeyIsDueWhenNewAndAgainAfterAMinuteAndManyKeysNeverGrowTheMap() {
        var clock = new Ticking();
        var throttle = new Throttle(clock);
        assertThat(throttle.due("A")).isTrue();
        assertThat(throttle.due("A")).isFalse();
        assertThat(throttle.due("B")).isTrue();
        clock.now = clock.now.plusSeconds(59);
        assertThat(throttle.due("A")).isFalse();
        clock.now = clock.now.plusSeconds(1);
        assertThat(throttle.due("A")).isTrue();
        for (int key = 0; key < Throttle.KEYS + 5; key++) throttle.due("key-" + key);
        assertThat(throttle.due("A")).isTrue();
        Throttle.forgetAll();
        assertThat(throttle.due("A")).isTrue();
    }

    @Test void theLastStateOfEachReadinessCheckIsKept() {
        HealthReport.forget();
        try {
            assertThat(HealthReport.last()).isEqualTo("no readiness check has run yet");
            new HealthReport("database", "fine", new Ticking()).report(null);
            assertThat(HealthReport.last()).isEqualTo("database UP");
        } finally { HealthReport.forget(); }
    }

    /** A storage or identity outage behind bearer authentication says why it answered 503, once while it lasts. */
    @Test void dependencyFailuresBeforeMvcNameTheirTypesOnceWhileTheyLast() throws Exception {
        try (var log = LogCapture.start()) {
            var filter = new DomainStorageFailureFilter();
            for (int request = 0; request < 3; request++) {
                var response = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/plans"), response, (in, out) -> {
                    throw software.amazon.awssdk.core.exception.SdkClientException.create("host db.internal password=hunter2",
                            new java.net.ConnectException("connection to db.internal refused"));
                });
                assertThat(response.getStatus()).isEqualTo(503);
            }
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/plans"), response, (in, out) -> {
                throw new AuthenticationServiceException("identity at identity.internal");
            });
            assertThat(response.getContentAsString()).contains("IDENTITY_UNAVAILABLE");
            // Plain logs (owner, 2026-11-17): a dependency down is an ERROR quoting the dependency's own message (the host helps); never a secret.
            assertThat(log.messages()).containsExactly(
                    "Account storage unavailable on /api/v1/plans: ACCOUNT_STORAGE_UNAVAILABLE (ConnectException: connection to db.internal refused);"
                            + " answered 503 until it recovers; check the VPC endpoint and the security group between the task and the service",
                    "Sign-in check unavailable on /api/v1/plans: IDENTITY_UNAVAILABLE (AuthenticationServiceException: identity at identity.internal); answered 503 until it recovers");
            assertThat(log.lines()).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.ERROR));
            assertThat(LogCapture.keys(log.lines().getFirst())).containsEntry("http.status", 503)
                    .containsEntry("error.type", "SdkClientException < ConnectException");
            log.containsNone("hunter2");
        }
    }
}
