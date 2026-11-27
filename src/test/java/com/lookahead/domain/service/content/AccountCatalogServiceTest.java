package com.lookahead.domain.service.content;

import com.lookahead.domain.db.store.content.PublicationFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** DLV-1016: the catalog half that left StudyPlanSnapshotValidator; the first two tests moved here from its test. */
class AccountCatalogServiceTest {
    @TempDir Path directory;
    private final JsonMapper mapper = new JsonMapper();
    private final String hash = "sha256:" + "a".repeat(64);
    private final String source = """
        {"schemaVersion":"account-catalog/v1","catalogVersion":"HASH",
         "algorithmVersions":["study-schedule/v2","interview-sprint/v1"],"rankingVersions":["rank/v1"],
         "compatibleTemplateCatalogVersions":["sha256:old"],
         "records":[{"id":"canonical-1","aliases":["document-1"],"contentType":"dsa-problem","topicIds":["dsa"],"route":["/","learn","dsa","one"]}]}
        """.replace("HASH", hash);
    private final AccountCatalogService catalog = new AccountCatalogService(mapper, mapper.readTree(source));

    @Test void resolvesTrustedAliasesWithoutTreatingUnknownIdsAsCatalogMembers() {
        assertEquals("canonical-1", catalog.canonicalContentId("document-1"));
        assertEquals("unpublished", catalog.canonicalContentId("unpublished"));
    }

    @Test void currentAccessRequiresKnownPublishedMetadataAndServerGrants() {
        assertTrue(catalog.isAccessible("canonical-1", Set.of("dsa")));
        assertTrue(catalog.isAccessible("document-1", Set.of("dsa")));
        assertFalse(catalog.isAccessible("canonical-1", Set.of("other-topic")));
        assertFalse(catalog.isAccessible("retired-content", Set.of("dsa")));
    }

    @Test void answersWhatTheValidatorAsks() {
        assertEquals(hash, catalog.catalogVersion());
        assertEquals(Set.of("dsa"), catalog.allTopicIds());
        assertEquals("canonical-1", catalog.catalog().record("document-1").path("id").asText());
        assertNull(catalog.catalog().record("unknown"));
        assertTrue(catalog.catalog().knows("canonical-1")); assertTrue(catalog.catalog().knows("document-1")); assertFalse(catalog.catalog().knows("unknown"));
        assertTrue(catalog.catalog().knowsTopic("dsa")); assertFalse(catalog.catalog().knowsTopic("other")); assertFalse(catalog.catalog().knowsTopic(null));
        assertTrue(catalog.catalog().knowsTopics(List.of("dsa"))); assertFalse(catalog.catalog().knowsTopics(List.of("dsa", "other")));
        assertEquals(2, catalog.catalog().records().size());
        assertThrows(UnsupportedOperationException.class, () -> catalog.catalog().records().clear());
        assertEquals(List.of("study-schedule/v2", "interview-sprint/v1"), catalog.catalog().algorithmVersions());
        assertEquals(List.of("rank/v1"), catalog.catalog().rankingVersions());
        assertEquals(List.of("sha256:old"), catalog.catalog().compatibleTemplateCatalogVersions());
        assertEquals(List.of(), catalog.catalog().compatibleTemplateRankingVersions());
    }

    @Test void metadataIsACopyWithTheFixedPolicies() {
        JsonNode metadata = catalog.metadata();
        assertEquals("account-catalog/v1", metadata.path("schemaVersion").asText());
        assertEquals(hash, metadata.path("catalogVersion").asText());
        assertEquals("topic-tie-v1", metadata.path("variationPolicies").path(0).asText());
        assertEquals("completion-day-v1", metadata.path("studyActivityPolicies").path(0).asText());
        assertEquals("dsa", metadata.path("topicIds").path(0).asText());
        ((ObjectNode) metadata).putArray("rankingVersions");
        assertEquals(List.of("rank/v1"), catalog.catalog().rankingVersions());
    }

    @Test void refusesACatalogThatIsNotTrustworthy() {
        ObjectNode good = (ObjectNode) mapper.readTree(source);
        ObjectNode schema = good.deepCopy(); schema.put("schemaVersion", "account-catalog/v2");
        assertEquals("Unsupported trusted catalog", assertThrows(IllegalArgumentException.class, () -> new AccountCatalogService(mapper, schema)).getMessage());
        ObjectNode digest = good.deepCopy(); digest.put("catalogVersion", "latest");
        assertEquals("Invalid catalog digest", assertThrows(IllegalArgumentException.class, () -> new AccountCatalogService(mapper, digest)).getMessage());
        ObjectNode twice = good.deepCopy(); ((ArrayNode) twice.path("records")).add(twice.path("records").path(0).deepCopy());
        assertEquals("Duplicate catalog ID", assertThrows(IllegalArgumentException.class, () -> new AccountCatalogService(mapper, twice)).getMessage());
        ObjectNode alias = good.deepCopy();
        ObjectNode second = ((ArrayNode) alias.path("records")).addObject();
        second.put("id", "canonical-2").put("contentType", "dsa-problem"); second.putArray("topicIds").add("dsa"); second.putArray("route").add("/"); second.putArray("aliases").add("document-1");
        assertEquals("Ambiguous catalog alias", assertThrows(IllegalArgumentException.class, () -> new AccountCatalogService(mapper, alias)).getMessage());
    }

    @Test void loadsFromAFileAndSaysWhenItCannot() throws Exception {
        Path file = directory.resolve("account-catalog.json");
        Files.writeString(file, source);
        assertEquals(hash, new AccountCatalogService(mapper, file.toString(), new PublicationFileStore()).catalogVersion());
        var missing = assertThrows(IllegalStateException.class, () -> new AccountCatalogService(mapper, directory.resolve("missing.json").toString(), new PublicationFileStore()));
        assertEquals("Cannot load trusted account catalog", missing.getMessage());
    }
}
