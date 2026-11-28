package com.lookahead.domain.validator;

import static com.lookahead.domain.model.AuthorReviewModels.*;

import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * Start-up check of the author review manifest ({@code app.author-reviews.manifest}): a JSON list
 * of at most 100 entries, each exactly artifactId, artifactVersion, contentHash and ticketId as
 * text in their forms, no artifact twice. A manifest that fails a check throws
 * IllegalArgumentException without a message; the catalog writes the start-up refusal line and
 * refuses the start. DLV-1016: these checks were written in the AuthorReviewArtifactService
 * constructor; they are here word for word, in the same order.
 */
public final class AuthorReviewManifestValidator {
  private AuthorReviewManifestValidator() {}

  /** The checked artifacts by ID, in ID order, read-only. */
  public static Map<String, Artifact> validated(JsonNode root) {
    if (!root.isArray() || root.size() > 100) throw new IllegalArgumentException();
    Map<String, Artifact> loaded = new TreeMap<>();
    for (var node : root) {
      if (node.size() != 4
          || !node.has("artifactId")
          || !node.has("artifactVersion")
          || !node.has("contentHash")
          || !node.has("ticketId")) throw new IllegalArgumentException();
      for (String key : List.of("artifactId", "artifactVersion", "contentHash", "ticketId"))
        if (!node.path(key).isTextual()) throw new IllegalArgumentException();
      var artifact =
          new Artifact(
              node.path("artifactId").asText(),
              node.path("artifactVersion").asText(),
              node.path("contentHash").asText(),
              node.path("ticketId").asText());
      if (!artifact.artifactId().matches("[a-z0-9][a-z0-9-]{0,79}")
          || !artifact.artifactVersion().matches("[a-zA-Z0-9][a-zA-Z0-9/._-]{0,119}")
          || !artifact.contentHash().matches("[a-f0-9]{64}")
          || !artifact.ticketId().matches("DLV-[1-9][0-9]{0,5}")
          || loaded.putIfAbsent(artifact.artifactId(), artifact) != null)
        throw new IllegalArgumentException();
    }
    return Collections.unmodifiableMap(loaded);
  }
}
