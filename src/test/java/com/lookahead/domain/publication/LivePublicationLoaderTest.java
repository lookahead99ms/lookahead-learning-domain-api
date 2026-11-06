package com.lookahead.domain.publication;

import com.lookahead.domain.LogCapture;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** DLV-971: exactly one v*.tar under release/live/ is served; zero, two or a bad tar leaves the task up and unready with the reason. */
class LivePublicationLoaderTest {
    static final String PREFIX = "release/live/", TAG = "v2026.11.06.16.00", OLDER = "v2026.11.05.12.00";
    @TempDir Path directory;
    final JsonMapper mapper = JsonMapper.builder().build();

    /** A source that lists the given keys and serves one tar's bytes for any of them. */
    static PublicationObjectSource source(Map<String, byte[]> objects) {
        return new PublicationObjectSource() {
            @Override public InputStream open(String key) throws IOException {
                byte[] bytes = objects.get(key);
                if (bytes == null) throw new IOException("no such key");
                return new ByteArrayInputStream(bytes);
            }
            @Override public List<ListedObject> list(String prefix) {
                return objects.keySet().stream().filter(key -> key.startsWith(prefix)).map(key -> new ListedObject(key, Instant.EPOCH)).toList();
            }
        };
    }

    byte[] tar(boolean gzip, String badMember, boolean wrongDigest, boolean extraMember) throws Exception {
        byte[] body = "{\"synthetic\":true}".getBytes();
        byte[] publication = mapper.writeValueAsBytes(Map.of("schemaVersion", "content-publication/v1", "version", "fixture-v1", "assets",
                List.of(Map.of("path", "/content/sample.json", "sha256", wrongDigest ? "0".repeat(64) : PublicationBootstrap.sha(body), "mediaType", "application/json", "tier", "public", "scopes", List.of(), "contentIds", List.of()))));
        var files = new TreeMap<String, byte[]>();
        files.put("publication.json", publication);
        files.put(badMember == null ? "content/sample.json" : badMember, body);
        files.put("account-catalog.json", mapper.writeValueAsBytes(Map.of("schemaVersion", "account-catalog/v1", "catalogVersion", "sha256:" + "a".repeat(64),
                "records", List.of(), "algorithmVersions", List.of(), "rankingVersions", List.of())));
        if (extraMember) files.put("content/unlisted.json", body);
        var bytes = new ByteArrayOutputStream();
        OutputStream out = gzip ? new GZIPOutputStream(bytes) : bytes;
        try (var tar = new TarArchiveOutputStream(out)) {
            tar.putArchiveEntry(new TarArchiveEntry("content/")); tar.closeArchiveEntry();
            for (var entry : files.entrySet()) {
                var member = new TarArchiveEntry(entry.getKey());
                member.setSize(entry.getValue().length); tar.putArchiveEntry(member); tar.write(entry.getValue()); tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    PublicationSettings settings() throws Exception {
        return new PublicationSettings(true, "us-east-2", "123456789012", "synthetic-assets", null, null, null, directory.toRealPath(), 1024, 4);
    }

    @AfterEach void writableForFixtureCleanup() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) if (Files.isDirectory(path)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test void exactlyOneTarIsUnpackedReadOnlyAndReportedInReadinessAndTheLog() throws Exception {
        for (boolean gzip : List.of(true, false)) {
            var objects = Map.of(PREFIX, new byte[0], PREFIX + TAG + ".tar", tar(gzip, null, false, false));
            LivePublicationLoader.Loaded loaded;
            try (var log = LogCapture.start("lookahead.startup")) {
                loaded = new LivePublicationLoader(mapper).load(settings(), PREFIX, source(objects));
                var line = log.only("publication.load");
                assertThat(line.getFormattedMessage()).isEqualTo("Content release " + TAG + " loaded from release/live/" + TAG + ".tar: 3 files verified against its publication");
                assertThat(LogCapture.keys(line)).containsEntry("release.tag", TAG).containsEntry("event.outcome", "success");
            }
            assertThat(loaded.release().loaded()).isTrue();
            assertThat(loaded.release().version()).isEqualTo(TAG);
            assertThat(loaded.release().prefix()).isEqualTo(PREFIX);
            assertThat(Files.readString(Path.of(loaded.location().contentRoot()).resolve("sample.json"))).contains("synthetic");
            assertThat(Files.getPosixFilePermissions(Path.of(loaded.location().manifest()))).isEqualTo(PosixFilePermissions.fromString("r--------"));
            var policy = new ProtectedContentPolicy(mapper, loaded.location());
            assertThat(policy.version()).isEqualTo("fixture-v1");
            assertThat(new SnapshotValidator(mapper, loaded.location()).catalogVersion()).startsWith("sha256:");
            var health = new ContentPublicationHealth(loaded.location(), policy, loaded.release(), Clock.systemUTC());
            assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
            assertThat(health.health().getDetails()).containsEntry("release", TAG);
            writableForFixtureCleanup();
        }
    }

    @Test void zeroOrTwoTarsOrAStrayObjectLeavesTheTaskUnreadyWithTheReason() throws Exception {
        byte[] good = tar(true, null, false, false);
        var cases = List.of(
                Map.entry(Map.<String, byte[]>of(), new String[] {"LIVE_RELEASE_MISSING", "no publication tar under release/live/: exactly one v*.tar is expected; publish a release or copy one from release/archive/"}),
                Map.entry(Map.of(PREFIX + TAG + ".tar", good, PREFIX + OLDER + ".tar", good), new String[] {"LIVE_RELEASE_AMBIGUOUS", "2 publication tars under release/live/ (" + OLDER + ", " + TAG + "): exactly one is expected; move the others to release/archive/"}),
                Map.entry(Map.of(PREFIX + TAG + ".tar", good, PREFIX + "notes.txt", good), new String[] {"LIVE_RELEASE_UNEXPECTED", "1 object(s) under release/live/ that are not a v*.tar (notes.txt): remove them, exactly one publication tar is expected"}));
        for (var entry : cases) {
            LivePublicationLoader.Loaded loaded;
            try (var log = LogCapture.start("lookahead.startup", "lookahead.health")) {
                loaded = new LivePublicationLoader(mapper).load(settings(), PREFIX, source(entry.getKey()));
                var line = log.only("publication.load");
                assertThat(LogCapture.keys(line)).containsEntry("reason", entry.getValue()[0]).containsEntry("release.tag", "none");
                assertThat(line.getFormattedMessage()).isEqualTo("Content release not loaded: " + entry.getValue()[1]
                        + "; the task stays up with readiness DOWN until the next start finds exactly one good tar under release/live/");
                assertThat(loaded.release().loaded()).isFalse();
                assertThat(loaded.release().problem().reason()).isEqualTo(entry.getValue()[0]);
                // The task starts: the content policy is disabled, the catalog is an empty trusted catalog, readiness says why.
                var policy = new ProtectedContentPolicy(mapper, loaded.location());
                assertThat(policy.version()).isEqualTo("disabled");
                assertThat(new SnapshotValidator(mapper, loaded.location()).allTopicIds()).isEmpty();
                var health = new ContentPublicationHealth(loaded.location(), policy, loaded.release(), Clock.systemUTC());
                assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
                assertThat(health.health().getDetails()).isEmpty();
                assertThat(LogCapture.keys(log.lines("readiness").getFirst())).containsEntry("reason", entry.getValue()[0]);
                assertThat(log.lines("readiness").getFirst().getFormattedMessage()).startsWith("Readiness DOWN: " + entry.getValue()[1]);
            }
            try (var files = Files.list(directory)) { assertThat(files.filter(p -> p.getFileName().toString().startsWith("publication-")).toList()).isEmpty(); }
        }
    }

    @Test void aBadTarIsRefusedWithoutAPartialPublicationAndS3FailuresAreUnavailable() throws Exception {
        var bad = List.of(tar(true, "../escape.json", false, false), tar(false, null, true, false), tar(true, null, false, true), tar(true, "evidence/secret.json", false, false), "not a tar".getBytes());
        for (byte[] archive : bad) {
            var loaded = new LivePublicationLoader(mapper).load(settings(), PREFIX, source(Map.of(PREFIX + TAG + ".tar", archive)));
            assertThat(loaded.release().loaded()).isFalse();
            assertThat(loaded.release().version()).isEqualTo(TAG);
            assertThat(loaded.release().problem().reason()).isEqualTo("LIVE_RELEASE_INVALID");
            assertThat(loaded.release().problem().words()).startsWith("the content release " + TAG + " could not be loaded while ");
            try (var files = Files.list(directory)) { assertThat(files.filter(p -> p.getFileName().toString().startsWith("publication-")).toList()).isEmpty(); }
        }
        var denied = new LivePublicationLoader(mapper).load(settings(), PREFIX, new PublicationObjectSource() {
            @Override public InputStream open(String key) { throw software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(403).build(); }
            @Override public List<ListedObject> list(String prefix) { return List.of(new ListedObject(PREFIX + TAG + ".tar", Instant.EPOCH)); }
        });
        assertThat(denied.release().problem().reason()).isEqualTo("LIVE_RELEASE_UNAVAILABLE");
        assertThat(denied.release().problem().words()).contains("S3 answered HTTP 403").contains("downloading " + TAG + ".tar");
        var listingFails = new LivePublicationLoader(mapper).load(settings(), PREFIX, key -> { throw new IOException("listing refused"); });
        assertThat(listingFails.release().problem().reason()).isEqualTo("LIVE_RELEASE_UNAVAILABLE");
        assertThat(listingFails.release().problem().words()).contains("listing release/live/");
        assertThatThrownBy(() -> new LivePublicationLoader(mapper).load(settings(), "Release/Live/", source(Map.of()))).isInstanceOf(IllegalStateException.class);
    }

    @Test void theConfigurationChoosesTheLiveTarOnlyWhenThePrefixIsSetInCloudMode() throws Exception {
        var objects = Map.of(PREFIX + TAG + ".tar", tar(true, null, false, false));
        var configuration = new PublicationConfiguration();
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("source", source(objects));
        var environment = new MockEnvironment().withProperty("app.publication.live-prefix", " " + PREFIX + " ");
        var loaded = configuration.publicationLoaded(settings(), environment, beans.getBeanProvider(PublicationObjectSource.class), mapper);
        assertThat(configuration.liveRelease(loaded).version()).isEqualTo(TAG);
        assertThat(configuration.publicationLocation(loaded).cloud()).isTrue();
        var local = configuration.publicationLoaded(new PublicationSettings(false, null, null, null, null, null, null, null, 0, 0),
                new MockEnvironment().withProperty("app.publication.live-prefix", PREFIX).withProperty("app.content.root", "/tmp/x"), beans.getBeanProvider(PublicationObjectSource.class), mapper);
        assertThat(local.release().configured()).isFalse();
        assertThat(local.location().contentRoot()).isEqualTo("/tmp/x");
        assertThat(LiveRelease.notConfigured().loaded()).isFalse();
    }
}
