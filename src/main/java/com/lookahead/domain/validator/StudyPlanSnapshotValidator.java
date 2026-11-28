package com.lookahead.domain.validator;

import static com.lookahead.domain.util.JsonChecks.*;

import com.lookahead.domain.model.AccountCatalog;
import com.lookahead.domain.util.CatalogIds;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates storage inputs against mounted, backend-controlled publication metadata. DLV-1016: the
 * catalog itself is the value {@link AccountCatalog}; the mapping of a plan imported from the
 * browser is in {@link StudyPlanImportValidator}, which uses this class's checks.
 */
@Component
@Profile("accounts")
public final class StudyPlanSnapshotValidator {
  private final AccountCatalog catalog;

  @Autowired
  public StudyPlanSnapshotValidator(AccountCatalog catalog) {
    this.catalog = catalog;
  }

  public record ValidationResult(
      Map<String, String> assignmentContentIds,
      Set<String> canonicalContentIds,
      String validatedAgainstCatalogVersion,
      JsonNode provenance) {}

  public ValidationResult validate(
      JsonNode snapshot, JsonNode provenance, boolean legacyImport, Set<String> grants) {
    return validateSnapshot(snapshot, provenance, legacyImport, grants, false);
  }

  /** Only for an already-owned immutable version; the store must compare all original pins. */
  public ValidationResult validateExistingVersion(
      JsonNode snapshot, JsonNode provenance, Set<String> grants) {
    boolean legacy = "legacy-local-import".equals(provenance.path("origin").asText());
    return validateSnapshot(snapshot, provenance, legacy, grants, true);
  }

  /**
   * Only for a template version whose owned immutable source/pins were checked by StudyPlanService.
   */
  public ValidationResult validateOwnedTemplateVersion(
      JsonNode snapshot, JsonNode provenance, Set<String> grants) {
    ObjectNode compatible = (ObjectNode) snapshot.deepCopy();
    compatible.remove("template");
    for (String field : List.of("days", "weeks", "futureReviews"))
      stripTemplateFields(compatible.path(field));
    ObjectNode pins = (ObjectNode) provenance.deepCopy();
    pins.remove("template");
    pins.put("origin", "generated");
    var result = validateSnapshot(compatible, pins, false, grants, true);
    return new ValidationResult(
        result.assignmentContentIds(),
        result.canonicalContentIds(),
        result.validatedAgainstCatalogVersion(),
        provenance.deepCopy());
  }

  private void stripTemplateFields(JsonNode node) {
    if (node.isArray()) {
      node.forEach(this::stripTemplateFields);
      return;
    }
    if (!node.isObject()) return;
    ObjectNode object = (ObjectNode) node;
    if (object.has("sourceContentId"))
      for (String key :
          List.of("templateKind", "requiredSessionIds", "reviewBasis", "reviewSourceSessionId"))
        object.remove(key);
    for (var entry : object.properties()) stripTemplateFields(entry.getValue());
  }

  /**
   * Validates an already hash-verified authored source; this never enables client snapshot writes.
   */
  public Map<String, String> validateTemplateReferences(JsonNode template) {
    JsonNode pins = template.path("provenance");
    require(
        "ready-made-schedule/v1".equals(text(pins, "algorithmVersion", 256)),
        "Unsupported template algorithm");
    String templateCatalog = text(pins, "catalogVersion", 256);
    require(
        catalog.catalogVersion().equals(templateCatalog)
            || catalog.compatibleTemplateCatalogVersions().contains(templateCatalog),
        "Unresolvable template catalog version");
    String ranking = optionalText(pins, "rankingVersion", 256);
    // DLV-969: like the catalog pin, a template's historical DSA ranking is accepted only when the
    // release lists it after
    // checking the template against today's lessons; new custom plans still need a current ranking
    // (validateProvenance).
    require(
        ranking == null
            || catalog.rankingVersions().contains(ranking)
            || catalog.compatibleTemplateRankingVersions().contains(ranking),
        "Unresolvable template ranking version");
    Set<String> selected = new HashSet<>(strings(template, "topicIds", 100));
    require(!selected.isEmpty() && catalog.knowsTopics(selected), "Unknown template topic");
    Map<String, String> canonical = new LinkedHashMap<>();
    Set<String> actualTopics = new HashSet<>();
    for (JsonNode reference : array(template, "references", 10000)) {
      String id = text(reference, "contentId", 256), topic = text(reference, "topicId", 256);
      JsonNode record = catalog.record(id);
      require(
          record != null && id.equals(record.path("id").asText()),
          "Template requires canonical published content IDs");
      require(
          selected.contains(topic) && strings(record, "topicIds", 100).contains(topic),
          "Template topic disagrees with catalog");
      require(
          reference.path("route").equals(record.path("route")),
          "Template route disagrees with catalog");
      require(
          reference.path("contentType").equals(record.path("contentType")),
          "Template content type disagrees with catalog");
      require(
          !reference.path("contentType").asText().equals("dsa-problem") || ranking != null,
          "Template DSA references require ranking pin");
      for (String dependency : strings(reference, "prerequisiteIds", 100))
        require(catalog.knows(dependency), "Unknown template prerequisite");
      require(canonical.putIfAbsent(id, id) == null, "Duplicate template reference");
      actualTopics.add(topic);
    }
    require(
        actualTopics.equals(selected),
        "Template selection must exactly cover its reference topics");
    for (String id : strings(template, "assumedPrerequisiteIds", 10000))
      require(catalog.knows(id), "Unknown assumed template prerequisite");
    return Map.copyOf(canonical);
  }

  private ValidationResult validateSnapshot(
      JsonNode snapshot,
      JsonNode provenance,
      boolean legacyImport,
      Set<String> grants,
      boolean existingVersion) {
    fields(
        snapshot,
        "config focusedDailyHours bufferHours includedTopics excludedTopics days weeks"
            + " uniqueNewItems reviewAssignments schedulingVersion eligibleNewItems"
            + " remainingNewItems blockedItems futureReviews overdueReviewCount mode"
            + " topicCoverage");
    JsonNode config = snapshot.path("config");
    fields(
        config,
        "days dailyHours topicIds accessTopicIds goalType familiarity completedContentIds"
            + " needsReviewContentIds variationKey");
    String variationKey = optionalText(config, "variationKey", 80);
    if (variationKey != null)
      require(variationKey.matches("topic-tie-v1-[0-9]+"), "Unsupported variation policy");
    int days = integer(config, "days", 1, 180);
    number(config, "dailyHours", 1, 15);
    Set<String> selected = new HashSet<>(strings(config, "topicIds", 100));
    require(catalog.knowsTopics(selected), "Unknown selected topic");
    // accessTopicIds are preserved provenance, never authorization.
    require(catalog.knowsTopics(strings(config, "accessTopicIds", 100)), "Unknown claimed topic");
    optionalEnum(config, "goalType", Set.of("learning", "interview"));
    if (config.has("familiarity")) {
      object(config.path("familiarity"));
      for (var entry : config.path("familiarity").properties()) {
        require(catalog.knowsTopic(entry.getKey()), "Unknown familiarity topic");
        require(
            entry.getValue().isString()
                && Set.of("new", "refresh", "familiar").contains(entry.getValue().asText()),
            "Invalid familiarity");
      }
    }
    for (String field : List.of("completedContentIds", "needsReviewContentIds"))
      for (String id : optionalStrings(config, field, 10000)) authorized(id, null, grants);
    number(snapshot, "focusedDailyHours", 0, 15);
    number(snapshot, "bufferHours", 0, 15);
    for (String field : List.of("uniqueNewItems", "reviewAssignments"))
      integer(snapshot, field, 0, 10000);
    for (String field : List.of("eligibleNewItems", "remainingNewItems", "overdueReviewCount"))
      if (snapshot.has(field)) integer(snapshot, field, 0, 100000);
    optionalEnum(snapshot, "mode", Set.of("learning", "interview-revision"));
    validateTopics(snapshot, "includedTopics", selected, grants, true);
    validateTopics(snapshot, "excludedTopics", selected, grants, false);
    Map<String, String> assignments = new LinkedHashMap<>();
    Set<String> canonical = new HashSet<>();
    JsonNode dayArray = array(snapshot, "days", 180);
    require(dayArray.size() == days, "Day count disagrees with configuration");
    int count = 0;
    for (int index = 0; index < dayArray.size(); index++) {
      JsonNode day = dayArray.get(index);
      fields(day, "day phase focus assignments newCount reviewCount focusedMinutes bufferMinutes");
      require(integer(day, "day", 1, 180) == index + 1, "Days must be sequential");
      text(day, "phase", 300);
      text(day, "focus", 1000);
      integer(day, "newCount", 0, 10000);
      integer(day, "reviewCount", 0, 10000);
      number(day, "focusedMinutes", 0, 900);
      if (day.has("bufferMinutes")) number(day, "bufferMinutes", 0, 900);
      double minutes = 0;
      for (JsonNode assignment : array(day, "assignments", 10000)) {
        validateAssignment(assignment, grants, selected, assignments, canonical, true);
        minutes += assignment.path("minutes").asDouble();
        require(++count <= 10000, "Too many assignments");
      }
      require(
          minutes <= config.path("dailyHours").asDouble() * 60 + 0.0001, "Daily budget exceeded");
      require(
          Math.abs(minutes - day.path("focusedMinutes").asDouble()) < 0.0001,
          "Focused minutes disagree");
    }
    JsonNode weeks = array(snapshot, "weeks", 26);
    int dayIndex = 0;
    for (int index = 0; index < weeks.size(); index++) {
      JsonNode week = weeks.get(index);
      fields(week, "number label days");
      require(integer(week, "number", 1, 26) == index + 1, "Invalid week number");
      text(week, "label", 300);
      for (JsonNode day : array(week, "days", 7)) {
        require(
            dayIndex < days && day.equals(dayArray.get(dayIndex++)), "Weeks disagree with days");
      }
    }
    require(dayIndex == days, "Weeks omit days");
    if (snapshot.has("futureReviews"))
      for (JsonNode item : array(snapshot, "futureReviews", 10000))
        validateAssignment(item, grants, selected, new HashMap<>(), new HashSet<>(), false);
    if (snapshot.has("blockedItems"))
      for (JsonNode item : array(snapshot, "blockedItems", 10000)) {
        fields(item, "id title prerequisiteIds");
        authorized(text(item, "id", 256), null, grants);
        text(item, "title", 1000);
        // These references explain why an accessible item was not scheduled.
        // They do not add assignments, progress membership or access grants.
        for (String id : strings(item, "prerequisiteIds", 100))
          require(catalog.knows(id), "Unknown or unpublished content reference");
      }
    if (snapshot.has("topicCoverage"))
      for (JsonNode item : array(snapshot, "topicCoverage", 100)) {
        fields(item, "id title scheduledItems availableItems minutes representedOutcomes");
        require(selected.contains(text(item, "id", 256)), "Unknown coverage topic");
        text(item, "title", 1000);
        for (String name :
            List.of("scheduledItems", "availableItems", "minutes", "representedOutcomes"))
          integer(item, name, 0, 10000000);
      }
    JsonNode pins = validateProvenance(snapshot, provenance, legacyImport, existingVersion);
    return new ValidationResult(
        Map.copyOf(assignments), Set.copyOf(canonical), catalog.catalogVersion(), pins);
  }

  private JsonNode validateProvenance(
      JsonNode snapshot, JsonNode provenance, boolean legacy, boolean existingVersion) {
    fields(
        provenance,
        "snapshotSchemaVersion algorithmVersion catalogVersion rankingVersion origin"
            + " historicalProvenance validatedAgainstCatalogVersion");
    if (provenance.has("snapshotSchemaVersion"))
      require(
          "study-plan/v1".equals(text(provenance, "snapshotSchemaVersion", 64)),
          "Unsupported snapshot schema");
    if (provenance.has("origin"))
      require(
          (legacy ? "legacy-local-import" : "generated").equals(text(provenance, "origin", 64)),
          "Invalid snapshot origin");
    String algorithm = optionalText(provenance, "algorithmVersion", 256);
    String snapshotAlgorithm = optionalText(snapshot, "schedulingVersion", 256);
    require(Objects.equals(algorithm, snapshotAlgorithm), "Algorithm pin disagrees with snapshot");
    if (!existingVersion && algorithm != null)
      require(catalog.algorithmVersions().contains(algorithm), "Unsupported algorithm version");
    String version = optionalText(provenance, "catalogVersion", 256);
    String ranking = optionalText(provenance, "rankingVersion", 256);
    if (!legacy && !existingVersion) {
      require(
          algorithm != null && catalog.catalogVersion().equals(version),
          "Generated plans require supported explicit pins");
      boolean dsa = false;
      for (JsonNode day : snapshot.path("days"))
        for (JsonNode item : day.path("assignments"))
          dsa |= item.path("contentType").asText().equals("dsa-problem");
      require(!dsa || ranking != null, "DSA plans require a ranking pin");
    }
    if (!existingVersion && version != null)
      require(catalog.catalogVersion().equals(version), "Unresolvable catalog version");
    if (!existingVersion && ranking != null)
      require(catalog.rankingVersions().contains(ranking), "Unresolvable ranking version");
    if (existingVersion) return provenance.deepCopy();
    ObjectNode result = (ObjectNode) provenance.deepCopy();
    result.put("snapshotSchemaVersion", "study-plan/v1");
    result.put("origin", legacy ? "legacy-local-import" : "generated");
    result.put("validatedAgainstCatalogVersion", catalog.catalogVersion());
    result.remove("historicalProvenance");
    if (legacy && (version == null || algorithm == null || ranking == null))
      result.put("historicalProvenance", "unknown");
    return result;
  }

  private void validateTopics(
      JsonNode snapshot, String name, Set<String> selected, Set<String> grants, boolean included) {
    for (JsonNode topic : array(snapshot, name, 100)) {
      fields(topic, "id path title description courseIds contentTypes");
      String id = text(topic, "id", 256);
      require(selected.contains(id), "Topic does not match selection");
      if (included)
        require(
            grants.contains(id)
                || catalog.records().stream()
                    .anyMatch(record ->
                        strings(record, "topicIds", 100).contains(id)
                            && catalog.isAccessible(record.path("id").asText(), grants)),
            "Topic access denied");
      enumValue(topic, "path", CatalogIds.PATHS);
      text(topic, "title", 1000);
      text(topic, "description", 2000);
      strings(topic, "courseIds", 100);
      optionalStrings(topic, "contentTypes", 30);
    }
  }

  void validateAssignment(
      JsonNode item,
      Set<String> grants,
      Set<String> selected,
      Map<String, String> assignments,
      Set<String> canonical,
      boolean scheduled) {
    fields(
        item,
        "id kind activity topicId topicTitle title courseTitle contentType route minutes"
            + " reviewFromDay sourceContentId prerequisiteIds reviewDueDay relatedLessonIds timebox"
            + " instructions requiredSessionId coverageKey");
    String id = text(item, "id", 256);
    String source = optionalText(item, "sourceContentId", 256);
    String topic = text(item, "topicId", 256);
    require(selected.contains(topic), "Assignment topic not selected");
    JsonNode record = authorized(source == null ? id : source, topic, grants);
    require(
        item.path("route").equals(record.path("route")),
        "Assignment route disagrees with trusted catalog");
    require(
        item.path("contentType").equals(record.path("contentType")),
        "Content type disagrees with trusted catalog");
    String canonicalId = record.path("id").asText();
    if (scheduled) {
      require(assignments.putIfAbsent(id, canonicalId) == null, "Duplicate session ID");
      canonical.add(canonicalId);
    }
    enumValue(item, "kind", Set.of("new", "review"));
    enumValue(
        item,
        "activity",
        Set.of("Understand", "Practice", "Apply", "Recall", "Attempt", "Refresh", "Rehearse"));
    enumValue(
        item,
        "contentType",
        Set.of(
            "q-and-a",
            "theory",
            "dsa-pattern",
            "dsa-problem",
            "system-design",
            "language-comparison",
            "guide"));
    for (String field : List.of("topicTitle", "title", "courseTitle")) text(item, field, 1000);
    number(item, "minutes", 1, 900);
    for (String field : List.of("reviewFromDay", "reviewDueDay"))
      if (item.has(field)) integer(item, field, 1, 10000);
    for (String field : List.of("prerequisiteIds", "relatedLessonIds"))
      for (String dependency : optionalStrings(item, field, 100))
        authorized(dependency, null, grants);
    if (item.has("timebox")) require(item.path("timebox").isBoolean(), "Invalid timebox");
    optionalText(item, "instructions", 2000);
    optionalText(item, "requiredSessionId", 256);
    optionalText(item, "coverageKey", 512);
  }

  JsonNode authorized(String id, String topic, Set<String> grants) {
    JsonNode record = catalog.record(id);
    require(record != null, "Unknown or unpublished content reference");
    List<String> topics = strings(record, "topicIds", 100);
    require(
        (topic == null || topics.contains(topic)) && catalog.isAccessible(id, grants),
        "Content access denied");
    return record;
  }
}
