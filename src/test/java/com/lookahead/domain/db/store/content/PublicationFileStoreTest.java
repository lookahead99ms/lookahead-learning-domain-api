package com.lookahead.domain.db.store.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file operations the publication services and the release import job use; decisions stay with the callers. */
class PublicationFileStoreTest {
    @TempDir Path temp;
    private final PublicationFileStore files = new PublicationFileStore();

    private static byte[] tar(boolean gzip, String... namesAndBodies) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(gzip ? new GZIPOutputStream(bytes) : bytes)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                if (namesAndBodies[i].endsWith("/")) { tar.putArchiveEntry(new TarArchiveEntry(namesAndBodies[i])); tar.closeArchiveEntry(); continue; }
                byte[] body = namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(namesAndBodies[i]); entry.setSize(body.length);
                tar.putArchiveEntry(entry); tar.write(body); tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Accepts every member as the tar describes it and records nothing. */
    private static PublicationFileStore.MemberChecks accepting() {
        return new PublicationFileStore.MemberChecks() {
            @Override public boolean wanted(PublicationFileStore.TarMember member) { return !member.directory(); }
            @Override public long placed(PublicationFileStore.TarMember member, boolean insideFolder) { if (!insideFolder) throw new IllegalArgumentException("outside"); return member.bytes(); }
            @Override public void written(PublicationFileStore.TarMember member, long bytes) {}
            @Override public void digested(PublicationFileStore.TarMember member, String sha256) {}
        };
    }

    @Test void boundedReadsAnswerEmptyPastTheBound() throws Exception {
        Path file = Files.writeString(temp.resolve("a.json"), "{\"a\":1}");
        assertThat(files.text(file.toString(), 7)).contains("{\"a\":1}");
        assertThat(files.text(file.toString(), 6)).isEmpty();
        assertThat(files.bytes(temp.toString(), "a.json")).hasSize(7);
        assertThat(files.realFolder(temp.toString()).path).isEqualTo(temp.toRealPath());
        assertThatThrownBy(() -> files.text(temp.resolve("missing").toString(), 10)).isInstanceOf(IOException.class);
        Path link = Files.createSymbolicLink(temp.resolve("link.json"), file);
        assertThat(files.symbolicLinkOnPath(link)).isTrue();
        assertThat(files.symbolicLinkOnPath(temp.toRealPath().resolve("a.json"))).isFalse();
    }

    @Test void answersWhatTheLoadedReleaseHoldsByTheNamesItsLocationCarries() throws Exception {
        Path content = Files.createDirectories(temp.resolve("content/staff"));
        Path file = Files.writeString(content.resolve("delivery-plan.json"), "{}");
        assertThat(files.holdsFile(file.toString())).isTrue();
        assertThat(files.holdsFile(content.toString())).as("a folder is not a file").isFalse();
        assertThat(files.holdsFile(temp.resolve("missing.json").toString())).isFalse();
        assertThat(files.holdsFolder(content.toString())).isTrue();
        assertThat(files.holdsFolder(file.toString())).isFalse();
        assertThat(files.holdsFile(temp.resolve("content").toString(), "staff/delivery-plan.json")).isTrue();
        assertThat(files.holdsFile(temp.resolve("content").toString(), "staff")).as("a folder is not a file").isFalse();
        assertThat(files.holdsFile(temp.resolve("content").toString(), "delivery/delivery-plan.json")).isFalse();
        assertThat(files.bytes(temp.resolve("content").toString(), "staff/delivery-plan.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> files.bytes(temp.resolve("content").toString(), "delivery/delivery-plan.json")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> files.realFolder(temp.resolve("missing").toString())).isInstanceOf(IOException.class);
    }

    @Test void stagesWritesDigestsLocksAndCleansAPrivateFolder() throws Exception {
        Path parent = temp.resolve("one/two");
        var stage = files.stage(parent);
        assertThat(files.download(stage, "download.tar", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3)).isEqualTo(3);
        assertThatThrownBy(() -> files.download(stage, "download.tar", new ByteArrayInputStream(new byte[0]), 3)).as("never overwrites").isInstanceOf(IOException.class);
        assertThat(files.archiveSha256(stage, 3)).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
        assertThatThrownBy(() -> files.archiveSha256(stage, 2)).isInstanceOf(IOException.class).hasMessage("Publication stream exceeds bound");
        files.discard(stage);
        var tooLarge = files.stage(parent);
        assertThatThrownBy(() -> files.download(tooLarge, "big", new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 3))
                .isInstanceOf(IOException.class).hasMessage("Publication stream exceeds bound");
        files.discard(tooLarge);
        try (var left = Files.list(parent)) { assertThat(left).as("a discarded stage leaves nothing behind").isEmpty(); }

        var loaded = files.stage(parent);
        files.download(loaded, "download.tar", new ByteArrayInputStream(tar(false, "publication.json", "{}", "content/a.json", "{\"a\":1}")), 1 << 20);
        files.unpackTar(loaded, 1 << 20, accepting());
        assertThat(files.unpacked(loaded, "content/a.json")).hasSize(7);
        Path written = Path.of(files.location(loaded, "content/a.json"));
        Path folder = Path.of(files.location(loaded, "content")).getParent();
        assertThat(folder.getFileName().toString()).isEqualTo("release");
        assertThat(folder.getParent().getFileName().toString()).startsWith("publication-");
        assertThat(folder.getParent().getParent()).isEqualTo(parent);
        assertThat(Files.exists(folder.getParent().resolve("download.tar"))).isTrue();
        files.seal(loaded);
        assertThat(Files.exists(folder.getParent().resolve("download.tar"))).as("the archive is deleted when the stage is sealed").isFalse();
        assertThat(Files.isWritable(written)).isFalse();
        assertThat(Files.isWritable(folder)).isFalse();
        assertThat(Files.readString(written)).isEqualTo("{\"a\":1}");
        files.discard(loaded);
        assertThat(Files.exists(folder.getParent())).isFalse();
        files.discard(loaded); // a missing folder is not an error
    }

    @Test void writesTheOneFileATaskWithoutAReleaseNeedsIntoItsOwnReadOnlyFolder() throws Exception {
        Path parent = Files.createDirectories(temp.resolve("publication"));
        Path catalog = Path.of(files.placeholder(parent, "account-catalog.json", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(catalog.getFileName().toString()).isEqualTo("account-catalog.json");
        assertThat(catalog.getParent().getFileName().toString()).startsWith("unloaded-");
        assertThat(catalog.getParent().getParent()).isEqualTo(parent);
        assertThat(Files.readString(catalog)).isEqualTo("{}");
        assertThat(Files.isWritable(catalog)).isFalse();
        assertThat(Files.isWritable(catalog.getParent())).isFalse();
        assertThatThrownBy(() -> files.placeholder(temp.resolve("missing"), "account-catalog.json", new byte[0])).isInstanceOf(IOException.class);
    }

    @Test void unpacksInArchiveOrderAndAsksTheCallerAtEachPoint() throws Exception {
        Path parent = temp.resolve("stages");
        for (boolean gzip : new boolean[] {false, true}) {
            var stage = files.stage(parent);
            files.download(stage, gzip ? "a.tar.gz" : "a.tar", new ByteArrayInputStream(tar(gzip, "content/", "", "content/a.json", "{\"a\":1}", "skip.txt", "no", "../out.json", "x")), 1 << 20);
            var calls = new ArrayList<String>();
            var recording = new PublicationFileStore.MemberChecks() {
                @Override public boolean wanted(PublicationFileStore.TarMember member) {
                    calls.add("wanted " + member.name() + " " + member.directory() + " " + member.plainFile());
                    return !member.directory() && !member.name().equals("skip.txt") && !member.name().startsWith("..");
                }
                @Override public long placed(PublicationFileStore.TarMember member, boolean insideFolder) { calls.add("placed " + insideFolder); return member.bytes(); }
                @Override public void written(PublicationFileStore.TarMember member, long bytes) { calls.add("written " + bytes); }
                @Override public void digested(PublicationFileStore.TarMember member, String sha256) { calls.add("digested " + sha256.length()); }
            };
            // A plain or gzip tar is told apart by its first two bytes; the pinned release is always read as gzip.
            if (gzip) files.unpackGzip(stage, 1 << 20, recording); else files.unpackTar(stage, 1 << 20, recording);
            assertThat(calls).containsExactly("wanted content/ true false", "wanted content/a.json false true", "placed true", "written 7", "digested 64",
                    "wanted skip.txt false true", "wanted ../out.json false true");
            assertThat(new String(files.unpacked(stage, "content/a.json"), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
            assertThat(Files.exists(Path.of(files.location(stage, "skip.txt")))).isFalse();
            assertThatThrownBy(() -> files.unpackTar(stage, 1 << 20, recording)).as("the release folder of a stage is created once").isInstanceOf(FileAlreadyExistsException.class);
        }
        var detected = files.stage(parent);
        files.download(detected, "a.tar", new ByteArrayInputStream(tar(true, "content/a.json", "{\"a\":1}")), 1 << 20);
        files.unpackTar(detected, 1 << 20, accepting());
        assertThat(files.unpacked(detected, "content/a.json")).as("a gzip tar is recognised without being told").hasSize(7);
        var plain = files.stage(parent);
        files.download(plain, "a.tar.gz", new ByteArrayInputStream(tar(false, "content/a.json", "{\"a\":1}")), 1 << 20);
        assertThatThrownBy(() -> files.unpackGzip(plain, 1 << 20, accepting())).as("a plain tar is not accepted where gzip is required").isInstanceOf(ZipException.class);
        // The caller refuses a member outside the folder before anything is written; the bound on expanded bytes is the store's.
        var escape = files.stage(parent);
        files.download(escape, "escape.tar", new ByteArrayInputStream(tar(false, "../out.json", "x")), 1 << 20);
        assertThatThrownBy(() -> files.unpackTar(escape, 1 << 20, accepting())).isInstanceOf(IllegalArgumentException.class).hasMessage("outside");
        assertThat(Files.exists(Path.of(files.location(escape, "x")).getParent().getParent().resolve("out.json"))).isFalse();
        // A caller that forgets to refuse it still cannot write outside the folder: the store refuses next to the write.
        var careless = files.stage(parent);
        files.download(careless, "careless.tar", new ByteArrayInputStream(tar(false, "../careless.json", "x")), 1 << 20);
        var ignoring = new PublicationFileStore.MemberChecks() {
            @Override public boolean wanted(PublicationFileStore.TarMember member) { return !member.directory(); }
            @Override public long placed(PublicationFileStore.TarMember member, boolean insideFolder) { return member.bytes(); }
            @Override public void written(PublicationFileStore.TarMember member, long bytes) {}
            @Override public void digested(PublicationFileStore.TarMember member, String sha256) {}
        };
        assertThatThrownBy(() -> files.unpackTar(careless, 1 << 20, ignoring)).isInstanceOf(IOException.class)
                .hasMessage("Archive member outside the target folder");
        assertThat(Files.exists(Path.of(files.location(careless, "x")).getParent().getParent().resolve("careless.json"))).isFalse();
        var large = files.stage(parent);
        files.download(large, "large.tar", new ByteArrayInputStream(tar(false, "a.bin", "x".repeat(4096))), 1 << 20);
        assertThatThrownBy(() -> files.unpackTar(large, 16, accepting())).isInstanceOf(IOException.class).hasMessage("Expanded archive exceeds bound");
        assertThat(new PublicationFileStore.LimitedStream(new ByteArrayInputStream(new byte[] {7}), 1).read()).isEqualTo(7);
    }
}
