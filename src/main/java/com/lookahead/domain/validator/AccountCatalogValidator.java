package com.lookahead.domain.validator;

import static com.lookahead.domain.util.JsonChecks.*;

import com.lookahead.domain.model.AccountCatalog;
import com.lookahead.domain.util.CatalogIds;
import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * Start-up check of the trusted account catalog: the schema, the digest, every record (ID, topics,
 * route, content type), the aliases and the two version lists. A catalog that fails a check refuses
 * the start with IllegalArgumentException and the message of the check. DLV-1016: this was the body
 * of the AccountCatalogService constructor, word for word; the records and topic IDs it collects
 * while checking are the value it answers.
 */
public final class AccountCatalogValidator {
  private AccountCatalogValidator() {}

  /** The catalog as a read-only value, after every check. */
  public static AccountCatalog validated(JsonNode source) {
    Map<String, JsonNode> records = new HashMap<>();
    Set<String> topicIds = new HashSet<>();
    // DLV-931: a publication built before the Blaze rename still yields blaze IDs and routes.
    JsonNode catalog = CatalogIds.normalize(source);
    require(
        "account-catalog/v1".equals(text(catalog, "schemaVersion", 64)),
        "Unsupported trusted catalog");
    require(
        text(catalog, "catalogVersion", 256).matches("sha256:[a-f0-9]{64}"),
        "Invalid catalog digest");
    for (JsonNode record : array(catalog, "records", 50000)) {
      String id = text(record, "id", 256);
      require(records.putIfAbsent(id, record) == null, "Duplicate catalog ID");
      for (String topic : strings(record, "topicIds", 100)) topicIds.add(topic);
      array(record, "route", 12);
      text(record, "contentType", 64);
    }
    for (JsonNode record : catalog.path("records")) {
      for (String alias : optionalStrings(record, "aliases", 100)) {
        JsonNode prior = records.putIfAbsent(alias, record);
        require(prior == null || prior.equals(record), "Ambiguous catalog alias");
      }
    }
    strings(catalog, "algorithmVersions", 100);
    strings(catalog, "rankingVersions", 100);
    return new AccountCatalog(catalog, records, topicIds);
  }
}
