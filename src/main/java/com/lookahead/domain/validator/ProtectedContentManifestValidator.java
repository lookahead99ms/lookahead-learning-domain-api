package com.lookahead.domain.validator;

import com.lookahead.domain.model.ProtectedContentModels.Asset;
import com.lookahead.domain.model.ProtectedContentModels.Manifest;
import com.lookahead.domain.util.CatalogIds;
import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * Start-up check of the publication manifest (publication.json): the schema, the version and every
 * asset (path, digest, tier, media type, scopes, content IDs, scope matching). A manifest that
 * fails a check throws IllegalArgumentException with the message of the check; the policy turns it
 * into its start-up refusal. DLV-1016: these checks were written in the
 * ProtectedContentManifestService constructor; they are here word for word, in the same order. The
 * allowlist and the free content IDs collected while checking are the value this answers.
 */
public final class ProtectedContentManifestValidator {
  private ProtectedContentManifestValidator() {}

  /**
   * A release either names both its manifest and its content folder or neither: one without the
   * other is refused.
   */
  public static void requireBoth(String manifestPath, String contentRoot) {
    if (manifestPath.isBlank() || contentRoot.isBlank())
      throw new IllegalArgumentException("Both publication and content root are required");
  }

  /**
   * The checked manifest: its version, the allowlist by path and the content IDs that need no
   * purchase.
   */
  public static Manifest validated(JsonNode data) {
    if (!data.path("schemaVersion").asText().equals("content-publication/v1")
        || !data.path("assets").isArray()
        || data.path("assets").size() > 50000)
      throw new IllegalArgumentException("Invalid publication schema");
    String version = data.path("version").asText();
    if (!version.matches("[a-zA-Z0-9_-]{1,80}"))
      throw new IllegalArgumentException("Invalid publication version");
    Map<String, Asset> entries = new HashMap<>();
    Set<String> free = new HashSet<>();
    for (var item : data.path("assets")) {
      String path = item.path("path").asText(),
          sha = item.path("sha256").asText(),
          type = item.path("mediaType").asText(),
          tier = item.path("tier").asText();
      if (!validPath(path)
          || !sha.matches("[a-f0-9]{64}")
          || !Set.of("public", "free", "pro").contains(tier)
          || !Set.of(
                  "application/json",
                  "text/html",
                  "text/css",
                  "text/javascript",
                  "image/svg+xml",
                  "image/png",
                  "image/jpeg",
                  "image/webp",
                  "font/woff2")
              .contains(type)) throw new IllegalArgumentException("Invalid asset entry");
      // DLV-931: a manifest built before the Blaze rename still grants blaze scopes and IDs.
      Set<String> scopes = Set.copyOf(CatalogIds.normalize(strings(item.path("scopes")))),
          ids = Set.copyOf(CatalogIds.normalize(strings(item.path("contentIds"))));
      String scopeMatch = "any";
      if (item.has("scopeMatch")) {
        if (!item.path("scopeMatch").isString()
            || !Set.of("any", "all").contains(item.path("scopeMatch").asText()))
          throw new IllegalArgumentException("Invalid scope matching rule");
        scopeMatch = item.path("scopeMatch").asText();
      }
      if (scopes.stream().anyMatch(scope -> !scope.matches(CatalogIds.SCOPE_PATTERN))
          || (tier.equals("pro") && scopes.isEmpty()))
        throw new IllegalArgumentException("Invalid course scope");
      if (path.startsWith("/content/study-plans/") && !ids.isEmpty())
        throw new IllegalArgumentException("Study plan assets cannot grant curriculum access");
      // DLV-968: staff documents open for the staff:documents scope but never unlock lessons.
      if (scopes.stream().anyMatch(scope -> !CatalogIds.isCurriculumGrant(scope)) && !ids.isEmpty())
        throw new IllegalArgumentException("Staff assets cannot grant curriculum access");
      if (entries.putIfAbsent(path, new Asset(path, sha, type, tier, scopes, ids, scopeMatch))
          != null) throw new IllegalArgumentException("Duplicate asset path");
      if (!tier.equals("pro")) free.addAll(ids);
    }
    return new Manifest(version, Map.copyOf(entries), Set.copyOf(free));
  }

  private static Set<String> strings(JsonNode node) {
    if (!node.isArray() || node.size() > 50000)
      throw new IllegalArgumentException("Expected bounded publication array");
    Set<String> result = new HashSet<>();
    for (var value : node) {
      if (!value.isString()
          || value.asText().isBlank()
          || value.asText().length() > 300
          || !result.add(value.asText()))
        throw new IllegalArgumentException("Invalid publication identity");
    }
    return Set.copyOf(result);
  }

  public static boolean validPath(String path) {
    return path.length() <= 600
        && path.matches(
            "/content/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:json|html|css|js|svg|"
                + "png|jpg|jpeg|webp|woff2)");
  }
}
