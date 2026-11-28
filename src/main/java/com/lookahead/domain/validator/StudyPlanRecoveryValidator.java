package com.lookahead.domain.validator;

import static com.lookahead.domain.util.JsonChecks.require;
import static com.lookahead.domain.util.PayloadReaders.*;

import com.lookahead.domain.model.AccountCatalog;
import com.lookahead.domain.model.StudyPlanVersion;
import com.lookahead.domain.util.StudyPlanJson;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the recovery data of a new plan version against the version it replaces: a plan update, a
 * recovery inside the same window or an explicit deadline extension. DLV-1016: this is
 * StudyPlanService.validateRecovery with its two helpers, moved here word for word and in the same
 * order; every refusal is the IllegalArgumentException it was.
 */
@Component
@Profile("accounts")
public class StudyPlanRecoveryValidator {
  private final AccountCatalog catalog;

  public StudyPlanRecoveryValidator(AccountCatalog catalog) {
    this.catalog = catalog;
  }

  public void validate(
      StudyPlanVersion prior, JsonNode next, JsonNode pins, JsonNode recovery, String reason) {
    fields(recovery, "strategy elapsedDays deadlineDays deferredContentIds deferredSessions");
    String strategy = text(recovery, "strategy", 40);
    int elapsed = integer(recovery, "elapsedDays", 0, 180);
    int deadline = integer(recovery, "deadlineDays", 1, 180);
    require(elapsed <= deadline, "Elapsed days exceed this deadline");
    require(
        next.path("config").path("days").asInt() == deadline,
        "Snapshot horizon must match deadline");
    JsonNode deferred = recovery.path("deferredContentIds");
    require(deferred.isArray() && deferred.size() <= 10000, "Invalid deferred content list");
    Map<String, JsonNode> scheduledBefore = StudyPlanJson.assignments(prior.snapshot());
    Map<String, JsonNode> oldAssignments = new LinkedHashMap<>(scheduledBefore),
        newAssignments = StudyPlanJson.assignments(next);
    for (JsonNode a : prior.snapshot().path("futureReviews"))
      oldAssignments.putIfAbsent(a.path("id").asText(), a);
    for (JsonNode entry : prior.recovery().path("deferredSessions")) {
      JsonNode a = entry.path("assignment");
      oldAssignments.putIfAbsent(a.path("id").asText(), a);
    }
    Set<String> oldContent = new HashSet<>();
    for (JsonNode a : oldAssignments.values())
      oldContent.add(catalog.canonicalContentId(StudyPlanJson.sourceId(a)));
    for (JsonNode item : deferred)
      require(item.isString() && oldContent.contains(item.asText()), "Unknown deferred content");
    int oldDeadline =
        prior
            .recovery()
            .path("deadlineDays")
            .asInt(prior.snapshot().path("config").path("days").asInt());
    require(
        elapsed >= prior.recovery().path("elapsedDays").asInt(),
        "Recovery cannot move elapsed time backwards");
    if (recovery.has("deferredSessions")) {
      JsonNode ledger = recovery.path("deferredSessions");
      require(ledger.isArray() && ledger.size() <= 10000, "Invalid deferred session ledger");
      for (JsonNode entry : ledger) {
        fields(entry, "assignment originalDay reason");
        require(
            Set.of(
                    "window-ended",
                    "daily-budget",
                    "prerequisite",
                    "review-session",
                    "review-spacing",
                    "remaining-capacity")
                .contains(text(entry, "reason", 40)),
            "Invalid deferral reason");
        JsonNode assignment = entry.path("assignment"), day = entry.path("originalDay");
        boolean found = false;
        if (day.isNull()) {
          for (JsonNode candidate : prior.snapshot().path("futureReviews"))
            if (candidate.equals(assignment)) found = true;
        } else {
          int original = integer(entry, "originalDay", 1, prior.snapshot().path("days").size());
          for (JsonNode candidate :
              prior.snapshot().path("days").get(original - 1).path("assignments"))
            if (candidate.equals(assignment)) found = true;
        }
        if (!found)
          for (JsonNode oldEntry : prior.recovery().path("deferredSessions"))
            if (oldEntry.path("assignment").equals(assignment)
                && oldEntry.path("originalDay").equals(day)) found = true;
        require(found, "Deferred session differs from original snapshot");
      }
    }
    if (reason.equals("update-plan")) {
      require(strategy.equals("none"), "A plan update must use strategy none");
      if (prior.snapshot().path("config").path("goalType").asText().equals("interview"))
        require(deadline == oldDeadline, "Move an interview deadline only with explicit extension");
      return;
    }
    require(
        next.path("config")
            .path("dailyHours")
            .equals(prior.snapshot().path("config").path("dailyHours")),
        "Recovery cannot increase the daily budget");
    for (String pin : List.of("algorithmVersion", "catalogVersion", "rankingVersion"))
      require(
          pins.path(pin).equals(prior.provenance().path(pin)),
          "Recovery must preserve version pins");
    require(
        pins.path("origin").equals(prior.provenance().path("origin")),
        "Recovery must preserve snapshot origin");
    if (reason.equals("recovery"))
      require(
          strategy.equals("fixed-window") && deadline == oldDeadline,
          "Recovery must preserve the interview deadline");
    else
      require(
          strategy.equals("explicit-extension") && deadline > oldDeadline,
          "Extension must explicitly move the deadline later");
    for (var entry : newAssignments.entrySet()) {
      JsonNode old = oldAssignments.get(entry.getKey());
      require(
          old != null && withoutReviewTiming(old).equals(withoutReviewTiming(entry.getValue())),
          "Recovery cannot replace or alter scheduled sessions");
      if (!old.equals(entry.getValue())) {
        JsonNode changed = entry.getValue();
        require(
            "review".equals(changed.path("kind").asText()), "Only review timing fields may change");
        String parent =
            changed
                .path("requiredSessionId")
                .asText(catalog.canonicalContentId(StudyPlanJson.sourceId(changed)));
        int parentDay = assignmentDay(next, parent);
        int oldParentDay = assignmentDay(prior.snapshot(), parent);
        if (oldParentDay == 0) oldParentDay = old.path("reviewFromDay").asInt(0);
        int oldDue =
            old.path("reviewDueDay").asInt(assignmentDay(prior.snapshot(), entry.getKey()));
        int spacing = Math.max(1, oldDue - oldParentDay);
        require(parentDay > 0, "Changed review timing requires its scheduled parent");
        int due = integer(changed, "reviewDueDay", parentDay + spacing, deadline);
        require(assignmentDay(next, entry.getKey()) >= due, "Review scheduled before its due day");
        if (changed.has("reviewFromDay"))
          require(
              changed.path("reviewFromDay").asInt() == parentDay,
              "Review origin must match its parent day");
      }
    }
    Set<String> deferredIds = new HashSet<>();
    deferred.forEach(n -> deferredIds.add(n.asText()));
    if (prior.snapshot().has("template")) {
      Set<String> nextFuture = new HashSet<>();
      for (JsonNode item : next.path("futureReviews")) {
        String id = item.path("id").asText();
        require(
            nextFuture.add(id) && item.equals(oldAssignments.get(id)),
            "Future template reviews must preserve their original metadata");
      }
      for (JsonNode old : prior.snapshot().path("futureReviews"))
        require(
            newAssignments.containsKey(old.path("id").asText())
                || nextFuture.contains(old.path("id").asText())
                || deferredIds.contains(catalog.canonicalContentId(StudyPlanJson.sourceId(old))),
            "Omitted future review must be disclosed as deferred");
    }
    for (var entry : scheduledBefore.entrySet())
      if (!newAssignments.containsKey(entry.getKey()))
        require(
            deferredIds.contains(
                catalog.canonicalContentId(StudyPlanJson.sourceId(entry.getValue()))),
            "Omitted work must be disclosed as deferred");
    // Days that have already elapsed may retain historical work, but cannot gain a new assignment.
    for (int day = 0; day < elapsed; day++) {
      Set<String> oldIds = new HashSet<>();
      if (day < prior.snapshot().path("days").size())
        prior
            .snapshot()
            .path("days")
            .get(day)
            .path("assignments")
            .forEach(a -> oldIds.add(a.path("id").asText()));
      for (JsonNode a : next.path("days").get(day).path("assignments"))
        require(oldIds.contains(a.path("id").asText()), "Cannot schedule new work in elapsed days");
    }
  }

  private JsonNode withoutReviewTiming(JsonNode assignment) {
    ObjectNode result = (ObjectNode) assignment.deepCopy();
    result.remove("reviewFromDay");
    result.remove("reviewDueDay");
    return result;
  }

  private int assignmentDay(JsonNode snapshot, String id) {
    for (JsonNode day : snapshot.path("days"))
      for (JsonNode assignment : day.path("assignments"))
        if (assignment.path("id").asText().equals(id)) return day.path("day").asInt();
    return 0;
  }
}
