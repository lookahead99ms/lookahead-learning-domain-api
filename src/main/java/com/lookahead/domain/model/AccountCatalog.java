package com.lookahead.domain.model;

import static com.lookahead.domain.util.JsonChecks.*;

import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * The trusted account catalog of the mounted release as a read-only value: every published content
 * item with its topics, routes and aliases, and the algorithm and ranking versions the release
 * supports. Built once at start by {@code AccountCatalogValidator.validated}, which checks it;
 * {@code AccountCatalogService} holds it. DLV-1016: these answers were methods of
 * AccountCatalogService, which the study plan validators depended on; they are here word for word.
 */
public final class AccountCatalog {
  private final JsonNode catalog;
  private final Map<String, JsonNode> records;
  private final Set<String> topicIds;

  /**
   * The three parts are kept as given (not copied): the checked document, its records by ID and by
   * alias, and every topic ID.
   */
  public AccountCatalog(JsonNode catalog, Map<String, JsonNode> records, Set<String> topicIds) {
    this.catalog = catalog;
    this.records = records;
    this.topicIds = topicIds;
  }

  /** The checked catalog document. Read it; copy any part that is handed out. */
  public JsonNode document() {
    return catalog;
  }

  public Set<String> allTopicIds() {
    return Set.copyOf(topicIds);
  }

  public String catalogVersion() {
    return catalog.path("catalogVersion").asText();
  }

  public boolean isAccessible(String canonicalId, Set<String> grants) {
    JsonNode record = records.get(canonicalId);
    return record != null
        && (grants.contains("content:" + record.path("id").asText())
            || strings(record, "topicIds", 100).stream().anyMatch(grants::contains));
  }

  /** Canonicalizes a known metadata alias; unknown historical IDs remain opaque, not authorized. */
  public String canonicalContentId(String id) {
    JsonNode record = records.get(id);
    return record == null ? id : record.path("id").asText();
  }

  /** The catalog record of an ID or alias, or null. */
  public JsonNode record(String id) {
    return records.get(id);
  }

  public boolean knows(String id) {
    return records.containsKey(id);
  }

  public boolean knowsTopic(String topic) {
    return topicIds.contains(topic);
  }

  public boolean knowsTopics(Collection<String> topics) {
    return topicIds.containsAll(topics);
  }

  /** Every record, once per ID and once per alias, as the catalog map holds them. */
  public Collection<JsonNode> records() {
    return Collections.unmodifiableCollection(records.values());
  }

  public List<String> algorithmVersions() {
    return strings(catalog, "algorithmVersions", 100);
  }

  public List<String> rankingVersions() {
    return strings(catalog, "rankingVersions", 100);
  }

  public List<String> compatibleTemplateCatalogVersions() {
    return optionalStrings(catalog, "compatibleTemplateCatalogVersions", 100);
  }

  public List<String> compatibleTemplateRankingVersions() {
    return optionalStrings(catalog, "compatibleTemplateRankingVersions", 100);
  }
}
