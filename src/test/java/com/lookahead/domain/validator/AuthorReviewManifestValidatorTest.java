package com.lookahead.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-1016: the manifest checks that were written in the AuthorReviewArtifactService constructor.
 */
class AuthorReviewManifestValidatorTest {
  private final JsonMapper mapper = new JsonMapper();

  private static String entry(String id, String version, String hash, String ticket) {
    return "{\"artifactId\":\""
        + id
        + "\",\"artifactVersion\":\""
        + version
        + "\",\"contentHash\":\""
        + hash
        + "\",\"ticketId\":\""
        + ticket
        + "\"}";
  }

  private static final String HASH = "a".repeat(64);

  @Test
  void aCheckedManifestAnswersItsArtifactsInIdOrderReadOnly() {
    var artifacts =
        AuthorReviewManifestValidator.validated(
            mapper.readTree(
                "["
                    + entry("study-plan", "v/1.0", HASH, "DLV-704")
                    + ","
                    + entry("algo-pattern", "v2", HASH, "DLV-1")
                    + "]"));
    assertThat(artifacts.keySet()).containsExactly("algo-pattern", "study-plan");
    assertThat(artifacts.get("study-plan").ticketId()).isEqualTo("DLV-704");
    assertThatThrownBy(() -> artifacts.remove("study-plan"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(AuthorReviewManifestValidator.validated(mapper.readTree("[]"))).isEmpty();
  }

  @Test
  void anythingElseIsRefusedWithoutAMessage() {
    String good = entry("study-plan", "v1", HASH, "DLV-704");
    for (String manifest :
        new String[] {
          "{}",
          "[" + good + "," + good + "]",
          "[{\"artifactId\":\"study-plan\",\"artifactVersion\":\"v1\",\"contentHash\":\""
              + HASH
              + "\"}]",
          "[{\"artifactId\":\"study-plan\",\"artifactVersion\":\"v1\",\"contentHash\":\""
              + HASH
              + "\",\"ticketId\":704}]",
          "[" + entry("Study-Plan", "v1", HASH, "DLV-704") + "]",
          "[" + entry("study-plan", "", HASH, "DLV-704") + "]",
          "[" + entry("study-plan", "v1", "abc", "DLV-704") + "]",
          "[" + entry("study-plan", "v1", HASH, "DLV-0") + "]",
          "[" + good.replace("}", ",\"extra\":\"x\"}") + "]"
        })
      assertThatThrownBy(() -> AuthorReviewManifestValidator.validated(mapper.readTree(manifest)))
          .as(manifest)
          .isExactlyInstanceOf(IllegalArgumentException.class)
          .hasMessage(null);
    var tooMany = new StringBuilder("[");
    for (int at = 0; at < 101; at++)
      tooMany.append(at == 0 ? "" : ",").append(entry("artifact-" + at, "v1", HASH, "DLV-704"));
    assertThatThrownBy(
            () -> AuthorReviewManifestValidator.validated(mapper.readTree(tooMany + "]")))
        .isExactlyInstanceOf(IllegalArgumentException.class);
  }
}
