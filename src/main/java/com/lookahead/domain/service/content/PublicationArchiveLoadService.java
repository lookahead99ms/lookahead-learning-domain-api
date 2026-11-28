package com.lookahead.domain.service.content;

import com.lookahead.domain.client.PublicationObjectClient;
import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationSelection;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.validator.ProtectedContentManifestValidator;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Complete hash-pinned extraction into a fresh private directory before bean publication. A release
 * is identified by its Content calendar release tag. A pinned deployment reads live/&lt;tag&gt;/
 * and checks both externally supplied digests. A live deployment lists live/, selects the complete
 * release folder with the highest tag and binds the archive to the digest inside that manifest.
 * Content identity is bound through the manifest's per-file digests and, when the publisher
 * recorded it, {@code contentSha256} over the publication and catalog bytes.
 */
public final class PublicationArchiveLoadService {
  static final long MAX_BYTES = 1024L * 1024 * 1024;
  static final int MAX_FILE = 32 * 1024 * 1024;
  private static final Pattern LIVE_KEY =
      Pattern.compile(
          PublicationSelection.LIVE_PREFIX
              + "("
              + PublicationSelection.RELEASE_TAG
              + ")/(manifest\\.json|publication\\.tar\\.gz)");

  /**
   * Chronological order of calendar tags. The UTC hour prefix is fixed width, so it compares as a
   * string; the counter has no leading zeros past 99, so a longer counter is a later one. This
   * equals plain string order for two-digit counters and stays correct at the 99 to 100 boundary.
   */
  static final Comparator<String> TAG_ORDER =
      (left, right) -> {
        int cutLeft = left.lastIndexOf('.'), cutRight = right.lastIndexOf('.');
        int byHour = left.substring(0, cutLeft).compareTo(right.substring(0, cutRight));
        if (byHour != 0) return byHour;
        String counterLeft = left.substring(cutLeft + 1),
            counterRight = right.substring(cutRight + 1);
        return counterLeft.length() != counterRight.length()
            ? Integer.compare(counterLeft.length(), counterRight.length())
            : counterLeft.compareTo(counterRight);
      };

  private record Entry(long bytes, String sha256) {}

  /**
   * One release to load: its prefix, ID and the digests known before the manifest is read (null
   * when live).
   */
  record Selection(String prefix, String releaseId, String manifestSha256, String archiveSha256) {}

  private final ObjectMapper mapper;
  private final PublicationFileStore files;

  public PublicationArchiveLoadService(ObjectMapper mapper, PublicationFileStore files) {
    this.mapper = mapper;
    this.files = files;
  }

  public PublicationLocation load(PublicationSelection selection, PublicationObjectClient source) {
    PublicationFileStore.Stage stage = null;
    String step = "preparing the private publication folder",
        release = selection.live() ? "live" : selection.releaseId();
    try {
      require(
          !files.symbolicLinkOnPath(selection.directory()),
          "Publication directory cannot contain symlinks");
      stage = files.stage(selection.directory());
      if (selection.live())
        step = "listing live/ in the publication bucket to find the serving release";
      Selection selected =
          selection.live()
              ? discoverLive(source)
              : new Selection(
                  PublicationSelection.LIVE_PREFIX + selection.releaseId() + "/",
                  selection.releaseId(),
                  selection.manifestSha256(),
                  selection.archiveSha256());
      String prefix = selected.prefix();
      release = selected.releaseId();
      step = "reading manifest.json";
      byte[] manifestBytes;
      try (InputStream stream = source.open(prefix + "manifest.json")) {
        manifestBytes = stream.readNBytes(MAX_FILE + 1);
      }
      require(
          manifestBytes.length <= MAX_FILE
              && (selected.manifestSha256() == null
                  || Sha256.hex(manifestBytes).equals(selected.manifestSha256())),
          "Transport manifest digest mismatch");
      step = "checking manifest.json";
      JsonNode manifest = mapper.readTree(manifestBytes);
      String archiveSha256 =
          selected.archiveSha256() == null
              ? manifest.path("archiveSha256").asText()
              : selected.archiveSha256();
      require(
          manifest.path("schemaVersion").asText().equals("lookahead-publication-archive/v1")
              && manifest.path("releaseId").asText().equals(selected.releaseId())
              && archiveSha256.matches("[a-f0-9]{64}")
              && manifest.path("archiveSha256").asText().equals(archiveSha256),
          "Transport contract mismatch");
      Map<String, Entry> expected = inventory(manifest);
      require(
          expected.containsKey("publication.json") && expected.containsKey("account-catalog.json"),
          "Complete release requires publication and trusted account catalog");
      step = "downloading publication.tar.gz";
      try (InputStream stream = source.open(prefix + "publication.tar.gz")) {
        require(
            files.download(stage, "download.tar.gz", stream, MAX_BYTES) <= MAX_BYTES,
            "Compressed archive exceeds bound");
      }
      step = "checking the archive digest";
      require(
          files.archiveSha256(stage, MAX_BYTES).equals(archiveSha256), "Archive digest mismatch");
      step = "extracting the archive";
      Set<String> seen = new HashSet<>();
      // The file store unpacks; every decision about a member is made here, in the order the checks
      // always ran.
      files.unpackGzip(
          stage,
          MAX_BYTES + 64L * 1024 * 1024,
          new PublicationFileStore.MemberChecks() {
            @Override
            public boolean wanted(PublicationFileStore.TarMember member) {
              Entry entry = expected.get(member.name());
              require(
                  member.plainFile()
                      && entry != null
                      && seen.add(member.name())
                      && member.bytes() == entry.bytes(),
                  "Unsafe, duplicate or unlisted archive member");
              return true;
            }

            @Override
            public long placed(PublicationFileStore.TarMember member, boolean insideFolder) {
              require(insideFolder, "Archive traversal");
              return expected.get(member.name()).bytes();
            }

            @Override
            public void written(PublicationFileStore.TarMember member, long bytes) {
              require(bytes == expected.get(member.name()).bytes(), "Archive size mismatch");
            }

            @Override
            public void digested(PublicationFileStore.TarMember member, String sha256) {
              require(
                  sha256.equals(expected.get(member.name()).sha256()),
                  "Archive member digest mismatch");
            }
          });
      require(seen.equals(expected.keySet()), "Incomplete archive");
      step = "checking the publication and the trusted catalog";
      byte[] publicationBytes = files.unpacked(stage, "publication.json");
      byte[] catalogBytes = files.unpacked(stage, "account-catalog.json");
      MessageDigest contentDigest = MessageDigest.getInstance("SHA-256");
      contentDigest.update(publicationBytes);
      contentDigest.update(catalogBytes);
      String recordedContent = manifest.path("contentSha256").asText();
      require(
          Sha256.hex(publicationBytes).equals(manifest.path("publicationSha256").asText())
              && (recordedContent.isEmpty()
                  || recordedContent.equals(HexFormat.of().formatHex(contentDigest.digest()))),
          "Publication identity mismatch");
      require(
          expected
              .get("account-catalog.json")
              .sha256()
              .equals(manifest.path("trustedCatalogSha256").asText()),
          "Trusted catalog digest missing or mismatched");
      JsonNode publication = mapper.readTree(publicationBytes);
      require(
          publication.path("schemaVersion").asText().equals("content-publication/v1")
              && publication.path("assets").isArray(),
          "Publication contract mismatch");
      Set<String> approved = new HashSet<>(Set.of("publication.json", "account-catalog.json"));
      for (JsonNode asset : publication.path("assets")) {
        String path = asset.path("path").asText();
        require(ProtectedContentManifestValidator.validPath(path), "Invalid publication path");
        String name = path.substring(1);
        require(
            approved.add(name)
                && expected.containsKey(name)
                && expected.get(name).sha256().equals(asset.path("sha256").asText()),
            "Publication allowlist differs from archive");
      }
      require(approved.equals(seen), "Unapproved archive inventory");
      require(
          mapper.readTree(catalogBytes).path("schemaVersion").asText().equals("account-catalog/v1"),
          "Invalid trusted catalog");
      step = "locking the extracted files read-only";
      files.seal(stage);
      EventLog.success(EventLog.STARTUP, "publication.load")
          .addKeyValue("release.tag", release)
          .addKeyValue("count", seen.size())
          .log(
              "Content release {} loaded: {} files verified against its manifest ({})",
              release,
              seen.size(),
              selection.live() ? "the highest complete release under live/" : "the pinned release");
      return new PublicationLocation(
          files.location(stage, "publication.json"),
          files.location(stage, "content"),
          files.location(stage, "account-catalog.json"),
          true,
          selection.cacheBytes(),
          selection.cacheEntries());
    } catch (Exception failure) {
      // A partial stage is never exposed. Do not log SDK response bodies or secret material.
      if (stage != null) files.discard(stage);
      refused(release, step, failure);
      throw new IllegalStateException("Selected cloud publication failed startup verification");
    }
  }

  /**
   * DLV-969 (owner, 2026-11-04): why the release could not load, before startup stops: the step,
   * then this class's own check text, or S3's HTTP status and error code with S3's own message
   * (plain logs, owner 2026-11-17: an ERROR line quotes the dependency; an IAM denial names the
   * role it refused, which is what the reader needs).
   */
  private static void refused(String release, String step, Exception failure) {
    boolean ours = failure instanceof Rejected;
    Integer status = EventLog.status(failure);
    String code = EventLog.code(failure);
    String detail =
        ours
            ? failure.getMessage()
            : status != null
                ? "S3 answered HTTP "
                    + status
                    + (code == null ? "" : " " + code)
                    + " ("
                    + EventLog.quoted(failure)
                    + ")"
                : EventLog.quoted(failure);
    var line =
        EventLog.error(
                EventLog.STARTUP,
                "publication.load",
                ours ? "PUBLICATION_INVALID" : "PUBLICATION_UNAVAILABLE")
            .addKeyValue("release.tag", release)
            .addKeyValue("error.type", EventLog.types(failure));
    if (status != null) line = line.addKeyValue("upstream.status", status);
    if (code != null) line = line.addKeyValue("upstream.code", code);
    line.log(
        "Startup refused: content release {} could not be loaded while {}: {}; the app stops before"
            + " it takes traffic",
        release,
        step,
        detail);
  }

  /** A check of this class failed; its text is fixed here and safe to log. */
  static final class Rejected extends IllegalArgumentException {
    Rejected(String message) {
      super(message);
    }
  }

  /**
   * The serving release is the complete folder under live/ with the highest calendar tag. Promotion
   * writes the archive before the manifest and removes the previous folder afterwards, so a folder
   * without a manifest is a copy in progress (ignored) and a second complete folder is a
   * predecessor awaiting removal (lower tag, reported). Object timestamps never decide the order; a
   * listing must still carry them so that a malformed source is rejected.
   */
  static Selection discoverLive(PublicationObjectClient source) throws IOException {
    List<PublicationObjectClient.ListedObject> listing =
        source.list(PublicationSelection.LIVE_PREFIX);
    require(
        listing.size() <= PublicationObjectClient.MAX_LIVE_OBJECTS,
        "Live prefix holds more objects than releases");
    Map<String, Set<String>> folders = new TreeMap<>(TAG_ORDER);
    for (PublicationObjectClient.ListedObject object : listing) {
      Matcher match = LIVE_KEY.matcher(object.key());
      require(
          match.matches() && object.lastModified() != null,
          "Unexpected object under the live prefix");
      require(
          folders.computeIfAbsent(match.group(1), tag -> new HashSet<>()).add(match.group(2)),
          "Duplicate live object");
    }
    List<String> complete = new ArrayList<>();
    for (Map.Entry<String, Set<String>> folder : folders.entrySet())
      if (folder.getValue().contains("manifest.json")) complete.add(folder.getKey());
    require(!complete.isEmpty(), "No live release is published");
    String chosen = complete.getLast();
    require(
        folders.get(chosen).contains("publication.tar.gz"), "Live release is missing its archive");
    // Exception, decided 2026-11-25 (DLV-1016): this WARN line is written on the logger directly,
    // not through an EventLog
    // helper. It is on lookahead.startup and carries no keys. EventLog.failed and EventLog.refused
    // would add the keys
    // event.action, event.outcome and reason; EventLog.warnWithoutStack writes on
    // lookahead.failure, another logger name.
    if (complete.size() > 1)
      EventLog.STARTUP.warn(
          "Live prefix holds {} releases; serving the highest tag {}, promotion cleanup pending",
          complete.size(),
          chosen);
    return new Selection(PublicationSelection.LIVE_PREFIX + chosen + "/", chosen, null, null);
  }

  private Map<String, Entry> inventory(JsonNode manifest) {
    JsonNode files = manifest.path("files");
    require(
        files.isArray() && files.size() >= 2 && files.size() <= 50002, "Invalid archive inventory");
    Map<String, Entry> result = new HashMap<>();
    long total = 0;
    for (JsonNode file : files) {
      String path = file.path("path").asText(), digest = file.path("sha256").asText();
      JsonNode size = file.path("bytes");
      require(
          safePath(path)
              && digest.matches("[a-f0-9]{64}")
              && size.isIntegralNumber()
              && size.canConvertToLong(),
          "Invalid inventory member");
      long bytes = size.asLong();
      require(
          bytes >= 0
              && bytes <= MAX_FILE
              && result.putIfAbsent(path, new Entry(bytes, digest)) == null,
          "Duplicate or oversized member");
      total += bytes;
      require(total <= MAX_BYTES, "Expanded publication exceeds bound");
    }
    require(
        manifest.path("totalBytes").isIntegralNumber()
            && manifest.path("totalBytes").asLong() == total,
        "Invalid inventory total");
    return result;
  }

  /**
   * A file a release may hold: the two fixed top-level files, or a file under {@code content/} that
   * the manifest rules accept and that sits in none of the three folders a release never ships. One
   * rule for the inventory of a pinned release and for the members of a live tar (DLV-1016).
   */
  static boolean safePath(String path) {
    if (Set.of("publication.json", "account-catalog.json").contains(path)) return true;
    return ProtectedContentManifestValidator.validPath("/" + path)
        && Arrays.stream(path.split("/"))
            .noneMatch(Set.of("delivery", "evidence", "access")::contains);
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new Rejected(message);
  }
}
