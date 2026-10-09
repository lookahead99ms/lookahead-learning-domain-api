package com.lookahead.domain.publication;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PublicationBootstrapTest {
    @TempDir Path directory;
    final JsonMapper mapper = new JsonMapper();
    record Fixture(byte[] archive, byte[] manifest, String release) {}
    Fixture fixture(String badMember, boolean omit, boolean wrongHash) throws Exception {
        byte[] body = "{\"synthetic\":true}".getBytes();
        byte[] publication = mapper.writeValueAsBytes(Map.of("schemaVersion", "content-publication/v1", "version", "fixture-v1", "assets",
                List.of(Map.of("path", "/content/sample.json", "sha256", PublicationBootstrap.sha(body), "mediaType", "application/json", "tier", "public", "scopes", List.of(), "contentIds", List.of()))));
        var files = new TreeMap<String, byte[]>();
        files.put("publication.json", publication); files.put("content/sample.json", body);
        files.put("account-catalog.json", mapper.writeValueAsBytes(Map.of("schemaVersion","account-catalog/v1","catalogVersion","sha256:"+"a".repeat(64),
                "records",List.of(),"algorithmVersions",List.of(),"rankingVersions",List.of())));
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var tar = new TarArchiveOutputStream(gzip)) {
            for (var entry : files.entrySet()) {
                if (omit && entry.getKey().equals("content/sample.json")) continue;
                var member = new TarArchiveEntry(entry.getKey().equals("content/sample.json") && badMember != null ? badMember : entry.getKey());
                member.setSize(entry.getValue().length); tar.putArchiveEntry(member); tar.write(entry.getValue()); tar.closeArchiveEntry();
            }
        }
        byte[] archive = bytes.toByteArray();
        var releaseBytes = new ByteArrayOutputStream(); releaseBytes.write(publication); releaseBytes.write(files.get("account-catalog.json"));
        String release = "release-" + PublicationBootstrap.sha(releaseBytes.toByteArray()).substring(0,24);
        var inventory = new ArrayList<Map<String,Object>>();
        for (var entry : files.entrySet()) inventory.add(Map.of("path", entry.getKey(), "bytes", entry.getValue().length,
                "sha256", wrongHash && entry.getKey().equals("content/sample.json") ? "0".repeat(64) : PublicationBootstrap.sha(entry.getValue())));
        byte[] manifest = mapper.writeValueAsBytes(Map.of("schemaVersion", "lookahead-publication-archive/v1", "releaseId", release,
                "archiveSha256", PublicationBootstrap.sha(archive), "publicationSha256", PublicationBootstrap.sha(publication),
                "trustedCatalogSha256", PublicationBootstrap.sha(files.get("account-catalog.json")), "files", inventory,
                "totalBytes", files.values().stream().mapToLong(value -> value.length).sum()));
        return new Fixture(archive, manifest, release);
    }
    PublicationSettings settings(Fixture f) throws Exception {
        return new PublicationSettings(true,"us-east-2","123456789012","synthetic-publication",f.release(),PublicationBootstrap.sha(f.manifest()),PublicationBootstrap.sha(f.archive()),directory.toRealPath(),1024,4);
    }
    PublicationObjectSource source(Fixture f) { return key -> new ByteArrayInputStream(key.endsWith("manifest.json") ? f.manifest() : f.archive()); }
    @AfterEach void writableForFixtureCleanup() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) if (Files.isDirectory(path)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
    }
    @Test void downloadsOnlyPinnedObjectsAndPublishesReadOnlyCompleteRelease() throws Exception {
        var f = fixture(null,false,false); var keys = new ArrayList<String>();
        var location = new PublicationBootstrap(mapper).load(settings(f), key -> {keys.add(key);return source(f).open(key);});
        assertThat(keys).containsExactly("releases/"+f.release()+"/manifest.json", "releases/"+f.release()+"/publication.tar.gz");
        assertThat(Files.readString(Path.of(location.contentRoot()).resolve("sample.json"))).contains("synthetic");
        assertThat(Files.getPosixFilePermissions(Path.of(location.manifest()))).isEqualTo(PosixFilePermissions.fromString("r--------"));
        assertThat(Path.of(location.accountCatalog())).exists();
        assertThat(new com.lookahead.learning.content.service.ProtectedContentPolicy(mapper,location).find("/content/account-catalog.json")).isEmpty();
    }
    @Test void rejectsTraversalMissingFilesAndChangedMemberBytesWithoutPartialPublication() throws Exception {
        for (var f : List.of(fixture("../escape.json",false,false),fixture(null,true,false),fixture(null,false,true),fixture("/tmp/escape.json",false,false))) {
            assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(settings(f),source(f))).isInstanceOf(IllegalStateException.class);
            try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
        }
    }
    @Test void deniedDownloadOrUnpinnedManifestFailsClosed() throws Exception {
        var f = fixture(null,false,false);
        assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(settings(f), key -> {throw new IOException("denied");})).hasMessage("Selected cloud publication failed startup verification");
        assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(settings(f), key -> new ByteArrayInputStream("{}".getBytes()))).isInstanceOf(IllegalStateException.class);
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void rejectsDuplicateOversizedInventoriesAndLinkEntries() throws Exception {
        var good=fixture(null,false,false);
        for (boolean duplicate : List.of(true,false)) {
            var manifest=(tools.jackson.databind.node.ObjectNode)mapper.readTree(good.manifest());
            var inventory=(tools.jackson.databind.node.ArrayNode)manifest.path("files");
            if(duplicate) inventory.add(inventory.get(0).deepCopy());
            else ((tools.jackson.databind.node.ObjectNode)inventory.get(0)).put("bytes",PublicationBootstrap.MAX_FILE+1L);
            var bad=new Fixture(good.archive(),mapper.writeValueAsBytes(manifest),good.release());
            assertThatThrownBy(()->new PublicationBootstrap(mapper).load(settings(bad),source(bad))).isInstanceOf(IllegalStateException.class);
        }
        for (byte kind : new byte[]{TarConstants.LF_SYMLINK,TarConstants.LF_LINK}) {
            var bytes=new ByteArrayOutputStream();
            try(var gzip=new GZIPOutputStream(bytes);var tar=new TarArchiveOutputStream(gzip)) {
                var member=new TarArchiveEntry("content/sample.json",kind);member.setLinkName("/etc/passwd");
                tar.putArchiveEntry(member);tar.closeArchiveEntry();
            }
            var manifest=(tools.jackson.databind.node.ObjectNode)mapper.readTree(good.manifest());
            manifest.put("archiveSha256",PublicationBootstrap.sha(bytes.toByteArray()));
            var bad=new Fixture(bytes.toByteArray(),mapper.writeValueAsBytes(manifest),good.release());
            assertThatThrownBy(()->new PublicationBootstrap(mapper).load(settings(bad),source(bad))).isInstanceOf(IllegalStateException.class);
        }
        try(var paths=Files.list(directory)){assertThat(paths.toList()).isEmpty();}
    }
    @Test void byteAndEntryBoundsEvictAndReturnedArraysCannotPoisonCache() {
        var cache = new BoundedContentCache(4,2);
        cache.read("a",()->new byte[]{1,2})[0]=9;
        assertThat(cache.read("a",()->{throw new AssertionError();})).containsExactly(1,2);
        cache.read("b",()->new byte[]{3,4}); cache.read("c",()->new byte[]{5,6});
        assertThat(cache.size()).isEqualTo(2); assertThat(cache.bytes()).isEqualTo(4);
        cache.read("large",()->new byte[5]); assertThat(cache.size()).isEqualTo(2);
        var calls = new int[1];cache.read("a",()->{calls[0]++;return new byte[]{7};});assertThat(calls[0]).isEqualTo(1);
        assertThat(cache.bytes()).isLessThanOrEqualTo(4);
    }
    @Test void readsArchiveProducedByInfraWhenExplicitlySupplied() throws Exception {
        String input = System.getProperty("lookahead.publication-fixture");
        org.junit.jupiter.api.Assumptions.assumeTrue(input != null, "Cross-repository fixture is run by verify_aws_publication_contract.py");
        Path root = Path.of(input);
        byte[] archive = Files.readAllBytes(root.resolve("publication.tar.gz"));
        byte[] manifest = Files.readAllBytes(root.resolve("publication.tar.gz.manifest.json"));
        var f = new Fixture(archive, manifest, mapper.readTree(manifest).path("releaseId").asText());
        var location = new PublicationBootstrap(mapper).load(settings(f), source(f));
        var catalog = new com.lookahead.learning.content.validator.SnapshotValidator(mapper, location);
        assertThat(catalog.catalogVersion()).startsWith("sha256:");
        assertThat(new com.lookahead.learning.content.service.ProtectedContentPolicy(mapper,location).read(
                new com.lookahead.learning.content.service.ProtectedContentPolicy(mapper,location).find("/content/sample.json").orElseThrow())).containsExactly("{\"synthetic\":true}".getBytes());
    }
}
