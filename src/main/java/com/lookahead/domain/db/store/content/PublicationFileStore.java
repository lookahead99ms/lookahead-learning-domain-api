package com.lookahead.domain.db.store.content;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;

/**
 * The publication's files on the task's own disk: the private folder a release is unpacked into ({@link Stage}), the
 * downloaded archive, the unpacked files and the files of the loaded release that the account services read. File
 * operations only; which archive member is acceptable, which digest must match and what a failure means is decided by
 * the caller ({@code PublicationArchiveLoadService}, {@code PublicationLiveLoadService}, {@code ProtectedContentManifestService},
 * {@code AccountCatalogService}, {@code DeliveryPlanReleaseImportJob}). DLV-1016: these operations were written inside
 * those services, then were static methods here that the services called with paths they built; now the store is
 * injected and only it builds paths.
 */
// Exception, decided 2026-11-25 (DLV-1016): db.store is not DynamoDB only. It also holds the file-system stores: this
// class, ProtectedContentFileStore, review.AuthorReviewManifestFileStore and testaccounts.TestAccountPlanFixtureFileStore.
// They are the persistence layer of the content release and of the two operator-supplied files on the task's own disk,
// so they stay with the other stores. None of them calls S3 or a database: the release archive is fetched by
// client.PublicationS3Client and reaches this store as a stream.
@Component
public final class PublicationFileStore {

    // ---- the loaded release, by the names the publication location carries -----------------------------------------

    /** True when {@code file} is a regular file on disk. */
    public boolean holdsFile(String file) { return Files.isRegularFile(Path.of(file)); }
    /** True when {@code folder} is a folder on disk. */
    public boolean holdsFolder(String folder) { return Files.isDirectory(Path.of(folder)); }
    /** True when {@code name} is a regular file under {@code folder}. */
    public boolean holdsFile(String folder, String name) { return Files.isRegularFile(Path.of(folder).resolve(name)); }
    /** The bytes of the file {@code name} under {@code folder}. */
    public byte[] bytes(String folder, String name) throws IOException { return Files.readAllBytes(Path.of(folder).resolve(name)); }
    /** The file's text, or empty when the file is larger than {@code maximumBytes} (nothing is read then). */
    public Optional<String> text(String file, long maximumBytes) throws IOException {
        Path path = Path.of(file);
        if (Files.size(path) > maximumBytes) return Optional.empty();
        return Optional.of(Files.readString(path));
    }
    /**
     * A folder of the loaded release as the file system resolved it. Only the stores of this package read its path
     * ({@link ProtectedContentFileStore}); a service holds it and hands it on (DLV-1016: the content policy held a
     * {@code java.nio.file.Path}).
     */
    public static final class Folder {
        final Path path;
        private Folder(Path path) { this.path = path; }
    }
    /** The folder as the file system resolves it (links followed); fails when it does not exist. */
    public Folder realFolder(String folder) throws IOException { return new Folder(Path.of(folder).toRealPath()); }

    // ---- loading a release into a private folder -------------------------------------------------------------------

    /**
     * One release while it is loaded: a new private folder under the publication directory, the archive downloaded into
     * it and the folder {@code release} its members are unpacked into. Only this store knows the paths.
     */
    public static final class Stage {
        private final Path folder;
        private Path archive, release;
        private Stage(Path folder) { this.folder = folder; }
    }

    /** True when the folder or one of its parents is a symbolic link. */
    public boolean symbolicLinkOnPath(Path folder) {
        for (Path cursor = folder; cursor != null; cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor)) return true;
        return false;
    }
    /** A new stage under {@code directory} (created with its parents when missing) that only this task can read and write. */
    public Stage stage(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new Stage(privateFolder(directory, "publication-"));
    }
    /** Writes the stream into the stage as its archive, a new file {@code name}; one byte past {@code maximumBytes} fails. Answers the bytes written. */
    public long download(Stage stage, String name, InputStream stream, long maximumBytes) throws IOException {
        stage.archive = stage.folder.resolve(name);
        try (OutputStream output = Files.newOutputStream(stage.archive, StandardOpenOption.CREATE_NEW)) { return copy(stream, output, maximumBytes, null); }
    }
    /** SHA-256 of the stage's archive of at most {@code maximumBytes}, in lowercase hex. */
    public String archiveSha256(Stage stage, long maximumBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(stage.archive)) { copy(input, OutputStream.nullOutputStream(), maximumBytes, digest); }
        return HexFormat.of().formatHex(digest.digest());
    }
    /** Unpacks the stage's archive, a gzip tar of at most {@code expandedMaximum} bytes, into the stage's new release folder; see {@link MemberChecks}. */
    public void unpackGzip(Stage stage, long expandedMaximum, MemberChecks checks) throws Exception {
        stage.release = Files.createDirectory(stage.folder.resolve("release"));
        unpack(stage.archive, true, expandedMaximum, stage.release, checks);
    }
    /** As {@link #unpackGzip}, for an archive that is a plain or a gzip tar: its first two bytes say which. */
    public void unpackTar(Stage stage, long expandedMaximum, MemberChecks checks) throws Exception {
        stage.release = Files.createDirectory(stage.folder.resolve("release"));
        unpack(stage.archive, gzip(stage.archive), expandedMaximum, stage.release, checks);
    }
    /** The bytes of the unpacked member {@code name}. */
    public byte[] unpacked(Stage stage, String name) throws IOException { return Files.readAllBytes(stage.release.resolve(name)); }
    /** Ends the load: the archive is deleted, then every unpacked file is made read-only and every folder read and list only, for this task's user. */
    public void seal(Stage stage) throws IOException {
        Files.delete(stage.archive);
        lockReadOnly(stage.release);
    }
    /** Where the unpacked member or folder {@code name} is on disk, for the publication location. */
    public String location(Stage stage, String name) { return stage.release.resolve(name).toString(); }
    /** Removes a stage that was never published, sealed or not. A failure leaves it private and unused. */
    public void discard(Stage stage) {
        try (var paths = Files.walk(stage.folder)) {
            var all = paths.toList();
            for (Path path : all) if (Files.isDirectory(path)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            for (Path path : all.reversed()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Remains private and unready; task teardown owns temporary disk. */ }
    }
    /**
     * The one file a task without a loaded release still needs: writes {@code bytes} as {@code name} into a new private
     * folder under {@code directory}, makes both read-only and answers where the file is.
     */
    public String placeholder(Path directory, String name, byte[] bytes) throws IOException {
        Path folder = privateFolder(directory, "unloaded-");
        Path file = Files.write(folder.resolve(name), bytes, StandardOpenOption.CREATE_NEW);
        lockReadOnly(folder);
        return file.toString();
    }

    /** A new folder under {@code parent} that only this task can read and write; its name starts with {@code prefix}. */
    private static Path privateFolder(Path parent, String prefix) throws IOException {
        return Files.createTempDirectory(parent, prefix, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    /** Every file read-only, every folder read and list only, for this task's user. */
    private static void lockReadOnly(Path folder) throws IOException {
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "r-x------" : "r--------"));
        }
    }
    /** True when the file starts with the gzip magic bytes. */
    private static boolean gzip(Path file) throws IOException {
        try (InputStream peek = Files.newInputStream(file)) {
            byte[] magic = peek.readNBytes(2);
            return magic.length == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
        }
    }

    // ---- unpacking -------------------------------------------------------------------------------------------------

    /** One member of the archive as the tar describes it. {@code plainFile}: a regular file, no link of either kind, not sparse. */
    public record TarMember(String name, long bytes, boolean directory, boolean plainFile) {}

    /** What the caller decides for each member while the archive is unpacked; any of the four may throw to refuse the archive. */
    public interface MemberChecks {
        /** First, for every member, before anything is written: false leaves the member out. */
        boolean wanted(TarMember member);
        /** Then, still before anything is written: {@code insideFolder} says whether the member's path stays inside the target folder. Answers the bytes the member must not exceed. */
        long placed(TarMember member, boolean insideFolder);
        /** After the member's bytes are written, while its file is still open. */
        void written(TarMember member, long bytes);
        /** After the file is closed: its SHA-256 in lowercase hex. */
        void digested(TarMember member, String sha256);
    }

    /** Unpacks a tar ({@code gzip}: compressed) of at most {@code expandedMaximum} bytes into {@code folder}, one member at a time, in the archive's order. */
    private static void unpack(Path archive, boolean gzip, long expandedMaximum, Path folder, MemberChecks checks) throws Exception {
        try (var raw = Files.newInputStream(archive);
             var bounded = new LimitedStream(gzip ? new GZIPInputStream(raw) : raw, expandedMaximum);
             var tar = new TarArchiveInputStream(bounded)) {
            for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                var member = new TarMember(entry.getName(), entry.getSize(), entry.isDirectory(),
                        entry.isFile() && !entry.isLink() && !entry.isSymbolicLink() && !entry.isSparse());
                if (!checks.wanted(member)) continue;
                Path destination = folder.resolve(member.name()).normalize();
                long bytes = checks.placed(member, destination.startsWith(folder));
                // Both callers refuse a member outside the folder in placed(), with their own sentence. This guard repeats
                // that rule here, next to the write, so that a caller that forgets cannot write outside the folder and so
                // that the check is visible where the file is opened (CodeQL java/zipslip, 2026-11-27).
                if (!destination.startsWith(folder)) throw new IOException("Archive member outside the target folder");
                Files.createDirectories(destination.getParent());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    checks.written(member, copy(tar, output, bytes, digest));
                }
                checks.digested(member, HexFormat.of().formatHex(digest.digest()));
            }
        }
    }

    /** Copies at most {@code maximum} bytes; one more fails. Answers the bytes copied. */
    private static long copy(InputStream input, OutputStream output, long maximum, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long total = 0;
        for (int count; (count = input.read(buffer, 0, (int)Math.min(buffer.length, maximum - total + 1))) != -1;) {
            total += count; if (total > maximum) throw new IOException("Publication stream exceeds bound");
            if (digest != null) digest.update(buffer, 0, count); output.write(buffer, 0, count);
        }
        return total;
    }

    /** A stream that fails once more than {@code maximum} bytes were read from it. */
    static final class LimitedStream extends FilterInputStream {
        private long remaining;
        LimitedStream(InputStream input, long maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int count = in.read(b, off, (int)Math.min(len, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("Expanded archive exceeds bound");
            return count;
        }
    }
}
