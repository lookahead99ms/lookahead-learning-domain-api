package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real file allowlist, byte hashes, catalog and mapping; account reads alone are mocked. */
class ReadyMadeAdoptionPreparationTest {
    private static final UUID OWNER = new UUID(0, 71);
    private static final UUID OTHER_OWNER = new UUID(0, 72);
    private static final String PUBLICATION_VERSION = "synthetic-adoption-publication-v1";
    @TempDir Path root;
    private final JsonMapper mapper = new JsonMapper();
    private final AccountRepository accounts = mock(AccountRepository.class,
            withSettings().mockMaker(MockMakers.SUBCLASS));
    private ObjectNode template;
    private SnapshotValidator catalog;
    private ReadyMadeAdoptionPreparation service;
    private Set<String> allTopics;
    private String templateId;
    private String templatePath;
    private String expectedHash;
    private Path templateFile;

    @BeforeEach void fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/study-plans/synthetic-adoption-template.json")) {
            assertNotNull(stream, "The redistributable synthetic mapper fixture is required");
            template = (ObjectNode) mapper.readTree(stream.readAllBytes());
        }
        templateId = template.path("templateId").asText();
        templatePath = "/content/study-plans/templates/" + templateId + ".json";
        templateFile = root.resolve(templatePath.substring("/content/".length()));
        allTopics = new TreeSet<>();
        for (JsonNode topic : template.path("topicIds")) allTopics.add(topic.asText());
        assertTrue(allTopics.size() >= 2, "Coverage tests need multiple actual courses");
        catalog = new SnapshotValidator(mapper, trustedCatalog(template));
        when(accounts.isEnabled(OWNER)).thenReturn(true);
        when(accounts.findTopicGrants(OWNER)).thenReturn(allTopics);
        publish(template, allTopics, "pro", "all", "application/json");
    }

    @Test void completePreparationIsOwnerBoundAndReadsOnlyCurrentAccountState() {
        var prepared = prepare();
        assertEquals(OWNER, prepared.accountId());
        assertEquals(templatePath, prepared.templatePath());
        assertEquals(expectedHash, prepared.templateSha256());
        assertEquals(PUBLICATION_VERSION, prepared.publicationVersion());
        assertTrue(prepared.coverage().complete());
        assertEquals(List.copyOf(new TreeSet<>(allTopics)), prepared.coverage().requiredCourseIds());
        assertEquals(prepared.coverage().requiredCourseIds(), prepared.coverage().coveredCourseIds());
        assertTrue(prepared.coverage().missingCourseIds().isEmpty());
        assertTrue(prepared.coverage().missingContentIds().isEmpty());
        assertNotNull(prepared.mapping());
        assertEquals(allTopics, prepared.mapping().topicIds());
        verify(accounts).isEnabled(OWNER);
        verify(accounts).findTopicGrants(OWNER);
        verifyNoMoreInteractions(accounts);
    }

    @Test void anotherOwnerCannotReuseTheFirstOwnersCoverage() {
        assertNotNull(prepare().mapping());
        when(accounts.isEnabled(OTHER_OWNER)).thenReturn(true);
        when(accounts.findTopicGrants(OTHER_OWNER)).thenReturn(Set.of());
        var other = service.prepare(OTHER_OWNER, templateId, expectedHash);
        assertEquals(OTHER_OWNER, other.accountId());
        assertEquals(List.copyOf(new TreeSet<>(allTopics)), other.coverage().missingCourseIds());
        assertNull(other.mapping());
        verify(accounts).findTopicGrants(OTHER_OWNER);
    }

    @Test void partialCourseAccessReportsExactMissingCoverageAndWithholdsTheDraft() {
        String covered = new TreeSet<>(allTopics).first();
        when(accounts.findTopicGrants(OWNER)).thenReturn(Set.of(covered, "grow:unrelated-course"));
        var prepared = prepare();
        Set<String> missing = new TreeSet<>(allTopics);
        missing.remove(covered);
        assertFalse(prepared.coverage().complete());
        assertEquals(List.of(covered), prepared.coverage().coveredCourseIds());
        assertEquals(List.copyOf(missing), prepared.coverage().missingCourseIds());
        assertEquals(contentIdsFor(missing), prepared.coverage().missingContentIds());
        assertNull(prepared.mapping());
    }

    @Test void individualContentGrantsNeverReplaceCompleteCourseCoverage() {
        Set<String> individualGrants = new TreeSet<>();
        for (String id : contentIdsFor(allTopics)) individualGrants.add("content:" + id);
        when(accounts.findTopicGrants(OWNER)).thenReturn(individualGrants);
        var prepared = prepare();
        assertTrue(prepared.coverage().coveredCourseIds().isEmpty());
        assertEquals(List.copyOf(new TreeSet<>(allTopics)), prepared.coverage().missingCourseIds());
        assertEquals(contentIdsFor(allTopics), prepared.coverage().missingContentIds());
        assertNull(prepared.mapping());
    }

    @Test void noAccessReturnsEveryRequiredContentIdWithoutTruncatingOrCreatingADraft() {
        when(accounts.findTopicGrants(OWNER)).thenReturn(Set.of());
        var prepared = prepare();
        assertEquals(contentIdsFor(allTopics), prepared.coverage().missingContentIds());
        assertEquals(List.copyOf(new TreeSet<>(allTopics)), prepared.coverage().missingCourseIds());
        assertNull(prepared.mapping());
        assertThrows(UnsupportedOperationException.class, () -> prepared.coverage().missingCourseIds().clear());
    }

    @Test void revokedGrantIsRecheckedOnTheNextPreparation() {
        when(accounts.findTopicGrants(OWNER)).thenReturn(allTopics, Set.of());
        assertTrue(prepare().coverage().complete());
        var revoked = prepare();
        assertFalse(revoked.coverage().complete());
        assertNull(revoked.mapping());
        verify(accounts, times(2)).findTopicGrants(OWNER);
    }

    @Test void disabledAccountFailsBeforePublicationOrGrantReads() {
        when(accounts.isEnabled(OWNER)).thenReturn(false);
        failure(401, "AUTHENTICATION_REQUIRED", this::prepare);
        verify(accounts).isEnabled(OWNER);
        verifyNoMoreInteractions(accounts);
    }

    @Test void changedExpectedPublicationHashRequiresAReviewBeforeGrantReads() {
        String changedHash = expectedHash.equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64);
        failure(409, "TEMPLATE_CHANGED", () -> service.prepare(OWNER, templateId, changedHash));
        verify(accounts).isEnabled(OWNER);
        verifyNoMoreInteractions(accounts);
    }

    @Test void changedFileBytesFailTheActualPublicationHashCheck() throws Exception {
        Files.writeString(templateFile, "{\"changed\":true}", StandardCharsets.UTF_8);
        failure(503, "CONTENT_UNAVAILABLE", this::prepare);
        verify(accounts, never()).findTopicGrants(any());
    }

    @Test void anUnlistedTemplateIsNotPreparedEvenWhenAFileExists() throws Exception {
        String unlistedId = "unlisted-synthetic-d7-h1";
        Files.write(root.resolve("study-plans/templates/" + unlistedId + ".json"),
                mapper.writeValueAsBytes(template));
        failure(404, "CONTENT_NOT_FOUND", () -> service.prepare(OWNER, unlistedId, expectedHash));
        verify(accounts, never()).findTopicGrants(any());
    }

    @Test void optionalDisabledPublicationHasNoAdoptionSource() {
        var disabled = new ReadyMadeAdoptionPreparation(new ProtectedContentPolicy(mapper, "", ""),
                accounts, catalog, mapper);
        failure(404, "CONTENT_NOT_FOUND", () -> disabled.prepare(OWNER, templateId, expectedHash));
    }

    @Test void invalidIdentityOrHashCannotBecomeAFileLookup() {
        for (String invalid : List.of("../" + templateId, "UPPER-d7-h1", "missing-duration", "a".repeat(181)))
            assertThrows(IllegalArgumentException.class, () -> service.prepare(OWNER, invalid, expectedHash));
        for (String invalid : List.of("sha256:" + expectedHash, "A".repeat(64), "short"))
            assertThrows(IllegalArgumentException.class, () -> service.prepare(OWNER, templateId, invalid));
        assertThrows(NullPointerException.class, () -> service.prepare(null, templateId, expectedHash));
        verifyNoInteractions(accounts);
    }

    @Test void trustedCatalogRejectsAnAlteredRouteWithoutLeakingSourceData() throws Exception {
        assertInvalidCatalogTemplate(copy -> ((ObjectNode) copy.path("references").get(0)).putArray("route")
                .add("https://untrusted.invalid/private-route"));
        verify(accounts, never()).findTopicGrants(any());
    }

    @Test void staleCatalogAndUnknownRankingPinsAreRejected() throws Exception {
        assertInvalidCatalogTemplate(copy -> ((ObjectNode) copy.path("provenance"))
                .put("catalogVersion", "sha256:" + "f".repeat(64)));
        assertInvalidCatalogTemplate(copy -> ((ObjectNode) copy.path("provenance"))
                .put("rankingVersion", "unreleased-synthetic-ranking"));
    }

    @Test void contentTypeAndCanonicalIdentityMustAgreeWithTheTrustedCatalog() throws Exception {
        assertInvalidCatalogTemplate(copy -> ((ObjectNode) copy.path("references").get(0))
                .put("contentType", "untrusted-type"));
        assertInvalidCatalogTemplate(copy -> ((ObjectNode) copy.path("references").get(0))
                .put("contentId", "unpublished-canonical-content"));
    }

    @Test void declaredPublicationScopesMustExactlyMatchActualTemplateCourses() throws Exception {
        Set<String> extra = new TreeSet<>(allTopics);
        extra.add("grow:unrelated-course");
        publish(template, extra, "pro", "all", "application/json");
        failure(503, "TEMPLATE_UNAVAILABLE", this::prepare);
        publish(template, Set.of(new TreeSet<>(allTopics).first()), "pro", "all", "application/json");
        failure(503, "TEMPLATE_UNAVAILABLE", this::prepare);
        verify(accounts, never()).findTopicGrants(any());
    }

    @Test void adoptionRequiresProJsonAndAllScopePublicationMetadata() throws Exception {
        publish(template, allTopics, "public", "all", "application/json");
        failure(503, "TEMPLATE_UNAVAILABLE", this::prepare);
        publish(template, allTopics, "pro", "any", "application/json");
        failure(503, "TEMPLATE_UNAVAILABLE", this::prepare);
        publish(template, allTopics, "pro", "all", "text/html");
        failure(503, "TEMPLATE_UNAVAILABLE", this::prepare);
        verify(accounts, never()).findTopicGrants(any());
    }

    @Test void allowlistedFileCannotPretendToBeADifferentTemplate() throws Exception {
        assertInvalidTemplate(copy -> copy.put("templateId", "another-synthetic-d7-h1"));
    }

    @Test void unknownPrerequisitesAndUnrepresentedTopicsAreRejectedByTheTrustedCatalog() throws Exception {
        assertInvalidCatalogTemplate(copy -> ((ObjectNode) copy.path("references").get(0))
                .putArray("prerequisiteIds").add("unknown-prerequisite"));
        assertInvalidCatalogTemplate(copy -> copy.putArray("assumedPrerequisiteIds").add("unknown-assumed-prerequisite"));
        assertInvalidCatalogTemplate(copy -> copy.withArray("topicIds").add("grow:unknown-topic"));
    }

    @Test void readOnlyPreparationDoesNotAdvertiseAnUnimplementedWriteContract() {
        JsonNode metadataBefore = catalog.metadata();
        assertNotNull(prepare().mapping());
        assertEquals(metadataBefore, catalog.metadata());
        assertFalse(catalog.metadata().toString().contains("ready-made-template-v1"));
        assertFalse(catalog.metadata().toString().contains("ready-made-schedule/v1"));
        verify(accounts).isEnabled(OWNER);
        verify(accounts).findTopicGrants(OWNER);
        verifyNoMoreInteractions(accounts);
    }

    @Test void preparedTemplateCannotBypassPolicyThroughAnOrdinarySnapshotWrite() {
        var mapping = prepare().mapping();
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(mapping.snapshot(), mapping.provenance(), false, allTopics));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(mapping.snapshot(), mapping.provenance(), true, allTopics));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validateExistingVersion(mapping.snapshot(), mapping.provenance(), allTopics));
    }

    private ReadyMadeAdoptionPreparation.Prepared prepare() {
        return service.prepare(OWNER, templateId, expectedHash);
    }

    private void assertInvalidTemplate(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode altered = template.deepCopy();
        mutation.accept(altered);
        publish(altered, allTopics, "pro", "all", "application/json");
        var failure = failure(503, "TEMPLATE_UNAVAILABLE", this::prepare);
        assertEquals("This template is temporarily unavailable.", failure.getMessage());
    }

    private void assertInvalidCatalogTemplate(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode altered = template.deepCopy();
        mutation.accept(altered);
        // Exercise the trusted-catalog decision directly so an invalid template digest cannot mask it.
        assertThrows(IllegalArgumentException.class, () -> catalog.validateTemplateReferences(altered));
        assertInvalidTemplate(mutation);
    }

    private AccountFailure failure(int status, String code, Runnable action) {
        AccountFailure failure = assertThrows(AccountFailure.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }

    private List<String> contentIdsFor(Set<String> topics) {
        Set<String> ids = new TreeSet<>();
        for (JsonNode reference : template.path("references"))
            if (topics.contains(reference.path("topicId").asText())) ids.add(reference.path("contentId").asText());
        return List.copyOf(ids);
    }

    private JsonNode trustedCatalog(JsonNode source) {
        ObjectNode trusted = mapper.createObjectNode().put("schemaVersion", "account-catalog/v1")
                .put("catalogVersion", source.path("provenance").path("catalogVersion").asText());
        trusted.putArray("algorithmVersions").add("study-schedule/v2").add("interview-sprint/v1");
        var ranking = trusted.putArray("rankingVersions");
        if (source.path("provenance").path("rankingVersion").isString())
            ranking.add(source.path("provenance").path("rankingVersion").asText());
        var records = trusted.putArray("records");
        for (JsonNode reference : source.path("references")) {
            ObjectNode record = records.addObject().put("id", reference.path("contentId").asText())
                    .put("contentType", reference.path("contentType").asText());
            record.putArray("topicIds").add(reference.path("topicId").asText());
            record.set("route", reference.path("route").deepCopy());
        }
        return trusted;
    }

    private void publish(JsonNode source, Set<String> scopes, String tier, String scopeMatch, String mediaType)
            throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(source);
        expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.createDirectories(templateFile.getParent());
        Files.write(templateFile, bytes);
        ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", "content-publication/v1")
                .put("version", PUBLICATION_VERSION);
        ObjectNode asset = manifest.putArray("assets").addObject().put("path", templatePath)
                .put("sha256", expectedHash).put("mediaType", mediaType).put("tier", tier)
                .put("scopeMatch", scopeMatch);
        asset.set("scopes", mapper.valueToTree(new ArrayList<>(new TreeSet<>(scopes))));
        asset.putArray("contentIds");
        Path manifestFile = root.resolve("manifest.json");
        Files.write(manifestFile, mapper.writeValueAsBytes(manifest));
        service = new ReadyMadeAdoptionPreparation(new ProtectedContentPolicy(mapper,
                manifestFile.toString(), root.toString()), accounts, catalog, mapper);
    }
}
