package com.lookahead.domain.validator;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DLV-1016: the manifest checks that were written in the ProtectedContentManifestService constructor; the policy tests still run them through the policy. */
class ProtectedContentManifestValidatorTest {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode good() {
        return (ObjectNode) mapper.readTree("""
            {"schemaVersion":"content-publication/v1","version":"v2026-11-25","assets":[
              {"path":"/content/learn/one.json","sha256":"%s","mediaType":"application/json","tier":"pro","scopes":["learn:docker"],"contentIds":["one"]},
              {"path":"/content/learn/free.json","sha256":"%s","mediaType":"application/json","tier":"free","scopes":[],"contentIds":["free-one"],"scopeMatch":"all"}]}
            """.formatted("a".repeat(64), "b".repeat(64)));
    }
    private static ObjectNode asset(ObjectNode manifest, int at) { return (ObjectNode) manifest.path("assets").path(at); }

    @Test void aCheckedManifestAnswersItsVersionAllowlistAndFreeContent() {
        var manifest = ProtectedContentManifestValidator.validated(good());
        assertThat(manifest.version()).isEqualTo("v2026-11-25");
        assertThat(manifest.assets().keySet()).containsExactlyInAnyOrder("/content/learn/one.json", "/content/learn/free.json");
        assertThat(manifest.assets().get("/content/learn/one.json").scopeMatch()).isEqualTo("any");
        assertThat(manifest.assets().get("/content/learn/free.json").scopeMatch()).isEqualTo("all");
        assertThat(manifest.freeContentIds()).isEqualTo(Set.of("free-one"));
        assertThatThrownBy(() -> manifest.assets().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void eachCheckRefusesWithItsOwnWordsInTheOrderOfTheFormerConstructor() {
        // Schema before version before the assets.
        ObjectNode bad = good(); bad.put("schemaVersion", "content-publication/v2").put("version", "not a version");
        asset(bad, 0).put("sha256", "short");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(bad)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid publication schema");
        bad.put("schemaVersion", "content-publication/v1");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(bad)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid publication version");
        bad.put("version", "v1");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(bad)).isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Invalid");
        // One fault at a time, each with the words of its check.
        ObjectNode scopes = good(); asset(scopes, 0).put("scopes", "learn:docker");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(scopes)).hasMessage("Expected bounded publication array");
        ObjectNode identity = good(); ((ArrayNode) asset(identity, 0).path("contentIds")).add("one");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(identity)).hasMessage("Invalid publication identity");
        ObjectNode rule = good(); asset(rule, 0).put("scopeMatch", "some");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(rule)).hasMessage("Invalid scope matching rule");
        ObjectNode paid = good(); asset(paid, 0).putArray("scopes");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(paid)).hasMessage("Invalid course scope");
        ObjectNode plan = good(); asset(plan, 0).put("path", "/content/study-plans/one.json");
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(plan)).hasMessage("Study plan assets cannot grant curriculum access");
        ObjectNode twice = good(); ((ArrayNode) twice.path("assets")).add(asset(twice, 0).deepCopy());
        assertThatThrownBy(() -> ProtectedContentManifestValidator.validated(twice)).hasMessage("Duplicate asset path");
    }

    @Test void aReleaseNamesBothItsManifestAndItsContentFolderAndAPathHasTheContentForm() {
        assertThatCode(() -> ProtectedContentManifestValidator.requireBoth("/release/publication.json", "/release/content")).doesNotThrowAnyException();
        for (String[] pair : new String[][] {{"", "/release/content"}, {"/release/publication.json", " "}})
            assertThatThrownBy(() -> ProtectedContentManifestValidator.requireBoth(pair[0], pair[1])).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Both publication and content root are required");
        assertThat(ProtectedContentManifestValidator.validPath("/content/learn/docker/one.json")).isTrue();
        for (String path : new String[] {"content/one.json", "/content/../one.json", "/content/one.exe", "/content/one", "/content/" + "a".repeat(600) + ".json"})
            assertThat(ProtectedContentManifestValidator.validPath(path)).as(path).isFalse();
    }
}
