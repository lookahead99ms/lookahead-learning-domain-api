package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-1016: the checks that were the AccountCatalogService constructor; the service tests still run
 * them through the service.
 */
class AccountCatalogValidatorTest {
  private final JsonMapper mapper = new JsonMapper();
  private final String hash = "sha256:" + "b".repeat(64);

  private ObjectNode good() {
    return (ObjectNode)
        mapper.readTree(
            """
            {"schemaVersion":"account-catalog/v1","catalogVersion":"HASH",\
            "algorithmVersions":["study-schedule/v2"],"rankingVersions":["rank/v1"],
             "records":[{"id":"canonical-1","aliases":["document-1"],"contentType":"dsa-problem",\
            "topicIds":["dsa"],"route":["/","learn","dsa","one"]}]}
            """
                .replace("HASH", hash));
  }

  @Test
  void aCheckedCatalogAnswersAsAValue() {
    var catalog = AccountCatalogValidator.validated(good());
    assertThat(catalog.catalogVersion()).isEqualTo(hash);
    assertThat(catalog.allTopicIds()).containsExactly("dsa");
    assertThat(catalog.knows("canonical-1")).isTrue();
    assertThat(catalog.record("document-1")).isSameAs(catalog.record("canonical-1"));
    assertThat(catalog.canonicalContentId("document-1")).isEqualTo("canonical-1");
    assertThat(catalog.isAccessible("document-1", Set.of("content:canonical-1"))).isTrue();
    assertThat(catalog.document().path("schemaVersion").asText()).isEqualTo("account-catalog/v1");
  }

  @Test
  void theChecksRunInTheOrderOfTheFormerConstructor() {
    // Every fault at once: the schema is named first, then the digest, then the records, then the
    // version lists.
    ObjectNode bad = good();
    bad.put("schemaVersion", "account-catalog/v2")
        .put("catalogVersion", "latest")
        .remove("algorithmVersions");
    ((ArrayNode) bad.path("records")).add(bad.path("records").path(0).deepCopy());
    assertThatThrownBy(() -> AccountCatalogValidator.validated(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported trusted catalog");
    bad.put("schemaVersion", "account-catalog/v1");
    assertThatThrownBy(() -> AccountCatalogValidator.validated(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid catalog digest");
    bad.put("catalogVersion", hash);
    assertThatThrownBy(() -> AccountCatalogValidator.validated(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Duplicate catalog ID");
    ((ArrayNode) bad.path("records")).remove(1);
    assertThatThrownBy(() -> AccountCatalogValidator.validated(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid array: algorithmVersions");
  }
}
