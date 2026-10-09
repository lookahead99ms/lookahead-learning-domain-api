package com.lookahead.domain.publication;

import com.lookahead.learning.content.service.ProtectedContentPolicy;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Complete hash-pinned extraction into a fresh private directory before bean publication. */
public final class PublicationBootstrap {
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    static final int MAX_FILE = 32 * 1024 * 1024;
    private record Entry(long bytes, String sha256) {}
    private final ObjectMapper mapper;
    public PublicationBootstrap(ObjectMapper mapper) { this.mapper = mapper; }

    public PublicationLocation load(PublicationSettings settings, PublicationObjectSource source) {
        Path stage = null;
        try {
            Path parent = settings.directory();
            for (Path cursor = parent; cursor != null; cursor = cursor.getParent())
                require(!Files.isSymbolicLink(cursor), "Publication directory cannot contain symlinks");
            Files.createDirectories(parent);
            stage = Files.createTempDirectory(parent, "publication-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            String prefix = "releases/" + settings.releaseId() + "/";
            byte[] manifestBytes;
            try (InputStream stream = source.open(prefix + "manifest.json")) { manifestBytes = stream.readNBytes(MAX_FILE + 1); }
            require(manifestBytes.length <= MAX_FILE && sha(manifestBytes).equals(settings.manifestSha256()), "Transport manifest digest mismatch");
            JsonNode manifest = mapper.readTree(manifestBytes);
            require(manifest.path("schemaVersion").asText().equals("lookahead-publication-archive/v1")
                    && manifest.path("releaseId").asText().equals(settings.releaseId())
                    && manifest.path("archiveSha256").asText().equals(settings.archiveSha256()), "Transport contract mismatch");
            Map<String, Entry> expected = inventory(manifest);
            require(expected.containsKey("publication.json") && expected.containsKey("account-catalog.json"), "Complete release requires publication and trusted account catalog");
            Path archive = stage.resolve("download.tar.gz");
            try (InputStream stream = source.open(prefix + "publication.tar.gz"); OutputStream output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
                require(copy(stream, output, MAX_BYTES, null) <= MAX_BYTES, "Compressed archive exceeds bound");
            }
            require(sha(archive).equals(settings.archiveSha256()), "Archive digest mismatch");
            Path extracted = Files.createDirectory(stage.resolve("release"));
            Set<String> seen = new HashSet<>();
            try (var compressed = Files.newInputStream(archive);
                 var gzip = new GZIPInputStream(compressed);
                 var bounded = new LimitedStream(gzip, MAX_BYTES + 64L * 1024 * 1024);
                 var tar = new TarArchiveInputStream(bounded)) {
                for (var member = tar.getNextEntry(); member != null; member = tar.getNextEntry()) {
                    String name = member.getName();
                    Entry entry = expected.get(name);
                    require(member.isFile() && !member.isLink() && !member.isSymbolicLink() && !member.isSparse()
                            && entry != null && seen.add(name) && member.getSize() == entry.bytes(), "Unsafe, duplicate or unlisted archive member");
                    Path destination = extracted.resolve(name).normalize();
                    require(destination.startsWith(extracted), "Archive traversal");
                    Files.createDirectories(destination.getParent());
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                        require(copy(tar, output, entry.bytes(), digest) == entry.bytes(), "Archive size mismatch");
                    }
                    require(HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()), "Archive member digest mismatch");
                }
            }
            require(seen.equals(expected.keySet()), "Incomplete archive");
            byte[] publicationBytes = Files.readAllBytes(extracted.resolve("publication.json"));
            byte[] catalogBytes = Files.readAllBytes(extracted.resolve("account-catalog.json"));
            MessageDigest releaseDigest = MessageDigest.getInstance("SHA-256");
            releaseDigest.update(publicationBytes); releaseDigest.update(catalogBytes);
            require(sha(publicationBytes).equals(manifest.path("publicationSha256").asText())
                    && settings.releaseId().equals("release-" + HexFormat.of().formatHex(releaseDigest.digest()).substring(0, 24)), "Publication identity mismatch");
            require(expected.get("account-catalog.json").sha256().equals(manifest.path("trustedCatalogSha256").asText()), "Trusted catalog digest missing or mismatched");
            JsonNode publication = mapper.readTree(publicationBytes);
            require(publication.path("schemaVersion").asText().equals("content-publication/v1") && publication.path("assets").isArray(), "Publication contract mismatch");
            Set<String> approved = new HashSet<>(Set.of("publication.json", "account-catalog.json"));
            for (JsonNode asset : publication.path("assets")) {
                String path = asset.path("path").asText();
                require(ProtectedContentPolicy.validPath(path), "Invalid publication path");
                String name = path.substring(1);
                require(approved.add(name) && expected.containsKey(name)
                        && expected.get(name).sha256().equals(asset.path("sha256").asText()), "Publication allowlist differs from archive");
            }
            require(approved.equals(seen), "Unapproved archive inventory");
            require(mapper.readTree(catalogBytes).path("schemaVersion").asText().equals("account-catalog/v1"), "Invalid trusted catalog");
            Files.delete(archive);
            try (var paths = Files.walk(extracted)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "r-x------" : "r--------"));
            }
            return new PublicationLocation(extracted.resolve("publication.json").toString(), extracted.resolve("content").toString(),
                    extracted.resolve("account-catalog.json").toString(), true, settings.cacheBytes(), settings.cacheEntries());
        } catch (Exception failure) {
            // A partial stage is never exposed. Do not log SDK response bodies or secret material.
            if (stage != null) cleanup(stage);
            throw new IllegalStateException("Selected cloud publication failed startup verification");
        }
    }

    private Map<String, Entry> inventory(JsonNode manifest) {
        JsonNode files = manifest.path("files");
        require(files.isArray() && files.size() >= 2 && files.size() <= 50002, "Invalid archive inventory");
        Map<String, Entry> result = new HashMap<>(); long total = 0;
        for (JsonNode file : files) {
            String path = file.path("path").asText(), digest = file.path("sha256").asText();
            JsonNode size = file.path("bytes");
            require(safePath(path) && digest.matches("[a-f0-9]{64}") && size.isIntegralNumber() && size.canConvertToLong(), "Invalid inventory member");
            long bytes = size.asLong();
            require(bytes >= 0 && bytes <= MAX_FILE && result.putIfAbsent(path, new Entry(bytes, digest)) == null, "Duplicate or oversized member");
            total += bytes; require(total <= MAX_BYTES, "Expanded publication exceeds bound");
        }
        require(manifest.path("totalBytes").isIntegralNumber() && manifest.path("totalBytes").asLong() == total, "Invalid inventory total");
        return result;
    }
    private static boolean safePath(String path) {
        if (Set.of("publication.json", "account-catalog.json").contains(path)) return true;
        return ProtectedContentPolicy.validPath("/" + path)
                && Arrays.stream(path.split("/")).noneMatch(Set.of("delivery", "evidence", "access")::contains);
    }
    private static long copy(InputStream input, OutputStream output, long maximum, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long total = 0;
        for (int count; (count = input.read(buffer, 0, (int)Math.min(buffer.length, maximum - total + 1))) != -1;) {
            total += count; if (total > maximum) throw new IOException("Publication stream exceeds bound");
            if (digest != null) digest.update(buffer, 0, count); output.write(buffer, 0, count);
        }
        return total;
    }
    static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) { copy(input, OutputStream.nullOutputStream(), MAX_BYTES, digest); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void cleanup(Path stage) {
        try (var paths = Files.walk(stage)) {
            var all = paths.toList();
            for (Path path : all) if (Files.isDirectory(path)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            for (Path path : all.reversed()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Remains private and unready; task teardown owns temporary disk. */ }
    }
    private static final class LimitedStream extends FilterInputStream {
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
