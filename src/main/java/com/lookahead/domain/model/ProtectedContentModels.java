package com.lookahead.domain.model;

import com.lookahead.domain.util.CatalogIds;
import java.util.*;

/**
 * One published asset of the release. DLV-1016: these were nested in
 * ProtectedContentManifestService; the store uses them too, so they live with the model.
 */
public final class ProtectedContentModels {
  private ProtectedContentModels() {}

  public record Asset(
      String path,
      String sha256,
      String mediaType,
      String tier,
      Set<String> scopes,
      Set<String> contentIds,
      String scopeMatch) {
    public boolean matchesScopes(Set<String> grants) {
      Set<String> courseGrants = CatalogIds.normalize(grants);
      return scopeMatch.equals("all")
          ? courseGrants.containsAll(scopes)
          : scopes.stream().anyMatch(courseGrants::contains);
    }
  }

  /**
   * A checked publication manifest: its version, the allowlist by path and the content IDs that
   * need no purchase (built by ProtectedContentManifestValidator).
   */
  public record Manifest(String version, Map<String, Asset> assets, Set<String> freeContentIds) {}
}
