package com.lookahead.learning.content.util;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** DLV-931: legacy look-ahead catalog IDs read as blaze; the Look Ahead platform name is untouched. */
class CatalogIdsTest {
    private final JsonMapper mapper = new JsonMapper();

    @ParameterizedTest
    @CsvSource({
        "look-ahead, blaze",
        "look-ahead:system-design, blaze:system-design",
        "look-ahead:system-design:caching, blaze:system-design:caching",
        "content:look-ahead:system-design:caching, content:blaze:system-design:caching",
        "look-ahead:system-design:caching:review:v2:3, blaze:system-design:caching:review:v2:3",
        "look-ahead:system-design:caching:daily-recall:4, blaze:system-design:caching:daily-recall:4",
        "look-ahead:look-ahead:caching, blaze:blaze:caching",
        "/look-ahead, /blaze",
        "/look-ahead/system-design/caching, /blaze/system-design/caching",
        "/look-ahead?tab=courses, /blaze?tab=courses",
        "/look-ahead#courses, /blaze#courses",
        "/content/look-ahead/system-design/visuals/a.svg, /content/blaze/system-design/visuals/a.svg",
        "/content/indexes/look-ahead.json, /content/indexes/blaze.json"
    })
    void rewritesCatalogIdsRoutesAndAssets(String legacy, String current) {
        assertThat(CatalogIds.normalize(legacy)).isEqualTo(current);
        assertThat(CatalogIds.isLegacy(legacy)).isTrue();
        assertThat(CatalogIds.normalize(current)).isEqualTo(current);
        assertThat(CatalogIds.isLegacy(current)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"look-ahead.study-plan.v1", "look-ahead-theme-v1", "look-ahead.adjustment-dismissal.v1:x",
            "look-ahead-platform-delivery", "Look Ahead", "Look Ahead Catalog", "lookahead", "/look-aheadx", "/learn/look-ahead",
            "learn:core-java", "grow:look-ahead-planning", "look-ahead-course:intro", "/assets/scenes/units/look-ahead/a.svg",
            "X-LookAhead-Account", "LOOKAHEAD_ACCOUNT_CATALOG", ""})
    void leavesPlatformNamesAndOtherCatalogsAlone(String value) {
        assertThat(CatalogIds.normalize(value)).isEqualTo(value);
    }

    @Test void scopesAndPathsUseBlaze() {
        assertThat("blaze:system-design").matches(CatalogIds.SCOPE_PATTERN);
        assertThat("look-ahead:system-design").doesNotMatch(CatalogIds.SCOPE_PATTERN);
        assertThat(CatalogIds.PATHS).containsExactlyInAnyOrder("learn", "grow", "blaze");
        assertThat(CatalogIds.normalize(List.of("look-ahead:a", "blaze:a", "learn:b"))).containsExactly("blaze:a", "learn:b");
    }

    @Test void rewritesKeysAndIdentifiersButNeverProse() {
        JsonNode legacy = mapper.readTree("""
            {"config":{"topicIds":["look-ahead:system-design","learn:core-java"],"familiarity":{"look-ahead:system-design":"new"}},
             "includedTopics":[{"id":"look-ahead:system-design","path":"look-ahead","title":"look-ahead","description":"look-ahead:x"}],
             "days":[{"day":1,"phase":"look-ahead","focus":"look-ahead","assignments":[{"id":"look-ahead:system-design:caching",
               "topicId":"look-ahead:system-design","route":["/","look-ahead","system-design","caching"],"minutes":30.5,
               "title":"look-ahead","instructions":"look-ahead:system-design","timebox":true}]}],
             "notes":{"look-ahead:system-design:caching":"look-ahead"},"name":"look-ahead","goal":"look-ahead",
             "storage":"look-ahead.study-plan.v1","count":3,"missing":null}
            """);
        JsonNode current = CatalogIds.normalize(legacy);
        assertThat(current).isEqualTo(mapper.readTree("""
            {"config":{"topicIds":["blaze:system-design","learn:core-java"],"familiarity":{"blaze:system-design":"new"}},
             "includedTopics":[{"id":"blaze:system-design","path":"blaze","title":"look-ahead","description":"look-ahead:x"}],
             "days":[{"day":1,"phase":"look-ahead","focus":"look-ahead","assignments":[{"id":"blaze:system-design:caching",
               "topicId":"blaze:system-design","route":["/","blaze","system-design","caching"],"minutes":30.5,
               "title":"look-ahead","instructions":"look-ahead:system-design","timebox":true}]}],
             "notes":{"blaze:system-design:caching":"look-ahead"},"name":"look-ahead","goal":"look-ahead",
             "storage":"look-ahead.study-plan.v1","count":3,"missing":null}
            """));
        assertThat(CatalogIds.containsLegacy(legacy)).isTrue();
        assertThat(CatalogIds.containsLegacy(current)).isFalse();
        // The input tree is never modified.
        assertThat(legacy.path("config").path("topicIds").get(0).asText()).isEqualTo("look-ahead:system-design");
    }

    @Test void legacyDuplicatesCollapseIntoTheValueAlreadyInBlazeForm() {
        JsonNode mixed = mapper.readTree("""
            {"completedContentIds":["look-ahead:a:one","blaze:a:one","blaze:a:two"],
             "sessionOutcomes":{"look-ahead:a:one":"attempted","blaze:a:one":"completed"},
             "reversed":{"blaze:a:one":"completed","look-ahead:a:one":"attempted"}}
            """);
        assertThat(CatalogIds.normalize(mixed)).isEqualTo(mapper.readTree("""
            {"completedContentIds":["blaze:a:one","blaze:a:two"],
             "sessionOutcomes":{"blaze:a:one":"completed"},"reversed":{"blaze:a:one":"completed"}}
            """));
    }
}
