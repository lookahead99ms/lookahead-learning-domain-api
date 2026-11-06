package com.lookahead.domain.contract;

import com.lookahead.domain.publication.PublicationBootstrap;
import com.lookahead.domain.publication.PublicationLocation;
import com.lookahead.domain.publication.PublicationObjectSource;
import com.lookahead.domain.publication.PublicationSettings;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DLV-968 (owner decisions 2026-11-05 and 2026-11-06): the Content-owned release-layout contract,
 * {@code docs/contracts/release-layout.json} on Content main, the only home of the file. {@code scripts/fetch-contracts.sh}
 * fetches it into {@code .contracts/} (CI step "Fetch contracts from Content" with {@code CONTENT_READ_TOKEN}; a
 * developer's own {@code gh} login locally); this repository keeps no copy and a missing file fails with that
 * instruction. A minimal release is built from the contract alone
 * (every required key, the optional keys with one value each) and loaded through Domain's real readers:
 * {@link PublicationBootstrap} for the layout, manifest and publication, {@link SnapshotValidator} for the account
 * catalog. The refusals are the readers' own: a missing required key is refused, and what Domain ignores today (an
 * unknown record field, the packager's approvalStatus) is shown to be ignored, so the contract never claims a
 * check Domain does not make. The file is Content's master itself; its producer-only entries (archivePrefix,
 * pinPattern, pinNote, notes) are informational here.
 * No database, no network, no S3: the release lives in memory and is extracted into a temporary folder.
 */
@Tag(ContractFiles.TAG)
class ReleaseLayoutContractTest {
    static final Path CONTRACT = ContractFiles.of("release-layout.json");
    static final String REFUSED = "Selected cloud publication failed startup verification";
    /** Manifest keys the producer's contract lists as optional because its packager writes them; Domain never reads them. */
    static final Set<String> IGNORED_MANIFEST_KEYS = Set.of("approvalStatus");
    static final JsonMapper mapper = JsonMapper.builder().build();
    static JsonNode contract;
    @TempDir Path directory;

    @BeforeAll static void readContract() throws Exception {
        contract = ContractFiles.read(CONTRACT);
        assertThat(contract.path("schema").asString()).isEqualTo("release-layout/v1");
        assertThat(contract.path("owner").asString()).isEqualTo("lookahead99ms/lookahead-learning-content");
        assertThat(contract.path("consumer").asString()).isEqualTo("lookahead99ms/lookahead-learning-domain-api");
    }

    @AfterEach void writableForFixtureCleanup() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) if (Files.isDirectory(path)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
    }

    static List<String> strings(JsonNode array) {
        var list = new ArrayList<String>();
        array.forEach(node -> list.add(node.asString()));
        return list;
    }

    static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }

    /** The contract's required keys and optional keys are exactly the ones the readers name; nothing invented on either side. */
    @Test void contractNamesExactlyWhatDomainReads() {
        JsonNode catalog = contract.path("accountCatalog");
        assertThat(catalog.path("schemaVersion").asString()).isEqualTo("account-catalog/v1");
        assertThat(strings(catalog.path("required"))).containsExactly("schemaVersion", "catalogVersion", "algorithmVersions", "rankingVersions", "records");
        assertThat(strings(catalog.path("optional"))).containsExactly("compatibleTemplateCatalogVersions", "compatibleTemplateRankingVersions");
        assertThat(catalog.path("versionList").path("maxEntries").asInt()).isEqualTo(100);
        assertThat(strings(catalog.path("record").path("required"))).containsExactly("id", "route", "topicIds", "contentType");
        assertThat(strings(catalog.path("record").path("optional"))).containsExactly("aliases");
        JsonNode manifest = contract.path("manifest");
        assertThat(manifest.path("schemaVersion").asString()).isEqualTo("lookahead-publication-archive/v1");
        assertThat(strings(manifest.path("required"))).containsExactly("schemaVersion", "releaseId", "archiveSha256", "publicationSha256", "trustedCatalogSha256", "files", "totalBytes");
        // The optional list may name keys the packager writes and Domain never reads; those are exactly IGNORED_MANIFEST_KEYS.
        var optional = new TreeSet<>(strings(manifest.path("optional")));
        assertThat(optional).contains("contentSha256");
        optional.removeAll(IGNORED_MANIFEST_KEYS);
        assertThat(optional).as("optional manifest keys Domain reads").containsExactly("contentSha256");
        assertThat(strings(manifest.path("fileEntry").path("required"))).containsExactly("path", "sha256", "bytes");
        assertThat(strings(manifest.path("requiredFiles"))).containsExactly("publication.json", "account-catalog.json");
        assertThat(contract.path("publication").path("schemaVersion").asString()).isEqualTo("content-publication/v1");
        assertThat(strings(contract.path("publication").path("required"))).containsExactly("schemaVersion", "assets");
        assertThat(strings(contract.path("layout").path("objects"))).containsExactly("manifest.json", "publication.tar.gz");
        assertThat("v2026.11.05.10.00").matches(contract.path("layout").path("releaseTag").asString());
        assertThat("v2026.11.05.10.100").matches(contract.path("layout").path("releaseTag").asString());
        assertThat("release-abc").doesNotMatch(contract.path("layout").path("releaseTag").asString());
        // Informational entries of the producer's copy: the archive prefix Domain never reads, and the producer's own,
        // stricter pin pattern. The pins this test builds satisfy it, so the minimal release is one the producer would write.
        if (contract.path("layout").has("archivePrefix")) assertThat(contract.path("layout").path("archivePrefix").asString()).isNotEqualTo(contract.path("layout").path("livePrefix").asString());
        if (catalog.has("pinPattern")) {
            String pinPattern = catalog.path("pinPattern").asString();
            ObjectNode built = catalog();
            for (String key : strings(catalog.path("optional"))) for (String pin : strings(built.path(key))) assertThat(pin).as(key).matches(pinPattern);
            assertThat("x".repeat(129)).doesNotMatch(pinPattern);
        }
    }

    /** The packager always writes approvalStatus; Domain never reads it, so any value loads and the location is the same. */
    @Test void approvalStatusIsWrittenByThePackagerAndIgnoredByDomain() throws Exception {
        assertThat(strings(contract.path("manifest").path("optional"))).contains("approvalStatus");
        var standard = release(catalog(), null);
        assertThat(mapper.readTree(standard.manifest()).path("approvalStatus").asString()).isEqualTo("requires-release-review");
        PublicationLocation location = load(standard);
        assertThat(Files.isRegularFile(Path.of(location.manifest()))).isTrue();
        writableForFixtureCleanup();
        var other = release(catalog(), null, Set.of(), "anything-the-packager-may-write-later");
        assertThatCode(() -> load(other)).as("an unknown approvalStatus value is ignored").doesNotThrowAnyException();
    }

    @Test void minimalReleaseBuiltFromTheContractLoadsAndItsCatalogIsAccepted() throws Exception {
        var release = release(catalog(), null);
        PublicationLocation location = load(release);
        assertThat(location.cloud()).isTrue();
        assertThat(Files.isRegularFile(Path.of(location.accountCatalog()))).isTrue();
        assertThat(Files.isRegularFile(Path.of(location.manifest()))).isTrue();
        // The extracted catalog file goes through the same reader the accounts profile uses at startup.
        var validator = new SnapshotValidator(mapper, location.accountCatalog());
        assertThat(validator.catalogVersion()).isEqualTo(catalog().path("catalogVersion").asString());
        assertThat(validator.allTopicIds()).containsExactly("dsa");
        assertThat(validator.metadata().path("schemaVersion").asString()).isEqualTo("account-catalog/v1");
        // The optional keys are read: a template pinned to the listed compatible catalog and ranking versions is accepted,
        // and one pinned to an unlisted version is refused.
        assertThatCode(() -> validator.validateTemplateReferences(template("sha256:" + "b".repeat(64), "rank:v0"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateTemplateReferences(template("sha256:" + "c".repeat(64), "rank:v0"))).hasMessage("Unresolvable template catalog version");
        assertThatThrownBy(() -> validator.validateTemplateReferences(template("sha256:" + "b".repeat(64), "rank/v9"))).hasMessage("Unresolvable template ranking version");
    }

    @Test void catalogMissingAnyRequiredKeyIsRefusedAndMissingOptionalKeysAreNot() throws Exception {
        for (String key : strings(contract.path("accountCatalog").path("required"))) {
            ObjectNode catalog = catalog(); catalog.remove(key);
            assertThatThrownBy(() -> new SnapshotValidator(mapper, catalog)).as("catalog without " + key).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode bare = catalog();
        for (String key : strings(contract.path("accountCatalog").path("optional"))) bare.remove(key);
        assertThatCode(() -> new SnapshotValidator(mapper, bare)).doesNotThrowAnyException();
        // The bootstrap refuses a catalog that is not account-catalog/v1 before any file is served.
        ObjectNode wrong = catalog(); wrong.put("schemaVersion", "account-catalog/v2");
        assertThatThrownBy(() -> load(release(wrong, null))).hasMessage(REFUSED);
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).as("nothing stays behind after a refusal").isEmpty(); }
    }

    /** The optional lists are read when a template pin needs them: a malformed list is refused there, as the reader does today. */
    @Test void optionalVersionListsAreBoundedListsOfStrings() throws Exception {
        ObjectNode pinned = template("sha256:" + "b".repeat(64), "rank:v0");
        for (String key : strings(contract.path("accountCatalog").path("optional"))) {
            ObjectNode tooMany = catalog();
            ArrayNode list = tooMany.putArray(key);
            for (int i = 0; i <= contract.path("accountCatalog").path("versionList").path("maxEntries").asInt(); i++) list.add("version-" + i);
            assertThatThrownBy(() -> new SnapshotValidator(mapper, tooMany).validateTemplateReferences(pinned)).as(key + " over the bound").hasMessage("Invalid array: " + key);
            ObjectNode notAList = catalog(); notAList.put(key, "sha256:" + "b".repeat(64));
            assertThatThrownBy(() -> new SnapshotValidator(mapper, notAList).validateTemplateReferences(pinned)).as(key + " as a string").hasMessage("Invalid array: " + key);
            ObjectNode duplicate = catalog(); duplicate.putArray(key).add("same").add("same");
            assertThatThrownBy(() -> new SnapshotValidator(mapper, duplicate).validateTemplateReferences(pinned)).as(key + " with a duplicate").hasMessage("Duplicate identifier");
        }
    }

    @Test void recordMissingARequiredFieldIsRefusedAndAnUnknownFieldIsIgnoredToday() throws Exception {
        for (String field : strings(contract.path("accountCatalog").path("record").path("required"))) {
            ObjectNode catalog = catalog();
            ((ObjectNode) catalog.path("records").get(0)).remove(field);
            assertThatThrownBy(() -> new SnapshotValidator(mapper, catalog)).as("record without " + field).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode withoutAliases = catalog();
        ((ObjectNode) withoutAliases.path("records").get(0)).remove("aliases");
        assertThatCode(() -> new SnapshotValidator(mapper, withoutAliases)).doesNotThrowAnyException();
        // Domain reads the named fields only; an extra record field is ignored, not refused. The Infra packager is the
        // place that refuses it before publication (the contract's notes say so).
        ObjectNode extra = catalog();
        ((ObjectNode) extra.path("records").get(0)).put("unknownField", "ignored");
        var validator = new SnapshotValidator(mapper, extra);
        assertThat(validator.isAccessible("canonical-1", Set.of("dsa"))).isTrue();
        assertThat(validator.canonicalContentId("alias-1")).isEqualTo("canonical-1");
        assertThatCode(() -> load(release(extra, null))).doesNotThrowAnyException();
    }

    @Test void manifestMissingAnyRequiredKeyIsRefusedAndTheOptionalKeyMayBeAbsent() throws Exception {
        for (String key : strings(contract.path("manifest").path("required"))) {
            var broken = release(catalog(), key);
            assertThatThrownBy(() -> load(broken)).as("manifest without " + key).hasMessage(REFUSED);
        }
        for (String key : strings(contract.path("manifest").path("optional"))) {
            var legacy = release(catalog(), key);
            assertThatCode(() -> load(legacy)).as("manifest without optional " + key).doesNotThrowAnyException();
        }
        // The contract's required files: a manifest that lists neither is refused before the archive is read.
        var requested = new ArrayList<String>();
        var noCatalog = release(catalog(), null, Set.of("account-catalog.json"));
        assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(settings(noCatalog), key -> { requested.add(key); return source(noCatalog).open(key); })).hasMessage(REFUSED);
        assertThat(requested).containsExactly("live/" + noCatalog.release() + "/manifest.json");
    }

    /** A catalog with every required key and each optional key holding one value, built from the contract's key lists. */
    static ObjectNode catalog() {
        ObjectNode catalog = mapper.createObjectNode();
        for (String key : strings(contract.path("accountCatalog").path("required"))) {
            switch (key) {
                case "schemaVersion" -> catalog.put(key, contract.path("accountCatalog").path("schemaVersion").asString());
                case "catalogVersion" -> catalog.put(key, "sha256:" + "a".repeat(64));
                case "algorithmVersions" -> catalog.putArray(key).add("study-schedule/v2");
                case "rankingVersions" -> catalog.putArray(key).add("rank/v1");
                case "records" -> catalog.putArray(key).add(record());
                default -> throw new IllegalStateException("unexpected required catalog key " + key);
            }
        }
        for (String key : strings(contract.path("accountCatalog").path("optional"))) {
            switch (key) {
                case "compatibleTemplateCatalogVersions" -> catalog.putArray(key).add("sha256:" + "b".repeat(64));
                case "compatibleTemplateRankingVersions" -> catalog.putArray(key).add("rank:v0");
                default -> throw new IllegalStateException("unexpected optional catalog key " + key);
            }
        }
        assertThat(catalog.path("catalogVersion").asString()).matches(contract.path("accountCatalog").path("catalogVersion").asString());
        return catalog;
    }

    static ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        var fields = new TreeSet<>(strings(contract.path("accountCatalog").path("record").path("required")));
        fields.addAll(strings(contract.path("accountCatalog").path("record").path("optional")));
        for (String field : fields) {
            switch (field) {
                case "id" -> record.put(field, "canonical-1");
                case "aliases" -> record.putArray(field).add("alias-1");
                case "route" -> record.putArray(field).add("/").add("learn").add("dsa").add("one");
                case "topicIds" -> record.putArray(field).add("dsa");
                case "contentType" -> record.put(field, "dsa-problem");
                default -> throw new IllegalStateException("unexpected record field " + field);
            }
        }
        return record;
    }

    static ObjectNode template(String catalogVersion, String rankingVersion) {
        ObjectNode template = mapper.createObjectNode();
        template.putObject("provenance").put("algorithmVersion", "ready-made-schedule/v1").put("catalogVersion", catalogVersion).put("rankingVersion", rankingVersion);
        template.putArray("topicIds").add("dsa");
        ObjectNode reference = template.putArray("references").addObject();
        reference.put("contentId", "canonical-1").put("topicId", "dsa").put("contentType", "dsa-problem");
        reference.putArray("route").add("/").add("learn").add("dsa").add("one");
        reference.putArray("prerequisiteIds");
        template.putArray("assumedPrerequisiteIds");
        return template;
    }

    record Release(byte[] archive, byte[] manifest, String release) {}

    Release release(ObjectNode catalog, String dropManifestKey) throws Exception { return release(catalog, dropManifestKey, Set.of()); }
    Release release(ObjectNode catalog, String dropManifestKey, Set<String> unlistedFiles) throws Exception { return release(catalog, dropManifestKey, unlistedFiles, "requires-release-review"); }

    /** The archive, publication.json and manifest.json exactly as the contract describes them, from the catalog given. */
    Release release(ObjectNode catalog, String dropManifestKey, Set<String> unlistedFiles, String approvalStatus) throws Exception {
        byte[] body = "{\"synthetic\":true}".getBytes();
        ObjectNode publication = mapper.createObjectNode();
        for (String key : strings(contract.path("publication").path("required"))) {
            switch (key) {
                case "schemaVersion" -> publication.put(key, contract.path("publication").path("schemaVersion").asString());
                case "assets" -> { ObjectNode asset = publication.putArray(key).addObject(); asset.put("path", "/content/sample.json").put("sha256", sha(body)); }
                default -> throw new IllegalStateException("unexpected publication key " + key);
            }
        }
        byte[] publicationBytes = mapper.writeValueAsBytes(publication), catalogBytes = mapper.writeValueAsBytes(catalog);
        var files = new TreeMap<String, byte[]>();
        files.put("publication.json", publicationBytes); files.put("account-catalog.json", catalogBytes); files.put("content/sample.json", body);
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var tar = new TarArchiveOutputStream(gzip)) {
            for (var entry : files.entrySet()) {
                var member = new TarArchiveEntry(entry.getKey());
                member.setSize(entry.getValue().length); tar.putArchiveEntry(member); tar.write(entry.getValue()); tar.closeArchiveEntry();
            }
        }
        byte[] archive = bytes.toByteArray();
        String tag = "v2026.11.05.10.00";
        ObjectNode manifest = mapper.createObjectNode();
        var keys = new ArrayList<>(strings(contract.path("manifest").path("required")));
        keys.addAll(strings(contract.path("manifest").path("optional")));
        for (String key : keys) {
            if (key.equals(dropManifestKey)) continue;
            switch (key) {
                case "schemaVersion" -> manifest.put(key, contract.path("manifest").path("schemaVersion").asString());
                case "releaseId" -> manifest.put(key, tag);
                case "archiveSha256" -> manifest.put(key, sha(archive));
                case "publicationSha256" -> manifest.put(key, sha(publicationBytes));
                case "trustedCatalogSha256" -> manifest.put(key, sha(catalogBytes));
                case "contentSha256" -> { var both = new ByteArrayOutputStream(); both.write(publicationBytes); both.write(catalogBytes); manifest.put(key, sha(both.toByteArray())); }
                case "approvalStatus" -> manifest.put(key, approvalStatus); // written by the packager, never read by Domain
                case "files" -> {
                    ArrayNode inventory = manifest.putArray(key);
                    for (var entry : files.entrySet()) {
                        if (unlistedFiles.contains(entry.getKey())) continue;
                        ObjectNode file = inventory.addObject();
                        for (String field : strings(contract.path("manifest").path("fileEntry").path("required"))) {
                            switch (field) {
                                case "path" -> file.put(field, entry.getKey());
                                case "sha256" -> file.put(field, sha(entry.getValue()));
                                case "bytes" -> file.put(field, entry.getValue().length);
                                default -> throw new IllegalStateException("unexpected file entry field " + field);
                            }
                        }
                    }
                }
                case "totalBytes" -> manifest.put(key, files.entrySet().stream().filter(entry -> !unlistedFiles.contains(entry.getKey())).mapToLong(entry -> entry.getValue().length).sum());
                default -> throw new IllegalStateException("unexpected manifest key " + key);
            }
        }
        return new Release(archive, mapper.writeValueAsBytes(manifest), tag);
    }

    PublicationSettings settings(Release release) throws Exception {
        return new PublicationSettings(true, "us-east-2", "123456789012", "synthetic-publication", release.release(), sha(release.manifest()), sha(release.archive()), directory.toRealPath(), 1024, 4);
    }

    static PublicationObjectSource source(Release release) {
        return key -> new ByteArrayInputStream(key.endsWith("manifest.json") ? release.manifest() : release.archive());
    }

    PublicationLocation load(Release release) throws Exception {
        return new PublicationBootstrap(mapper).load(settings(release), source(release));
    }
}
