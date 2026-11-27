package com.lookahead.domain.service.content;

import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.validator.ProtectedContentManifestValidator;
import static com.lookahead.domain.service.content.PublicationArchiveLoadService.require;
import static com.lookahead.domain.service.content.PublicationArchiveLoadService.safePath;
import com.lookahead.domain.client.PublicationObjectClient;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationSelection;
import com.lookahead.domain.model.PublicationLiveRelease;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.HealthDiagnosis;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
public final class PublicationLiveLoadService {
    public static final String PREFIX_PATTERN = "[a-z0-9][a-z0-9_-]*(?:/[a-z0-9][a-z0-9_-]*)*/";
    static final long MAX_BYTES = PublicationArchiveLoadService.MAX_BYTES;
    static final int MAX_FILE = PublicationArchiveLoadService.MAX_FILE;
    private static final byte[] EMPTY_CATALOG = ("{\"schemaVersion\":\"account-catalog/v1\",\"catalogVersion\":\"sha256:" + "0".repeat(64)
            + "\",\"records\":[],\"algorithmVersions\":[],\"rankingVersions\":[]}").getBytes(StandardCharsets.UTF_8);

    public record Loaded(PublicationLocation location, PublicationLiveRelease release) {}
    private record Member(long bytes, String sha256) {}

    private final ObjectMapper mapper;
    private final PublicationFileStore files;
    public PublicationLiveLoadService(ObjectMapper mapper, PublicationFileStore files) { this.mapper = mapper; this.files = files; }

    public Loaded load(PublicationSelection selection, String livePrefix, PublicationObjectClient source) {
        if (!livePrefix.matches(PREFIX_PATTERN))
            throw EventLog.startupRefuse("app.publication.live-prefix (LOOKAHEAD_PUBLICATION_LIVE_PREFIX)", "SETTING_INVALID",
                    "is not a key prefix of lowercase letters, digits, dash and underscore ending in /", "Invalid live publication prefix");
        PublicationFileStore.Stage stage = null;
        String step = "preparing the private publication folder", version = null;
        try {
            require(!files.symbolicLinkOnPath(selection.directory()), "Publication directory cannot contain symlinks");
            stage = files.stage(selection.directory());
            step = "listing " + livePrefix + " in the assets bucket";
            Selection selected = select(livePrefix, source.list(livePrefix));
            if (selected.problem() != null) return unloaded(selection, stage, livePrefix, null, selected.problem(), selected.problem().reason(), null);
            version = selected.version();
            step = "downloading " + version + ".tar";
            try (InputStream stream = source.open(selected.key())) { files.download(stage, "download.tar", stream, MAX_BYTES); }
            step = "extracting " + version + ".tar";
            Map<String, Member> members = extract(stage);
            step = "checking the publication and the trusted catalog in " + version + ".tar";
            require(members.containsKey("publication.json") && members.containsKey("account-catalog.json"), "Complete release requires publication and trusted account catalog");
            byte[] publicationBytes = files.unpacked(stage, "publication.json");
            JsonNode publication = mapper.readTree(publicationBytes);
            require(publication.path("schemaVersion").asText().equals("content-publication/v1") && publication.path("assets").isArray()
                    && publication.path("assets").size() <= 50000, "Publication contract mismatch");
            Set<String> approved = new HashSet<>(Set.of("publication.json", "account-catalog.json"));
            for (JsonNode asset : publication.path("assets")) {
                String path = asset.path("path").asText();
                require(ProtectedContentManifestValidator.validPath(path), "Invalid publication path");
                String name = path.substring(1);
                Member member = members.get(name);
                require(approved.add(name) && member != null && member.sha256().equals(asset.path("sha256").asText()), "Publication allowlist differs from archive");
            }
            require(approved.equals(members.keySet()), "Unapproved archive inventory");
            require(mapper.readTree(files.unpacked(stage, "account-catalog.json")).path("schemaVersion").asText().equals("account-catalog/v1"), "Invalid trusted catalog");
            step = "locking the extracted files read-only";
            files.seal(stage);
            EventLog.success(EventLog.STARTUP, "publication.load").addKeyValue("release.tag", version).addKeyValue("count", members.size())
                    .log("Content release {} loaded from {}{}.tar: {} files verified against its publication", version, livePrefix, version, members.size());
            return new Loaded(new PublicationLocation(files.location(stage, "publication.json"), files.location(stage, "content"),
                    files.location(stage, "account-catalog.json"), true, selection.cacheBytes(), selection.cacheEntries()),
                    new PublicationLiveRelease(livePrefix, version, null));
        } catch (Exception failure) {
            boolean ours = failure instanceof PublicationArchiveLoadService.Rejected;
            String reason = ours ? "LIVE_RELEASE_INVALID" : "LIVE_RELEASE_UNAVAILABLE";
            Integer status = EventLog.status(failure);
            String code = EventLog.code(failure);
            String detail = ours ? failure.getMessage()
                    : status != null ? "S3 answered HTTP " + status + (code == null ? "" : " " + code) + " (" + EventLog.types(failure) + ")"
                    : EventLog.types(failure);
            return unloaded(selection, stage, livePrefix, version, new HealthDiagnosis(reason,
                    "the content release " + (version == null ? "under " + livePrefix : version) + " could not be loaded while " + step + ": " + detail), reason, failure);
        }
    }

    /** The one tar under the prefix, or the problem with the listing. */
    record Selection(String key, String version, HealthDiagnosis problem) {}
    static Selection select(String prefix, List<PublicationObjectClient.ListedObject> listing) {
        Pattern tar = Pattern.compile(Pattern.quote(prefix) + "(" + PublicationSelection.RELEASE_TAG + ")\\.tar");
        var tars = new TreeMap<String, String>(PublicationArchiveLoadService.TAG_ORDER);
        var others = new ArrayList<String>();
        for (PublicationObjectClient.ListedObject object : listing) {
            if (object.key().equals(prefix)) continue; // a folder placeholder written by the console
            Matcher match = tar.matcher(object.key());
            if (match.matches()) tars.put(match.group(1), object.key()); else others.add(object.key().substring(prefix.length()));
        }
        if (!others.isEmpty())
            return new Selection(null, null, new HealthDiagnosis("LIVE_RELEASE_UNEXPECTED", others.size() + " object(s) under " + prefix + " that are not a v*.tar (" + String.join(", ", others) + "): remove them, exactly one publication tar is expected"));
        if (tars.isEmpty())
            return new Selection(null, null, new HealthDiagnosis("LIVE_RELEASE_MISSING", "no publication tar under " + prefix + ": exactly one v*.tar is expected; publish a release or copy one from release/archive/"));
        if (tars.size() > 1)
            return new Selection(null, null, new HealthDiagnosis("LIVE_RELEASE_AMBIGUOUS", tars.size() + " publication tars under " + prefix + " (" + String.join(", ", tars.keySet()) + "): exactly one is expected; move the others to release/archive/"));
        var only = tars.firstEntry();
        return new Selection(only.getValue(), only.getKey(), null);
    }

    /** A plain or gzip tar of regular files on safe paths; answers each member's size and digest. */
    private Map<String, Member> extract(PublicationFileStore.Stage stage) throws Exception {
        Map<String, Member> members = new HashMap<>();
        long[] total = {0};
        // The file store unpacks; every decision about a member is made here, in the order the checks always ran.
        files.unpackTar(stage, MAX_BYTES + 64L * 1024 * 1024, new PublicationFileStore.MemberChecks() {
            @Override public boolean wanted(PublicationFileStore.TarMember member) {
                if (member.directory()) return false;
                require(member.plainFile() && safePath(member.name()) && member.bytes() >= 0 && member.bytes() <= MAX_FILE, "Unsafe or oversized archive member");
                total[0] += member.bytes(); require(total[0] <= MAX_BYTES, "Expanded publication exceeds bound");
                return true;
            }
            @Override public long placed(PublicationFileStore.TarMember member, boolean insideFolder) {
                require(insideFolder && !members.containsKey(member.name()), "Archive traversal or duplicate member");
                return member.bytes();
            }
            @Override public void written(PublicationFileStore.TarMember member, long bytes) {
                require(bytes == member.bytes(), "Archive size mismatch");
            }
            @Override public void digested(PublicationFileStore.TarMember member, String sha256) {
                members.put(member.name(), new Member(member.bytes(), sha256));
            }
        });
        require(!members.isEmpty(), "Empty archive");
        return members;
    }

    /**
     * Not loaded: the stage is removed, an empty trusted catalog keeps the account services constructible, the content
     * policy starts disabled and readiness reports {@code problem} until a restart finds one good tar.
     */
    private Loaded unloaded(PublicationSelection selection, PublicationFileStore.Stage stage, String prefix, String version, HealthDiagnosis problem, String reason, Exception failure) {
        if (stage != null) files.discard(stage);
        String catalog;
        try {
            catalog = files.placeholder(selection.directory(), "account-catalog.json", EMPTY_CATALOG);
        } catch (IOException disk) {
            throw new IllegalStateException("The private publication folder is not writable", disk);
        }
        var line = EventLog.failed(EventLog.STARTUP, "publication.load", reason).addKeyValue("release.tag", version == null ? "none" : version);
        if (failure != null) line = line.addKeyValue("error.type", EventLog.types(failure));
        line.log("Content release not loaded: {}; the task stays up with readiness DOWN until the next start finds exactly one good tar under {}", problem.words(), prefix);
        return new Loaded(new PublicationLocation("", "", catalog, true, selection.cacheBytes(), selection.cacheEntries()),
                new PublicationLiveRelease(prefix, version, problem));
    }
}
