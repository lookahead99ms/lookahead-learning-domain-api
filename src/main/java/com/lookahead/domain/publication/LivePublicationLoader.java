package com.lookahead.domain.publication;

import com.lookahead.domain.Events;
import com.lookahead.domain.HealthReport.Diagnosis;
import com.lookahead.domain.StartupChecks;
import com.lookahead.learning.content.service.ProtectedContentPolicy;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-971 (owner, 2026-11-06): the approved layout serves the publication from one tar under {@code release/live/}.
 * At start the Domain lists the prefix, expects exactly one {@code v*.tar} (the publication as today: lessons,
 * catalogs, config and {@code publication.json} inside; plain or gzip tar; no manifest beside it), downloads and
 * unpacks it into a fresh private folder and reports the version (the file name) in readiness and one log line. Zero
 * or more than one tar, or a tar that fails its checks: the content stays unloaded, readiness is DOWN with the reason
 * and the task does not restart on its own. Checks inside the tar: every member a regular file on a safe path, no
 * duplicates, {@code publication.json} ({@code content-publication/v1}) and {@code account-catalog.json}
 * ({@code account-catalog/v1}) present, every listed asset present with its recorded digest, nothing unlisted.
 */
public final class LivePublicationLoader {
    public static final String PREFIX_PATTERN = "[a-z0-9][a-z0-9_-]*(?:/[a-z0-9][a-z0-9_-]*)*/";
    static final long MAX_BYTES = PublicationBootstrap.MAX_BYTES;
    static final int MAX_FILE = PublicationBootstrap.MAX_FILE;
    private static final byte[] EMPTY_CATALOG = ("{\"schemaVersion\":\"account-catalog/v1\",\"catalogVersion\":\"sha256:" + "0".repeat(64)
            + "\",\"records\":[],\"algorithmVersions\":[],\"rankingVersions\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public record Loaded(PublicationLocation location, LiveRelease release) {}
    private record Member(long bytes, String sha256) {}

    private final ObjectMapper mapper;
    public LivePublicationLoader(ObjectMapper mapper) { this.mapper = mapper; }

    public Loaded load(PublicationSettings settings, String livePrefix, PublicationObjectSource source) {
        if (!livePrefix.matches(PREFIX_PATTERN))
            throw StartupChecks.refuse("app.publication.live-prefix (LOOKAHEAD_PUBLICATION_LIVE_PREFIX)", "SETTING_INVALID",
                    "is not a key prefix of lowercase letters, digits, dash and underscore ending in /", "Invalid live publication prefix");
        Path stage = null;
        String step = "preparing the private publication folder", version = null;
        try {
            Path parent = settings.directory();
            for (Path cursor = parent; cursor != null; cursor = cursor.getParent())
                require(!Files.isSymbolicLink(cursor), "Publication directory cannot contain symlinks");
            Files.createDirectories(parent);
            stage = Files.createTempDirectory(parent, "publication-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            step = "listing " + livePrefix + " in the assets bucket";
            Selection selected = select(livePrefix, source.list(livePrefix));
            if (selected.problem() != null) return unloaded(settings, stage, livePrefix, null, selected.problem(), selected.problem().reason(), null);
            version = selected.version();
            step = "downloading " + version + ".tar";
            Path archive = stage.resolve("download.tar");
            try (InputStream stream = source.open(selected.key()); OutputStream output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
                copy(stream, output, MAX_BYTES, null);
            }
            step = "extracting " + version + ".tar";
            Path extracted = Files.createDirectory(stage.resolve("release"));
            Map<String, Member> members = extract(archive, extracted);
            step = "checking the publication and the trusted catalog in " + version + ".tar";
            require(members.containsKey("publication.json") && members.containsKey("account-catalog.json"), "Complete release requires publication and trusted account catalog");
            byte[] publicationBytes = Files.readAllBytes(extracted.resolve("publication.json"));
            JsonNode publication = mapper.readTree(publicationBytes);
            require(publication.path("schemaVersion").asText().equals("content-publication/v1") && publication.path("assets").isArray()
                    && publication.path("assets").size() <= 50000, "Publication contract mismatch");
            Set<String> approved = new HashSet<>(Set.of("publication.json", "account-catalog.json"));
            for (JsonNode asset : publication.path("assets")) {
                String path = asset.path("path").asText();
                require(ProtectedContentPolicy.validPath(path), "Invalid publication path");
                String name = path.substring(1);
                Member member = members.get(name);
                require(approved.add(name) && member != null && member.sha256().equals(asset.path("sha256").asText()), "Publication allowlist differs from archive");
            }
            require(approved.equals(members.keySet()), "Unapproved archive inventory");
            require(mapper.readTree(Files.readAllBytes(extracted.resolve("account-catalog.json"))).path("schemaVersion").asText().equals("account-catalog/v1"), "Invalid trusted catalog");
            step = "locking the extracted files read-only";
            Files.delete(archive);
            lock(extracted);
            Events.success(StartupChecks.LOG, "publication.load").addKeyValue("release.tag", version).addKeyValue("count", members.size())
                    .log("Content release {} loaded from {}{}.tar: {} files verified against its publication", version, livePrefix, version, members.size());
            return new Loaded(new PublicationLocation(extracted.resolve("publication.json").toString(), extracted.resolve("content").toString(),
                    extracted.resolve("account-catalog.json").toString(), true, settings.cacheBytes(), settings.cacheEntries()),
                    new LiveRelease(livePrefix, version, null));
        } catch (Exception failure) {
            boolean ours = failure instanceof PublicationBootstrap.Rejected;
            String reason = ours ? "LIVE_RELEASE_INVALID" : "LIVE_RELEASE_UNAVAILABLE";
            Integer status = Events.status(failure);
            String code = Events.code(failure);
            String detail = ours ? failure.getMessage()
                    : status != null ? "S3 answered HTTP " + status + (code == null ? "" : " " + code) + " (" + Events.types(failure) + ")"
                    : Events.types(failure);
            return unloaded(settings, stage, livePrefix, version, new Diagnosis(reason,
                    "the content release " + (version == null ? "under " + livePrefix : version) + " could not be loaded while " + step + ": " + detail), reason, failure);
        }
    }

    /** The one tar under the prefix, or the problem with the listing. */
    record Selection(String key, String version, Diagnosis problem) {}
    static Selection select(String prefix, List<PublicationObjectSource.ListedObject> listing) {
        Pattern tar = Pattern.compile(Pattern.quote(prefix) + "(" + PublicationSettings.RELEASE_TAG + ")\\.tar");
        var tars = new TreeMap<String, String>(PublicationBootstrap.TAG_ORDER);
        var others = new ArrayList<String>();
        for (PublicationObjectSource.ListedObject object : listing) {
            if (object.key().equals(prefix)) continue; // a folder placeholder written by the console
            Matcher match = tar.matcher(object.key());
            if (match.matches()) tars.put(match.group(1), object.key()); else others.add(object.key().substring(prefix.length()));
        }
        if (!others.isEmpty())
            return new Selection(null, null, new Diagnosis("LIVE_RELEASE_UNEXPECTED", others.size() + " object(s) under " + prefix + " that are not a v*.tar (" + String.join(", ", others) + "): remove them, exactly one publication tar is expected"));
        if (tars.isEmpty())
            return new Selection(null, null, new Diagnosis("LIVE_RELEASE_MISSING", "no publication tar under " + prefix + ": exactly one v*.tar is expected; publish a release or copy one from release/archive/"));
        if (tars.size() > 1)
            return new Selection(null, null, new Diagnosis("LIVE_RELEASE_AMBIGUOUS", tars.size() + " publication tars under " + prefix + " (" + String.join(", ", tars.keySet()) + "): exactly one is expected; move the others to release/archive/"));
        var only = tars.firstEntry();
        return new Selection(only.getValue(), only.getKey(), null);
    }

    /** A plain or gzip tar of regular files on safe paths; answers each member's size and digest. */
    private static Map<String, Member> extract(Path archive, Path extracted) throws Exception {
        boolean gzip;
        try (InputStream peek = Files.newInputStream(archive)) { byte[] magic = peek.readNBytes(2); gzip = magic.length == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b; }
        Map<String, Member> members = new HashMap<>();
        long total = 0;
        try (var raw = Files.newInputStream(archive);
             var bounded = new PublicationBootstrap.LimitedStream(gzip ? new GZIPInputStream(raw) : raw, MAX_BYTES + 64L * 1024 * 1024);
             var tar = new TarArchiveInputStream(bounded)) {
            for (var member = tar.getNextEntry(); member != null; member = tar.getNextEntry()) {
                if (member.isDirectory()) continue;
                String name = member.getName();
                require(member.isFile() && !member.isLink() && !member.isSymbolicLink() && !member.isSparse() && safePath(name)
                        && member.getSize() >= 0 && member.getSize() <= MAX_FILE, "Unsafe or oversized archive member");
                total += member.getSize(); require(total <= MAX_BYTES, "Expanded publication exceeds bound");
                Path destination = extracted.resolve(name).normalize();
                require(destination.startsWith(extracted) && !members.containsKey(name), "Archive traversal or duplicate member");
                Files.createDirectories(destination.getParent());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    require(copy(tar, output, member.getSize(), digest) == member.getSize(), "Archive size mismatch");
                }
                members.put(name, new Member(member.getSize(), HexFormat.of().formatHex(digest.digest())));
            }
        }
        require(!members.isEmpty(), "Empty archive");
        return members;
    }

    static boolean safePath(String path) {
        if (Set.of("publication.json", "account-catalog.json").contains(path)) return true;
        return path.startsWith("content/") && ProtectedContentPolicy.validPath("/" + path)
                && Arrays.stream(path.split("/")).noneMatch(Set.of("delivery", "evidence", "access")::contains);
    }

    /**
     * Not loaded: the stage is removed, an empty trusted catalog keeps the account services constructible, the content
     * policy starts disabled and readiness reports {@code problem} until a restart finds one good tar.
     */
    private Loaded unloaded(PublicationSettings settings, Path stage, String prefix, String version, Diagnosis problem, String reason, Exception failure) {
        if (stage != null) cleanup(stage);
        Path catalog;
        try {
            Path fallback = Files.createTempDirectory(settings.directory(), "unloaded-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            catalog = Files.write(fallback.resolve("account-catalog.json"), EMPTY_CATALOG, StandardOpenOption.CREATE_NEW);
            lock(fallback);
        } catch (IOException disk) {
            throw new IllegalStateException("The private publication folder is not writable", disk);
        }
        var line = Events.failed(StartupChecks.LOG, "publication.load", reason).addKeyValue("release.tag", version == null ? "none" : version);
        if (failure != null) line = line.addKeyValue("error.type", Events.types(failure));
        line.log("Content release not loaded: {}; the task stays up with readiness DOWN until the next start finds exactly one good tar under {}", problem.words(), prefix);
        return new Loaded(new PublicationLocation("", "", catalog.toString(), true, settings.cacheBytes(), settings.cacheEntries()),
                new LiveRelease(prefix, version, problem));
    }

    private static void lock(Path folder) throws IOException {
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "r-x------" : "r--------"));
        }
    }

    private static long copy(InputStream input, OutputStream output, long maximum, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long total = 0;
        for (int count; (count = input.read(buffer, 0, (int) Math.min(buffer.length, maximum - total + 1))) != -1;) {
            total += count; if (total > maximum) throw new IOException("Publication stream exceeds bound");
            if (digest != null) digest.update(buffer, 0, count); output.write(buffer, 0, count);
        }
        return total;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new PublicationBootstrap.Rejected(message); }

    private static void cleanup(Path stage) {
        try (var paths = Files.walk(stage)) {
            var all = paths.toList();
            for (Path path : all) if (Files.isDirectory(path)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            for (Path path : all.reversed()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Remains private and unready; task teardown owns temporary disk. */ }
    }
}
