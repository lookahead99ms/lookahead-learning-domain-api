package com.lookahead.domain.publication;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.LogCapture;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-969 (owner, 2026-11-04): the content release says in the log why it did not load, or why readiness is DOWN. */
class PublicationLoggingTest {
    @TempDir Path directory;
    final PublicationBootstrapTest helper = new PublicationBootstrapTest();

    static final class Ticking extends Clock {
        Instant now = Instant.parse("2026-11-04T19:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @AfterEach void cleanup() throws Exception { helper.directory = directory; helper.writableForFixtureCleanup(); }

    @Test void aLoadedReleaseIsLoggedOnceWithItsTagAndFileCount() throws Exception {
        helper.directory = directory;
        var fixture = helper.fixture(null, false, false);
        try (var log = LogCapture.start("lookahead.startup")) {
            new PublicationBootstrap(helper.mapper).load(helper.settings(fixture), helper.source(fixture));
            var line = log.only("publication.load");
            assertThat(line.getLevel()).isEqualTo(Level.INFO);
            assertThat(line.getFormattedMessage()).isEqualTo("Content release " + PublicationBootstrapTest.TAG + " loaded: 3 files verified against its manifest (the pinned release)");
            assertThat(LogCapture.keys(line)).containsEntry("release.tag", PublicationBootstrapTest.TAG).containsEntry("count", 3);
        }
    }

    @Test void aReleaseThatFailsItsChecksNamesTheStepAndTheCheck() throws Exception {
        helper.directory = directory;
        var fixture = helper.fixture(null, false, true);
        try (var log = LogCapture.start("lookahead.startup")) {
            assertThatThrownBy(() -> new PublicationBootstrap(helper.mapper).load(helper.settings(fixture), helper.source(fixture)))
                    .hasMessage("Selected cloud publication failed startup verification");
            var line = log.only("publication.load");
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).isEqualTo("Startup refused: content release " + PublicationBootstrapTest.TAG
                    + " could not be loaded while extracting the archive: Archive member digest mismatch; the app stops before it takes traffic");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "PUBLICATION_INVALID").containsEntry("release.tag", PublicationBootstrapTest.TAG);
            assertThat(line.getFormattedMessage()).doesNotContain(directory.toString(), "synthetic-publication");
        }
    }

    @Test void anS3RefusalOrIoFailureNamesTheStatusCodeAndTypesNeverTheMessage() throws Exception {
        helper.directory = directory;
        var fixture = helper.fixture(null, false, false);
        var denied = S3Exception.builder().statusCode(403).message("Access Denied for arn:aws:iam::123456789012:role/secret-role")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").errorMessage("private detail").build()).build();
        try (var log = LogCapture.start("lookahead.startup")) {
            assertThatThrownBy(() -> new PublicationBootstrap(helper.mapper).load(helper.settings(fixture), key -> { throw denied; }))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new PublicationBootstrap(helper.mapper).load(helper.settings(fixture), key -> {
                if (key.endsWith("manifest.json")) return new ByteArrayInputStream(fixture.manifest());
                throw new IOException("connection to bucket.s3.internal reset");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(log.messages()).containsExactly(
                    "Startup refused: content release " + PublicationBootstrapTest.TAG + " could not be loaded while reading manifest.json:"
                            + " S3 answered HTTP 403 AccessDenied (S3Exception); the app stops before it takes traffic",
                    "Startup refused: content release " + PublicationBootstrapTest.TAG + " could not be loaded while downloading publication.tar.gz:"
                            + " IOException; the app stops before it takes traffic");
            assertThat(LogCapture.keys(log.lines().getFirst())).containsEntry("reason", "PUBLICATION_UNAVAILABLE").containsEntry("upstream.status", 403)
                    .containsEntry("upstream.code", "AccessDenied");
            log.containsNone("secret-role", "private detail", "s3.internal", "123456789012");
        }
    }

    @Test void theLiveListingStepIsNamedWhenNoReleaseIsPublished() throws Exception {
        var settings = new PublicationSettings(true, "us-east-2", "123456789012", "synthetic-publication", null, null, null, directory.toRealPath(), 1024, 4);
        var source = new PublicationObjectSource() {
            @Override public java.io.InputStream open(String key) { throw new AssertionError("nothing to open"); }
            @Override public java.util.List<ListedObject> list(String prefix) { return java.util.List.of(); }
        };
        try (var log = LogCapture.start("lookahead.startup")) {
            assertThatThrownBy(() -> new PublicationBootstrap(helper.mapper).load(settings, source)).isInstanceOf(IllegalStateException.class);
            assertThat(log.only("publication.load").getFormattedMessage()).isEqualTo("Startup refused: content release live could not be loaded while"
                    + " listing live/ in the publication bucket to find the serving release: No live release is published; the app stops before it takes traffic");
        }
    }

    @Test void contentReadinessSaysWhyItIsDownOnceAMinuteAndUpOnceRecovered() throws Exception {
        Path manifest = Files.writeString(directory.resolve("publication.json"), "{}");
        Path catalog = Files.writeString(directory.resolve("account-catalog.json"), "{}");
        Path content = Files.createDirectory(directory.resolve("content"));
        var location = new PublicationLocation(manifest.toString(), content.toString(), catalog.toString(), true, 0, 1);
        var policy = mock(ProtectedContentPolicy.class);
        when(policy.version()).thenReturn("disabled", "release-1");
        var clock = new Ticking();
        var health = new ContentPublicationHealth(location, policy, clock);
        try (var log = LogCapture.start("lookahead.health")) {
            assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
            assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
            Files.delete(catalog);
            assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
            assertThat(health.health().getDetails()).isEmpty();
            clock.now = clock.now.plusSeconds(30);
            health.health();
            clock.now = clock.now.plusSeconds(31);
            health.health();
            Files.delete(manifest);
            health.health();
            Files.writeString(manifest, "{}"); Files.writeString(catalog, "{}"); Files.delete(content);
            health.health();
            Files.createDirectory(content);
            health.health();
            health.health();
            assertThat(log.messages()).containsExactly(
                    "Readiness DOWN: no content release is loaded: the content policy started without a publication manifest or content folder;"
                            + " the container health check fails until this is fixed",
                    "Readiness UP: the content release is loaded and its files are on disk",
                    "Readiness DOWN: the loaded content release has lost its trusted account catalog (account-catalog.json) from the task's disk;"
                            + " the container health check fails until this is fixed",
                    "Readiness DOWN: the loaded content release has lost its trusted account catalog (account-catalog.json) from the task's disk;"
                            + " the container health check fails until this is fixed",
                    "Readiness DOWN: the loaded content release has lost its publication manifest (publication.json) from the task's disk;"
                            + " the container health check fails until this is fixed",
                    "Readiness DOWN: the loaded content release has lost its content folder from the task's disk; the container health check fails until this is fixed",
                    "Readiness UP: the content release is loaded and its files are on disk");
            assertThat(log.lines()).extracting(line -> LogCapture.keys(line).get("reason")).containsExactly("CONTENT_DISABLED", null, "CATALOG_MISSING",
                    "CATALOG_MISSING", "MANIFEST_MISSING", "CONTENT_ROOT_MISSING", null);
            assertThat(log.lines()).allSatisfy(line -> assertThat(LogCapture.keys(line)).containsEntry("health.component", "content publication"));
            log.containsNone(directory.toString());
        }
        var local = new ContentPublicationHealth(new PublicationLocation("", "", "", false, 0, 1), policy, clock);
        try (var log = LogCapture.start("lookahead.health")) {
            assertThat(local.health().getStatus().getCode()).isEqualTo("UP");
            assertThat(log.lines()).isEmpty();
        }
    }
}
