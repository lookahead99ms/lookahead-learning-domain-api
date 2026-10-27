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
    /** Content calendar release tags; the fixture never derives a release identity from content bytes. */
    static final String TAG = "v2026.10.27.01.00", LATER_TAG = "v2026.10.27.01.01";
    record Fixture(byte[] archive, byte[] manifest, String release) {}
    Fixture fixture(String badMember, boolean omit, boolean wrongHash) throws Exception { return fixture(badMember, omit, wrongHash, TAG); }
    Fixture fixture(String badMember, boolean omit, boolean wrongHash, String release) throws Exception {
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
        var inventory = new ArrayList<Map<String,Object>>();
        for (var entry : files.entrySet()) inventory.add(Map.of("path", entry.getKey(), "bytes", entry.getValue().length,
                "sha256", wrongHash && entry.getKey().equals("content/sample.json") ? "0".repeat(64) : PublicationBootstrap.sha(entry.getValue())));
        byte[] manifest = mapper.writeValueAsBytes(Map.of("schemaVersion", "lookahead-publication-archive/v1", "releaseId", release,
                "archiveSha256", PublicationBootstrap.sha(archive), "publicationSha256", PublicationBootstrap.sha(publication),
                "contentSha256", contentSha256(publication, files.get("account-catalog.json")),
                "trustedCatalogSha256", PublicationBootstrap.sha(files.get("account-catalog.json")), "files", inventory,
                "totalBytes", files.values().stream().mapToLong(value -> value.length).sum()));
        return new Fixture(archive, manifest, release);
    }
    /** Full content identity as the publisher records it: publication bytes followed by catalog bytes. */
    static String contentSha256(byte[] publication, byte[] catalog) throws Exception {
        var content = new ByteArrayOutputStream(); content.write(publication); content.write(catalog);
        return PublicationBootstrap.sha(content.toByteArray());
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
    PublicationSettings liveSettings() throws Exception {
        return new PublicationSettings(true,"us-east-2","123456789012","synthetic-publication",null,null,null,directory.toRealPath(),1024,4);
    }
    /** A source whose listing and bytes are both scripted; keys are served exactly as listed. */
    PublicationObjectSource liveSource(List<PublicationObjectSource.ListedObject> listing, Map<String,byte[]> objects, List<String> requested) {
        return new PublicationObjectSource() {
            @Override public InputStream open(String key) throws IOException {
                requested.add(key);
                byte[] bytes = objects.get(key);
                if (bytes == null) throw new IOException("absent");
                return new ByteArrayInputStream(bytes);
            }
            @Override public List<ListedObject> list(String prefix) { requested.add("list:" + prefix); return listing; }
        };
    }
    @Test void liveModeServesTheHighestCompleteTagUnderLiveAndNeverReadsReleases() throws Exception {
        var older = fixture(null,false,false);
        var newer = fixture("content/sample.json",false,false); // same bytes, distinct instance, same tag
        var newerPublication = (tools.jackson.databind.node.ObjectNode) mapper.readTree(newer.manifest());
        assertThat(newerPublication.path("releaseId").asText()).isEqualTo(older.release());
        // The later release carries the higher calendar tag; its object timestamps are deliberately older,
        // so a selection by time would pick the wrong folder.
        var distinct = distinctFixture();
        assertThat(PublicationBootstrap.TAG_ORDER.compare(distinct.release(), older.release())).isPositive();
        var t0 = java.time.Instant.parse("2026-10-26T10:00:00Z"); var earlier = t0.minusSeconds(600);
        var listing = List.of(
            new PublicationObjectSource.ListedObject("live/"+older.release()+"/publication.tar.gz", t0),
            new PublicationObjectSource.ListedObject("live/"+older.release()+"/manifest.json", t0),
            new PublicationObjectSource.ListedObject("live/"+distinct.release()+"/publication.tar.gz", earlier),
            new PublicationObjectSource.ListedObject("live/"+distinct.release()+"/manifest.json", earlier));
        var objects = Map.of("live/"+older.release()+"/publication.tar.gz", older.archive(), "live/"+older.release()+"/manifest.json", older.manifest(),
            "live/"+distinct.release()+"/publication.tar.gz", distinct.archive(), "live/"+distinct.release()+"/manifest.json", distinct.manifest());
        var requested = new ArrayList<String>();
        var location = new PublicationBootstrap(mapper).load(liveSettings(), liveSource(listing, objects, requested));
        assertThat(requested).containsExactly("list:live/", "live/"+distinct.release()+"/manifest.json", "live/"+distinct.release()+"/publication.tar.gz");
        assertThat(Files.readString(Path.of(location.contentRoot()).resolve("second.json"))).contains("second");
        assertThat(location.cloud()).isTrue();
        // Ordering of the listing does not matter; the highest tag wins even when listed first.
        var reversed = new ArrayList<>(listing); Collections.reverse(reversed);
        writableForFixtureCleanup();
        var again = new ArrayList<String>();
        new PublicationBootstrap(mapper).load(liveSettings(), liveSource(reversed, objects, again));
        assertThat(again.get(1)).isEqualTo("live/"+distinct.release()+"/manifest.json");
    }
    @Test void liveSelectionOrdersTagsChronologicallyAcrossTheCounterWidthBoundary() throws Exception {
        // Counter 100 follows 99 within the same hour; the next hour and the next day follow any counter;
        // a tag whose manifest is still being copied (no manifest) is ignored even when it is the highest.
        var t0 = java.time.Instant.parse("2026-10-26T10:00:00Z");
        var hour99 = "v2026.10.27.01.99"; var hour100 = "v2026.10.27.01.100"; var nextHour = "v2026.10.27.02.00"; var nextDay = "v2026.10.28.00.00";
        assertThat(PublicationBootstrap.TAG_ORDER.compare(hour100, hour99)).isPositive();
        assertThat(hour100.compareTo(hour99)).isNegative(); // plain string order would get this wrong
        assertThat(PublicationBootstrap.TAG_ORDER.compare(nextHour, hour100)).isPositive();
        assertThat(PublicationBootstrap.TAG_ORDER.compare(nextDay, nextHour)).isPositive();
        assertThat(PublicationBootstrap.TAG_ORDER.compare(hour99, hour99)).isZero();
        var listing = new ArrayList<PublicationObjectSource.ListedObject>();
        for (String tag : List.of(hour99, hour100)) {
            listing.add(new PublicationObjectSource.ListedObject("live/"+tag+"/publication.tar.gz", t0));
            listing.add(new PublicationObjectSource.ListedObject("live/"+tag+"/manifest.json", t0));
        }
        listing.add(new PublicationObjectSource.ListedObject("live/"+nextDay+"/publication.tar.gz", t0.plusSeconds(60)));
        var requested = new ArrayList<String>();
        var selected = PublicationBootstrap.discoverLive(liveSource(listing, Map.of(), requested));
        assertThat(selected.releaseId()).isEqualTo(hour100);
        assertThat(selected.prefix()).isEqualTo("live/"+hour100+"/");
        assertThat(selected.manifestSha256()).isNull(); assertThat(selected.archiveSha256()).isNull();
        // The highest complete tag without its archive fails rather than falling back to a lower tag.
        listing.add(new PublicationObjectSource.ListedObject("live/"+nextDay+"/manifest.json", t0.plusSeconds(60)));
        listing.remove(new PublicationObjectSource.ListedObject("live/"+nextDay+"/publication.tar.gz", t0.plusSeconds(60)));
        assertThatThrownBy(() -> PublicationBootstrap.discoverLive(liveSource(listing, Map.of(), requested)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Live release is missing its archive");
    }
    @Test void contentIdentityIsBoundWhenRecordedAndLegacyManifestsWithoutItStillStart() throws Exception {
        var good = fixture(null,false,false);
        var manifest = (tools.jackson.databind.node.ObjectNode) mapper.readTree(good.manifest());
        assertThat(manifest.path("contentSha256").asText()).matches("[a-f0-9]{64}").isNotEqualTo(manifest.path("publicationSha256").asText());
        // A recorded identity that does not match the publication and catalog bytes fails closed.
        var tampered = manifest.deepCopy(); tampered.put("contentSha256", manifest.path("publicationSha256").asText());
        var wrong = new Fixture(good.archive(), mapper.writeValueAsBytes(tampered), good.release());
        assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(settings(wrong), source(wrong))).hasMessage("Selected cloud publication failed startup verification");
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
        // A manifest naming a different calendar tag than the pin (or the live folder) is rejected.
        var renamed = manifest.deepCopy(); renamed.put("releaseId", LATER_TAG);
        var mismatched = new Fixture(good.archive(), mapper.writeValueAsBytes(renamed), good.release());
        assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(settings(mismatched), source(mismatched))).isInstanceOf(IllegalStateException.class);
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
        // A publisher that has not recorded contentSha256 yet is accepted: every byte is still bound by the inventory digests.
        var legacy = manifest.deepCopy(); legacy.remove("contentSha256");
        var unrecorded = new Fixture(good.archive(), mapper.writeValueAsBytes(legacy), good.release());
        var location = new PublicationBootstrap(mapper).load(settings(unrecorded), source(unrecorded));
        assertThat(location.cloud()).isTrue();
        assertThat(Files.readString(Path.of(location.contentRoot()).resolve("sample.json"))).contains("synthetic");
    }
    @Test void liveModeFailsClosedOnEmptyForeignIncompleteOrMismatchedLivePrefix() throws Exception {
        var f = fixture(null,false,false);
        var t0 = java.time.Instant.parse("2026-10-26T10:00:00Z");
        var both = Map.of("live/"+f.release()+"/publication.tar.gz", f.archive(), "live/"+f.release()+"/manifest.json", f.manifest());
        var manifestOnly = List.of(new PublicationObjectSource.ListedObject("live/"+f.release()+"/manifest.json", t0));
        var foreign = List.of(new PublicationObjectSource.ListedObject("live/"+f.release()+"/manifest.json", t0),
            new PublicationObjectSource.ListedObject("live/"+f.release()+"/publication.tar.gz", t0),
            new PublicationObjectSource.ListedObject("live/notes.txt", t0));
        var duplicate = List.of(new PublicationObjectSource.ListedObject("live/"+f.release()+"/manifest.json", t0),
            new PublicationObjectSource.ListedObject("live/"+f.release()+"/manifest.json", t0));
        var noInstant = List.of(new PublicationObjectSource.ListedObject("live/"+f.release()+"/manifest.json", null));
        var archiveOnly = List.of(new PublicationObjectSource.ListedObject("live/"+f.release()+"/publication.tar.gz", t0));
        String otherId = LATER_TAG;
        var wrongFolder = List.of(new PublicationObjectSource.ListedObject("live/"+otherId+"/manifest.json", t0),
            new PublicationObjectSource.ListedObject("live/"+otherId+"/publication.tar.gz", t0));
        var wrongObjects = Map.of("live/"+otherId+"/manifest.json", f.manifest(), "live/"+otherId+"/publication.tar.gz", f.archive());
        // The retired hash-shaped folder name is now a foreign key under live/.
        var hashFolder = List.of(new PublicationObjectSource.ListedObject("live/release-"+"f".repeat(24)+"/manifest.json", t0),
            new PublicationObjectSource.ListedObject("live/release-"+"f".repeat(24)+"/publication.tar.gz", t0));
        var tooMany = new ArrayList<PublicationObjectSource.ListedObject>();
        for (int i = 0; i <= PublicationBootstrap.MAX_LIVE_OBJECTS; i++) tooMany.add(new PublicationObjectSource.ListedObject("live/"+f.release()+"/manifest.json", t0));
        record Case(List<PublicationObjectSource.ListedObject> listing, Map<String,byte[]> objects) {}
        for (var scenario : List.of(new Case(List.of(), both), new Case(manifestOnly, both), new Case(foreign, both), new Case(duplicate, both),
                new Case(noInstant, both), new Case(archiveOnly, both), new Case(wrongFolder, wrongObjects), new Case(hashFolder, both), new Case(tooMany, both))) {
            var requested = new ArrayList<String>();
            assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(liveSettings(), liveSource(scenario.listing(), scenario.objects(), requested)))
                .hasMessage("Selected cloud publication failed startup verification");
            try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
        }
        // A pinned deployment never lists, and a source without listing support cannot serve live.
        var pinnedRequests = new ArrayList<String>();
        new PublicationBootstrap(mapper).load(settings(f), key -> { pinnedRequests.add(key); return source(f).open(key); });
        assertThat(pinnedRequests).noneMatch(key -> key.startsWith("list:") || key.startsWith("live/"));
        assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(liveSettings(), source(f))).isInstanceOf(IllegalStateException.class);
    }
    Fixture distinctFixture() throws Exception { return distinctFixture(LATER_TAG); }
    Fixture distinctFixture(String release) throws Exception {
        byte[] body = "{\"synthetic\":true}".getBytes(); byte[] second = "{\"second\":true}".getBytes();
        byte[] publication = mapper.writeValueAsBytes(Map.of("schemaVersion", "content-publication/v1", "version", "fixture-v2", "assets",
                List.of(Map.of("path", "/content/sample.json", "sha256", PublicationBootstrap.sha(body), "mediaType", "application/json", "tier", "public", "scopes", List.of(), "contentIds", List.of()),
                        Map.of("path", "/content/second.json", "sha256", PublicationBootstrap.sha(second), "mediaType", "application/json", "tier", "public", "scopes", List.of(), "contentIds", List.of()))));
        var files = new TreeMap<String, byte[]>();
        files.put("publication.json", publication); files.put("content/sample.json", body); files.put("content/second.json", second);
        files.put("account-catalog.json", mapper.writeValueAsBytes(Map.of("schemaVersion","account-catalog/v1","catalogVersion","sha256:"+"b".repeat(64),
                "records",List.of(),"algorithmVersions",List.of(),"rankingVersions",List.of())));
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var tar = new TarArchiveOutputStream(gzip)) {
            for (var entry : files.entrySet()) {
                var member = new TarArchiveEntry(entry.getKey());
                member.setSize(entry.getValue().length); tar.putArchiveEntry(member); tar.write(entry.getValue()); tar.closeArchiveEntry();
            }
        }
        byte[] archive = bytes.toByteArray();
        var inventory = new ArrayList<Map<String,Object>>();
        for (var entry : files.entrySet()) inventory.add(Map.of("path", entry.getKey(), "bytes", entry.getValue().length, "sha256", PublicationBootstrap.sha(entry.getValue())));
        byte[] manifest = mapper.writeValueAsBytes(Map.of("schemaVersion", "lookahead-publication-archive/v1", "releaseId", release,
                "archiveSha256", PublicationBootstrap.sha(archive), "publicationSha256", PublicationBootstrap.sha(publication),
                "contentSha256", contentSha256(publication, files.get("account-catalog.json")),
                "trustedCatalogSha256", PublicationBootstrap.sha(files.get("account-catalog.json")), "files", inventory,
                "totalBytes", files.values().stream().mapToLong(value -> value.length).sum()));
        return new Fixture(archive, manifest, release);
    }
    @Test void contentOnlyReleaseCannotStartWithoutTrustedCatalog() throws Exception {
        var good = fixture(null, false, false);
        var manifest = (tools.jackson.databind.node.ObjectNode) mapper.readTree(good.manifest());
        var files = (tools.jackson.databind.node.ArrayNode) manifest.path("files");
        files.remove(0); // sorted inventory starts with account-catalog.json
        manifest.remove("trustedCatalogSha256");
        var incomplete = new Fixture(good.archive(), mapper.writeValueAsBytes(manifest), good.release());
        var requested = new ArrayList<String>();
        assertThatThrownBy(() -> new PublicationBootstrap(mapper).load(settings(incomplete), key -> {
            requested.add(key); return source(incomplete).open(key);
        })).isInstanceOf(IllegalStateException.class);
        assertThat(requested).hasSize(1);
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }
    @Test void startsContentContextAndServesRealReleaseWithoutNetwork() throws Exception {
        String input = System.getProperty("lookahead.content-release-fixture");
        org.junit.jupiter.api.Assumptions.assumeTrue(input != null, "Explicit local Content release required");
        Path root = Path.of(input);
        byte[] archive = Files.readAllBytes(root.resolve("publication.tar.gz"));
        byte[] manifest = Files.readAllBytes(root.resolve("publication.tar.gz.manifest.json"));
        var f = new Fixture(archive, manifest, mapper.readTree(manifest).path("releaseId").asText());
        var selected = settings(f);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(PublicationLocation.class, () -> new PublicationBootstrap(mapper).load(selected, source(f)))
            .run(context -> {
                assertThat(context).hasNotFailed();
                var location = context.getBean(PublicationLocation.class);
                var policy = new com.lookahead.learning.content.service.ProtectedContentPolicy(mapper, location);
                var catalog = new com.lookahead.learning.content.validator.SnapshotValidator(mapper, location);
                assertThat(catalog.allTopicIds()).isNotEmpty();
                assertThat(new PublicationConfiguration().contentPublication(location, policy, catalog).health().getStatus().getCode()).isEqualTo("UP");
                var service = new com.lookahead.learning.content.service.ProtectedContentService(policy, null);
                var publication = mapper.readTree(Files.readAllBytes(Path.of(location.manifest())));
                String publicPath = null, protectedPath = null;
                for (var asset : publication.path("assets")) {
                    if (asset.path("tier").asText().equals("public")) publicPath = asset.path("path").asText();
                    else protectedPath = asset.path("path").asText();
                }
                assertThat(publicPath).isNotNull();
                var controller = new com.lookahead.learning.content.controller.ProtectedContentController(service);
                var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
                var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(publicPath))
                    .andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getContentAsByteArray()).isEqualTo(service.read(publicPath, null).bytes()).isNotEmpty();
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, private");
                String denied = protectedPath;
                assertThat(denied).isNotNull();
                assertThatThrownBy(() -> service.read(denied, null)).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
                assertThatThrownBy(() -> service.read("/content/account-catalog.json", null)).isInstanceOf(com.lookahead.learning.content.exception.AccountFailure.class);
                assertThat(Path.of(location.contentRoot()).getParent().getParent().resolve("download.tar.gz")).doesNotExist();
            });
    }
}
