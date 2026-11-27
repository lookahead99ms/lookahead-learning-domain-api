package com.lookahead.domain.validator;

import com.lookahead.domain.model.AccountCatalog;
import com.lookahead.domain.util.StudyPlanActivity;
import com.lookahead.domain.validator.StudyPlanSnapshotValidator.ValidationResult;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lookahead.domain.util.JsonChecks.*;

/**
 * Turns a study plan saved in the browser into what the account stores: its progress and its recovery data, each
 * checked against the validated snapshot. DLV-1016: these two methods were part of StudyPlanSnapshotValidator and moved
 * here word for word; catalog questions go to the catalog value (model AccountCatalog), the assignment check to the validator.
 */
public final class StudyPlanImportValidator {
    private final ObjectMapper mapper;
    private final StudyPlanSnapshotValidator validator;
    private final AccountCatalog catalog;

    public StudyPlanImportValidator(ObjectMapper mapper, StudyPlanSnapshotValidator validator, AccountCatalog catalog) {
        this.mapper = mapper; this.validator = validator; this.catalog = catalog;
    }

    /** Preserves imported aggregate evidence without manufacturing attempt counts or timestamps. */
    public JsonNode mapImportedProgress(JsonNode local, ValidationResult validated) {
        fields(local, "schemaVersion revision name planNumber createdAt customName goal rankingVersion catalogVersion snapshot completedIds shiftedDays recovery deferredSessions sessionOutcomes reviewNotes attemptedContentIds needsReviewContentIds history studyLog readyMade startDate");
        require("study-plan-local/v1".equals(text(local, "schemaVersion", 64)), "Unsupported local snapshot");
        integer(local, "revision", 1, Integer.MAX_VALUE); text(local, "goal", 160); integer(local, "shiftedDays", 0, 10000);
        String localCatalog = optionalText(local, "catalogVersion", 256);
        require(Objects.equals(localCatalog, optionalText(validated.provenance(), "catalogVersion", 256)), "Imported catalog provenance disagrees with saved local pin");
        String localRanking = optionalText(local, "rankingVersion", 256);
        require(Objects.equals(localRanking, optionalText(validated.provenance(), "rankingVersion", 256)), "Imported ranking provenance disagrees with saved local pin");
        Set<String> content = new TreeSet<>(), sessions = new TreeSet<>();
        for (String id : strings(local, "completedIds", 10000)) {
            boolean session = validated.assignmentContentIds().containsKey(id) || StudyPlanActivity.resolve(local.path("snapshot"),id,StudyPlanActivity.dynamicDay(id)) != null;
            String canonicalId = catalog.canonicalContentId(id);
            boolean canonical = validated.canonicalContentIds().contains(canonicalId);
            // Prior local-plan progress remains raw provenance and is not projected into this plan.
            if (session) sessions.add(id);
            // Same canonical/session identity in a learning plan is the explicit full-content toggle.
            if (canonical) content.add(canonicalId);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("completedContentIds", mapper.valueToTree(content));
        result.set("completedSessionIds", mapper.valueToTree(sessions));
        for (String field : List.of("attemptedContentIds", "needsReviewContentIds")) {
            List<String> values = optionalStrings(local, field, 10000);
            result.set(field, mapper.valueToTree(values.stream().map(catalog::canonicalContentId).filter(validated.canonicalContentIds()::contains).distinct().toList()));
        }
        ObjectNode notes = mapper.createObjectNode(), outcomes = mapper.createObjectNode();
        if (local.has("reviewNotes")) {
            object(local.path("reviewNotes"));
            for (var entry : local.path("reviewNotes").properties()) {
                require(entry.getValue().isString(), "Invalid note");
                boundedText(entry.getKey(), 256); boundedText(entry.getValue().asText(), 1000);
                String canonicalId = catalog.canonicalContentId(entry.getKey());
                if (validated.canonicalContentIds().contains(canonicalId)) notes.set(canonicalId, entry.getValue());
            }
        }
        if (local.has("sessionOutcomes")) {
            object(local.path("sessionOutcomes"));
            for (var entry : local.path("sessionOutcomes").properties()) {
                boundedText(entry.getKey(), 256);
                require(entry.getValue().isString() && Set.of("attempted", "needs-review", "completed").contains(entry.getValue().asText()), "Invalid session outcome");
                if (validated.assignmentContentIds().containsKey(entry.getKey()) || StudyPlanActivity.resolve(local.path("snapshot"),entry.getKey(),StudyPlanActivity.dynamicDay(entry.getKey())) != null) outcomes.set(entry.getKey(), entry.getValue());
            }
        }
        for (JsonNode history : array(local, "history", 10000)) {
            fields(history, "revision changedAt reason"); integer(history, "revision", 1, Integer.MAX_VALUE);
            text(history, "changedAt", 100); text(history, "reason", 2000);
        }
        result.set("notes", notes); result.set("sessionOutcomes", outcomes);
        if (local.has("studyLog")) {
            var log = mapper.createArrayNode(); Set<String> seen = new HashSet<>();
            for (JsonNode entry : array(local,"studyLog",20000)) {
                fields(entry,"assignmentId day minutes recordedAt");
                String id = text(entry,"assignmentId",300); int day = integer(entry,"day",1,180);
                number(entry,"minutes",1,900);
                Instant.parse(text(entry,"recordedAt",100));
                require(seen.add(id+"\0"+day),"Duplicate study activity date");
                JsonNode activity = StudyPlanActivity.resolve(local.path("snapshot"),id,day);
                if (activity != null) {
                    require(Math.abs(entry.path("minutes").asDouble()-activity.path("minutes").asDouble())<0.0001,"Study estimate disagrees with plan");
                    log.add(entry.deepCopy());
                }
            }
            result.set("studyLog",log);
        }
        ObjectNode source = (ObjectNode) local.deepCopy(); source.remove("snapshot"); result.set("legacySource", source);
        return result;
    }

    /** Validates explicit local recovery and its out-of-schedule ledger without inventing dates. */
    public JsonNode validateImportedRecovery(JsonNode local, Set<String> grants) {
        int horizon = integer(local.path("snapshot").path("config"), "days", 1, 180);
        int shifted = integer(local, "shiftedDays", 0, 10000);
        ObjectNode result;
        JsonNode recovery = local.path("recovery");
        if (local.has("recovery")) {
            fields(recovery, "strategy elapsedDays deadlineDays deferredContentIds deferredSessions");
            enumValue(recovery, "strategy", Set.of("none", "fixed-window", "explicit-extension"));
            int elapsed = integer(recovery, "elapsedDays", 0, 180);
            require(integer(recovery, "deadlineDays", 1, 180) == horizon, "Imported deadline must equal snapshot horizon");
            require(elapsed <= horizon, "Elapsed days exceed imported deadline");
            for (String id : strings(recovery, "deferredContentIds", 10000)) validator.authorized(id, null, grants);
            result = (ObjectNode) recovery.deepCopy();
        } else {
            result = mapper.createObjectNode();
            result.put("strategy", "none").put("elapsedDays", 0).put("deadlineDays", horizon);
            result.set("deferredContentIds", mapper.createArrayNode());
        }
        result.put("legacyShiftedDays", shifted);
        JsonNode ledger = local.has("deferredSessions") ? local.path("deferredSessions") : recovery.path("deferredSessions");
        if (local.has("deferredSessions") && recovery.has("deferredSessions"))
            require(local.path("deferredSessions").equals(recovery.path("deferredSessions")), "Imported deferred ledgers disagree");
        if (!ledger.isMissingNode()) {
            require(ledger.isArray() && ledger.size() <= 10000, "Invalid deferred session ledger");
            Set<String> selected = new HashSet<>(strings(local.path("snapshot").path("config"), "topicIds", 100));
            Map<String, String> sessions = new HashMap<>();
            Set<String> content = new TreeSet<>();
            Set<String> scheduled = new HashSet<>();
            for (JsonNode day : local.path("snapshot").path("days"))
                for (JsonNode item : day.path("assignments")) scheduled.add(item.path("id").asText());
            for (JsonNode entry : ledger) {
                fields(entry, "assignment originalDay reason");
                enumValue(entry, "reason", Set.of("window-ended", "daily-budget", "prerequisite", "review-session", "review-spacing", "remaining-capacity"));
                require(entry.has("originalDay"), "Original day must be explicit or null");
                if (!entry.path("originalDay").isNull()) integer(entry, "originalDay", 1, horizon);
                JsonNode item = entry.path("assignment");
                validator.validateAssignment(item, grants, selected, sessions, content, true);
                require(!scheduled.contains(item.path("id").asText()), "Deferred session is also scheduled");
            }
            if (local.has("recovery")) {
                Set<String> declared = new HashSet<>();
                for (String id : strings(recovery, "deferredContentIds", 10000)) declared.add(catalog.canonicalContentId(id));
                require(declared.equals(content), "Deferred content IDs disagree with session ledger");
            } else result.set("deferredContentIds", mapper.valueToTree(content));
            result.set("deferredSessions", ledger.deepCopy());
        }
        return result;
    }
}
