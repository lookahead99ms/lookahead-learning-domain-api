package com.lookahead.learning.content.validator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.lookahead.learning.content.util.StudyActivity;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates storage inputs against mounted, backend-controlled publication metadata. */
@Component
@Profile("accounts")
public final class SnapshotValidator {
    private final ObjectMapper mapper;
    private final JsonNode catalog;
    private final Map<String, JsonNode> records = new HashMap<>();
    private final Set<String> topicIds = new HashSet<>();

    @Autowired
    public SnapshotValidator(ObjectMapper mapper, @Value("${app.accounts.catalog-path}") String catalogPath) {
        this(mapper, readCatalog(mapper, catalogPath));
    }

    public SnapshotValidator(ObjectMapper mapper, JsonNode catalog) {
        this.mapper = mapper;
        this.catalog = catalog.deepCopy();
        require("account-catalog/v1".equals(text(catalog, "schemaVersion", 64)), "Unsupported trusted catalog");
        require(text(catalog, "catalogVersion", 256).matches("sha256:[a-f0-9]{64}"), "Invalid catalog digest");
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
    }

    private static JsonNode readCatalog(ObjectMapper mapper, String path) {
        try {
            require(Files.size(Path.of(path)) <= 32 * 1024 * 1024, "Trusted catalog too large");
            return mapper.readTree(Files.readString(Path.of(path)));
        } catch (Exception ex) { throw new IllegalStateException("Cannot load trusted account catalog", ex); }
    }

    public Set<String> allTopicIds() { return Set.copyOf(topicIds); }
    public String catalogVersion() { return catalog.path("catalogVersion").asText(); }
    public JsonNode metadata() {
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "account-catalog/v1");
        result.put("catalogVersion", catalogVersion());
        result.set("algorithmVersions", catalog.path("algorithmVersions").deepCopy());
        result.putArray("variationPolicies").add("topic-tie-v1");
        result.putArray("studyActivityPolicies").add("completion-day-v1");
        result.set("rankingVersions", catalog.path("rankingVersions").deepCopy());
        result.set("topicIds", mapper.valueToTree(new TreeSet<>(topicIds)));
        return result;
    }

    public record ValidationResult(Map<String, String> assignmentContentIds, Set<String> canonicalContentIds,
                                   String validatedAgainstCatalogVersion, JsonNode provenance) {}

    public ValidationResult validate(JsonNode snapshot, JsonNode provenance, boolean legacyImport, Set<String> grants) {
        return validateSnapshot(snapshot, provenance, legacyImport, grants, false);
    }

    /** Only for an already-owned immutable version; the store must compare all original pins. */
    public ValidationResult validateExistingVersion(JsonNode snapshot, JsonNode provenance, Set<String> grants) {
        boolean legacy = "legacy-local-import".equals(provenance.path("origin").asText());
        return validateSnapshot(snapshot, provenance, legacy, grants, true);
    }

    public boolean isAccessible(String canonicalId, Set<String> grants) {
        JsonNode record = records.get(canonicalId);
        return record != null && (grants.contains("content:"+record.path("id").asText()) || strings(record, "topicIds", 100).stream().anyMatch(grants::contains));
    }

    /** Validates an already hash-verified authored source; this never enables client snapshot writes. */
    public Map<String, String> validateTemplateReferences(JsonNode template) {
        JsonNode pins = template.path("provenance");
        require("ready-made-schedule/v1".equals(text(pins, "algorithmVersion", 256)), "Unsupported template algorithm");
        require(catalogVersion().equals(text(pins, "catalogVersion", 256)), "Unresolvable template catalog version");
        String ranking = optionalText(pins, "rankingVersion", 256);
        require(ranking == null || strings(catalog, "rankingVersions", 100).contains(ranking), "Unresolvable template ranking version");
        Set<String> selected = new HashSet<>(strings(template, "topicIds", 100));
        require(!selected.isEmpty() && topicIds.containsAll(selected), "Unknown template topic");
        Map<String, String> canonical = new LinkedHashMap<>();
        Set<String> actualTopics = new HashSet<>();
        for (JsonNode reference : array(template, "references", 10000)) {
            String id = text(reference, "contentId", 256), topic = text(reference, "topicId", 256);
            JsonNode record = records.get(id);
            require(record != null && id.equals(record.path("id").asText()), "Template requires canonical published content IDs");
            require(selected.contains(topic) && strings(record, "topicIds", 100).contains(topic), "Template topic disagrees with catalog");
            require(reference.path("route").equals(record.path("route")), "Template route disagrees with catalog");
            require(reference.path("contentType").equals(record.path("contentType")), "Template content type disagrees with catalog");
            require(!reference.path("contentType").asText().equals("dsa-problem") || ranking != null, "Template DSA references require ranking pin");
            for (String dependency : strings(reference, "prerequisiteIds", 100))
                require(records.containsKey(dependency), "Unknown template prerequisite");
            require(canonical.putIfAbsent(id, id) == null, "Duplicate template reference");
            actualTopics.add(topic);
        }
        require(actualTopics.equals(selected), "Template selection must exactly cover its reference topics");
        for (String id : strings(template, "assumedPrerequisiteIds", 10000))
            require(records.containsKey(id), "Unknown assumed template prerequisite");
        return Map.copyOf(canonical);
    }

    private ValidationResult validateSnapshot(JsonNode snapshot, JsonNode provenance, boolean legacyImport,
                                              Set<String> grants, boolean existingVersion) {
        fields(snapshot, "config focusedDailyHours bufferHours includedTopics excludedTopics days weeks uniqueNewItems reviewAssignments schedulingVersion eligibleNewItems remainingNewItems blockedItems futureReviews overdueReviewCount mode topicCoverage");
        JsonNode config = snapshot.path("config");
        fields(config, "days dailyHours topicIds accessTopicIds goalType familiarity completedContentIds needsReviewContentIds variationKey");
        String variationKey = optionalText(config, "variationKey", 80);
        if (variationKey != null) require(variationKey.matches("topic-tie-v1-[0-9]+"), "Unsupported variation policy");
        int days = integer(config, "days", 1, 180);
        number(config, "dailyHours", 1, 15);
        Set<String> selected = new HashSet<>(strings(config, "topicIds", 100));
        require(topicIds.containsAll(selected), "Unknown selected topic");
        // accessTopicIds are preserved provenance, never authorization.
        require(topicIds.containsAll(strings(config, "accessTopicIds", 100)), "Unknown claimed topic");
        optionalEnum(config, "goalType", Set.of("learning", "interview"));
        if (config.has("familiarity")) {
            object(config.path("familiarity"));
            for (var entry : config.path("familiarity").properties()) {
                require(topicIds.contains(entry.getKey()), "Unknown familiarity topic");
                require(entry.getValue().isString() && Set.of("new", "refresh", "familiar").contains(entry.getValue().asText()), "Invalid familiarity");
            }
        }
        for (String field : List.of("completedContentIds", "needsReviewContentIds"))
            for (String id : optionalStrings(config, field, 10000)) authorized(id, null, grants);
        number(snapshot, "focusedDailyHours", 0, 15);
        number(snapshot, "bufferHours", 0, 15);
        for (String field : List.of("uniqueNewItems", "reviewAssignments")) integer(snapshot, field, 0, 10000);
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
            text(day, "phase", 300); text(day, "focus", 1000);
            integer(day, "newCount", 0, 10000); integer(day, "reviewCount", 0, 10000);
            number(day, "focusedMinutes", 0, 900);
            if (day.has("bufferMinutes")) number(day, "bufferMinutes", 0, 900);
            double minutes = 0;
            for (JsonNode assignment : array(day, "assignments", 10000)) {
                validateAssignment(assignment, grants, selected, assignments, canonical, true);
                minutes += assignment.path("minutes").asDouble();
                require(++count <= 10000, "Too many assignments");
            }
            require(minutes <= config.path("dailyHours").asDouble() * 60 + 0.0001, "Daily budget exceeded");
            require(Math.abs(minutes - day.path("focusedMinutes").asDouble()) < 0.0001, "Focused minutes disagree");
        }
        JsonNode weeks = array(snapshot, "weeks", 26);
        int dayIndex = 0;
        for (int index = 0; index < weeks.size(); index++) {
            JsonNode week = weeks.get(index);
            fields(week, "number label days");
            require(integer(week, "number", 1, 26) == index + 1, "Invalid week number");
            text(week, "label", 300);
            for (JsonNode day : array(week, "days", 7)) {
                require(dayIndex < days && day.equals(dayArray.get(dayIndex++)), "Weeks disagree with days");
            }
        }
        require(dayIndex == days, "Weeks omit days");
        if (snapshot.has("futureReviews")) for (JsonNode item : array(snapshot, "futureReviews", 10000))
            validateAssignment(item, grants, selected, new HashMap<>(), new HashSet<>(), false);
        if (snapshot.has("blockedItems")) for (JsonNode item : array(snapshot, "blockedItems", 10000)) {
            fields(item, "id title prerequisiteIds");
            authorized(text(item, "id", 256), null, grants); text(item, "title", 1000);
            // These references explain why an accessible item was not scheduled.
            // They do not add assignments, progress membership or access grants.
            for (String id : strings(item, "prerequisiteIds", 100))
                require(records.containsKey(id), "Unknown or unpublished content reference");
        }
        if (snapshot.has("topicCoverage")) for (JsonNode item : array(snapshot, "topicCoverage", 100)) {
            fields(item, "id title scheduledItems availableItems minutes representedOutcomes");
            require(selected.contains(text(item, "id", 256)), "Unknown coverage topic"); text(item, "title", 1000);
            for (String name : List.of("scheduledItems", "availableItems", "minutes", "representedOutcomes")) integer(item, name, 0, 10000000);
        }
        JsonNode pins = validateProvenance(snapshot, provenance, legacyImport, existingVersion);
        return new ValidationResult(Map.copyOf(assignments), Set.copyOf(canonical), catalogVersion(), pins);
    }

    private JsonNode validateProvenance(JsonNode snapshot, JsonNode provenance, boolean legacy, boolean existingVersion) {
        fields(provenance, "snapshotSchemaVersion algorithmVersion catalogVersion rankingVersion origin historicalProvenance validatedAgainstCatalogVersion");
        if (provenance.has("snapshotSchemaVersion")) require("study-plan/v1".equals(text(provenance, "snapshotSchemaVersion", 64)), "Unsupported snapshot schema");
        if (provenance.has("origin")) require((legacy ? "legacy-local-import" : "generated").equals(text(provenance, "origin", 64)), "Invalid snapshot origin");
        String algorithm = optionalText(provenance, "algorithmVersion", 256);
        String snapshotAlgorithm = optionalText(snapshot, "schedulingVersion", 256);
        require(Objects.equals(algorithm, snapshotAlgorithm), "Algorithm pin disagrees with snapshot");
        if (!existingVersion && algorithm != null) require(strings(catalog, "algorithmVersions", 100).contains(algorithm), "Unsupported algorithm version");
        String version = optionalText(provenance, "catalogVersion", 256);
        String ranking = optionalText(provenance, "rankingVersion", 256);
        if (!legacy && !existingVersion) {
            require(algorithm != null && catalogVersion().equals(version), "Generated plans require supported explicit pins");
            boolean dsa = false;
            for (JsonNode day : snapshot.path("days")) for (JsonNode item : day.path("assignments"))
                dsa |= item.path("contentType").asText().equals("dsa-problem");
            require(!dsa || ranking != null, "DSA plans require a ranking pin");
        }
        if (!existingVersion && version != null) require(catalogVersion().equals(version), "Unresolvable catalog version");
        if (!existingVersion && ranking != null) require(strings(catalog, "rankingVersions", 100).contains(ranking), "Unresolvable ranking version");
        if (existingVersion) return provenance.deepCopy();
        ObjectNode result = (ObjectNode) provenance.deepCopy();
        result.put("snapshotSchemaVersion", "study-plan/v1");
        result.put("origin", legacy ? "legacy-local-import" : "generated");
        result.put("validatedAgainstCatalogVersion", catalogVersion());
        result.remove("historicalProvenance");
        if (legacy && (version == null || algorithm == null || ranking == null)) result.put("historicalProvenance", "unknown");
        return result;
    }

    private void validateTopics(JsonNode snapshot, String name, Set<String> selected, Set<String> grants, boolean included) {
        for (JsonNode topic : array(snapshot, name, 100)) {
            fields(topic, "id path title description courseIds contentTypes");
            String id = text(topic, "id", 256);
            require(selected.contains(id), "Topic does not match selection");
            if (included) require(grants.contains(id) || records.values().stream().anyMatch(record->strings(record,"topicIds",100).contains(id) && isAccessible(record.path("id").asText(),grants)), "Topic access denied");
            enumValue(topic, "path", Set.of("learn", "grow", "look-ahead"));
            text(topic, "title", 1000); text(topic, "description", 2000);
            strings(topic, "courseIds", 100); optionalStrings(topic, "contentTypes", 30);
        }
    }

    private void validateAssignment(JsonNode item, Set<String> grants, Set<String> selected,
                                    Map<String, String> assignments, Set<String> canonical, boolean scheduled) {
        fields(item, "id kind activity topicId topicTitle title courseTitle contentType route minutes reviewFromDay sourceContentId prerequisiteIds reviewDueDay relatedLessonIds timebox instructions requiredSessionId coverageKey");
        String id = text(item, "id", 256);
        String source = optionalText(item, "sourceContentId", 256);
        String topic = text(item, "topicId", 256);
        require(selected.contains(topic), "Assignment topic not selected");
        JsonNode record = authorized(source == null ? id : source, topic, grants);
        require(item.path("route").equals(record.path("route")), "Assignment route disagrees with trusted catalog");
        require(item.path("contentType").equals(record.path("contentType")), "Content type disagrees with trusted catalog");
        String canonicalId = record.path("id").asText();
        if (scheduled) {
            require(assignments.putIfAbsent(id, canonicalId) == null, "Duplicate session ID");
            canonical.add(canonicalId);
        }
        enumValue(item, "kind", Set.of("new", "review"));
        enumValue(item, "activity", Set.of("Understand", "Practice", "Apply", "Recall", "Attempt", "Refresh", "Rehearse"));
        enumValue(item, "contentType", Set.of("q-and-a", "theory", "dsa-pattern", "dsa-problem", "system-design", "language-comparison", "guide"));
        for (String field : List.of("topicTitle", "title", "courseTitle")) text(item, field, 1000);
        number(item, "minutes", 1, 900);
        for (String field : List.of("reviewFromDay", "reviewDueDay")) if (item.has(field)) integer(item, field, 1, 10000);
        for (String field : List.of("prerequisiteIds", "relatedLessonIds"))
            for (String dependency : optionalStrings(item, field, 100)) authorized(dependency, null, grants);
        if (item.has("timebox")) require(item.path("timebox").isBoolean(), "Invalid timebox");
        optionalText(item, "instructions", 2000); optionalText(item, "requiredSessionId", 256); optionalText(item, "coverageKey", 512);
    }

    private JsonNode authorized(String id, String topic, Set<String> grants) {
        JsonNode record = records.get(id);
        require(record != null, "Unknown or unpublished content reference");
        List<String> topics = strings(record, "topicIds", 100);
        require((topic == null || topics.contains(topic)) && isAccessible(id,grants), "Content access denied");
        return record;
    }

    /** Preserves imported aggregate evidence without manufacturing attempt counts or timestamps. */
    public JsonNode mapImportedProgress(JsonNode local, ValidationResult validated) {
        fields(local, "schemaVersion revision goal rankingVersion catalogVersion snapshot completedIds shiftedDays recovery deferredSessions sessionOutcomes reviewNotes attemptedContentIds needsReviewContentIds history studyLog");
        require("study-plan-local/v1".equals(text(local, "schemaVersion", 64)), "Unsupported local snapshot");
        integer(local, "revision", 1, Integer.MAX_VALUE); text(local, "goal", 160); integer(local, "shiftedDays", 0, 10000);
        String localCatalog = optionalText(local, "catalogVersion", 256);
        require(Objects.equals(localCatalog, optionalText(validated.provenance(), "catalogVersion", 256)), "Imported catalog provenance disagrees with saved local pin");
        String localRanking = optionalText(local, "rankingVersion", 256);
        require(Objects.equals(localRanking, optionalText(validated.provenance(), "rankingVersion", 256)), "Imported ranking provenance disagrees with saved local pin");
        Set<String> content = new TreeSet<>(), sessions = new TreeSet<>();
        for (String id : strings(local, "completedIds", 10000)) {
            boolean session = validated.assignmentContentIds().containsKey(id) || StudyActivity.resolve(local.path("snapshot"),id,StudyActivity.dynamicDay(id)) != null;
            String canonicalId = canonicalContentId(id);
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
            result.set(field, mapper.valueToTree(values.stream().map(this::canonicalContentId).filter(validated.canonicalContentIds()::contains).distinct().toList()));
        }
        ObjectNode notes = mapper.createObjectNode(), outcomes = mapper.createObjectNode();
        if (local.has("reviewNotes")) {
            object(local.path("reviewNotes"));
            for (var entry : local.path("reviewNotes").properties()) {
                require(entry.getValue().isString(), "Invalid note");
                boundedText(entry.getKey(), 256); boundedText(entry.getValue().asText(), 1000);
                String canonicalId = canonicalContentId(entry.getKey());
                if (validated.canonicalContentIds().contains(canonicalId)) notes.set(canonicalId, entry.getValue());
            }
        }
        if (local.has("sessionOutcomes")) {
            object(local.path("sessionOutcomes"));
            for (var entry : local.path("sessionOutcomes").properties()) {
                boundedText(entry.getKey(), 256);
                require(entry.getValue().isString() && Set.of("attempted", "needs-review", "completed").contains(entry.getValue().asText()), "Invalid session outcome");
                if (validated.assignmentContentIds().containsKey(entry.getKey()) || StudyActivity.resolve(local.path("snapshot"),entry.getKey(),StudyActivity.dynamicDay(entry.getKey())) != null) outcomes.set(entry.getKey(), entry.getValue());
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
                JsonNode activity = StudyActivity.resolve(local.path("snapshot"),id,day);
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
            for (String id : strings(recovery, "deferredContentIds", 10000)) authorized(id, null, grants);
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
                validateAssignment(item, grants, selected, sessions, content, true);
                require(!scheduled.contains(item.path("id").asText()), "Deferred session is also scheduled");
            }
            if (local.has("recovery")) {
                Set<String> declared = new HashSet<>();
                for (String id : strings(recovery, "deferredContentIds", 10000)) declared.add(canonicalContentId(id));
                require(declared.equals(content), "Deferred content IDs disagree with session ledger");
            } else result.set("deferredContentIds", mapper.valueToTree(content));
            result.set("deferredSessions", ledger.deepCopy());
        }
        return result;
    }

    /** Canonicalizes a known metadata alias; unknown historical IDs remain opaque, not authorized. */
    public String canonicalContentId(String id) {
        JsonNode record = records.get(id);
        return record == null ? id : record.path("id").asText();
    }

    private static void fields(JsonNode node, String allowed) {
        object(node); Set<String> names = Set.of(allowed.split(" "));
        for (var entry : node.properties()) require(names.contains(entry.getKey()), "Unknown field: " + entry.getKey());
    }
    private static void object(JsonNode node) { require(node != null && node.isObject(), "Expected object"); }
    private static JsonNode array(JsonNode node, String name, int max) {
        JsonNode value = node.path(name); require(value.isArray() && value.size() <= max, "Invalid array: " + name); return value;
    }
    private static List<String> strings(JsonNode node, String name, int max) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(node, name, max)) { require(value.isString(), "Invalid identifier"); boundedText(value.asText(), 256); require(!value.asText().isBlank(), "Blank identifier"); result.add(value.asText()); }
        require(new HashSet<>(result).size() == result.size(), "Duplicate identifier"); return result;
    }
    private static List<String> optionalStrings(JsonNode node, String name, int max) { return node.has(name) ? strings(node, name, max) : List.of(); }
    private static String text(JsonNode node, String name, int max) { JsonNode value = node.path(name); require(value.isString(), "Invalid text: " + name); boundedText(value.asText(), max); return value.asText(); }
    private static String optionalText(JsonNode node, String name, int max) { return !node.has(name) || node.path(name).isNull() ? null : text(node, name, max); }
    private static void boundedText(String value, int max) { require(value.length() <= max && value.codePoints().noneMatch(c -> c < 32 && c != 10 && c != 9 && c != 13), "Invalid or oversized text"); }
    private static int integer(JsonNode node, String name, int min, int max) { JsonNode value = node.path(name); require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= min && value.asInt() <= max, "Invalid integer: " + name); return value.asInt(); }
    private static void number(JsonNode node, String name, double min, double max) { JsonNode value = node.path(name); require(value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= min && value.asDouble() <= max, "Invalid number: " + name); }
    private static void enumValue(JsonNode node, String name, Set<String> values) { require(values.contains(text(node, name, 64)), "Invalid value: " + name); }
    private static void optionalEnum(JsonNode node, String name, Set<String> values) { if (node.has(name)) enumValue(node, name, values); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
