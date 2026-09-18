package com.lookahead.learning.content.validator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Deterministic, lossless projection of an already hash-verified publication asset. */
public final class ReadyMadeTemplateMapper {
    public static final String ADAPTER_VERSION = "ready-made-to-study-plan/v1";
    private static final Set<String> CONTENT_TYPES = Set.of("q-and-a", "theory", "dsa-pattern", "dsa-problem", "system-design", "language-comparison", "guide");
    private final ObjectMapper mapper;

    public ReadyMadeTemplateMapper(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    public record Mapping(JsonNode snapshot, JsonNode provenance, Map<String, String> assignmentContentIds,
                          Set<String> topicIds) {
        public Mapping {
            snapshot = snapshot.deepCopy();
            provenance = provenance.deepCopy();
            assignmentContentIds = Map.copyOf(assignmentContentIds);
            topicIds = Set.copyOf(topicIds);
        }

        @Override public JsonNode snapshot() { return snapshot.deepCopy(); }
        @Override public JsonNode provenance() { return provenance.deepCopy(); }
    }

    private record PriorSession(int day, JsonNode session) {}

    /** The caller must verify rawSha256 against allowlisted bytes before parsing this input. */
    public Mapping map(JsonNode template, String rawSha256) {
        require(rawSha256 != null && rawSha256.matches("[a-f0-9]{64}"), "Invalid raw template digest");
        fields(template, "schemaVersion templateId templateVersion pathId durationDays dailyHours availabilityUnit intensive intendedUse provenance startingKnowledge assumedPrerequisiteIds topicIds references days coverage futureReviews");
        require("study-plan-template/v1".equals(text(template, "schemaVersion", 64)), "Unsupported template schema");
        String templateId = text(template, "templateId", 200);
        String pathId = text(template, "pathId", 160);
        require(pathId.matches("[a-z0-9-]+"), "Invalid template path ID");
        int duration = integer(template, "durationDays", 1, 180);
        require(Set.of(7, 14, 21, 30, 60, 90, 120, 180).contains(duration), "Unsupported template duration");
        int hours = integer(template, "dailyHours", 1, 9);
        require(Set.of(1, 2, 3, 4, 6, 9).contains(hours), "Unsupported template availability");
        require(templateId.equals(pathId + "-d" + duration + "-h" + hours), "Template identity disagrees with variant");
        require("hours-per-day".equals(text(template, "availabilityUnit", 64)), "Ambiguous template availability");
        require(template.path("intensive").isBoolean() && template.path("intensive").asBoolean() == (hours >= 6), "Invalid intensive label");
        text(template, "intendedUse", 2000);
        strings(template, "startingKnowledge", 100, 2000);
        String templateVersion = digest(template, "templateVersion");
        JsonNode pins = template.path("provenance");
        fields(pins, "algorithmVersion catalogVersion rankingVersion blueprintVersion sourceContentVersion");
        require("ready-made-schedule/v1".equals(text(pins, "algorithmVersion", 64)), "Unsupported template algorithm");
        digest(pins, "catalogVersion"); digest(pins, "blueprintVersion"); digest(pins, "sourceContentVersion");
        require(pins.has("rankingVersion"), "Missing ranking pin");
        if (!pins.path("rankingVersion").isNull()) text(pins, "rankingVersion", 256);
        verifyTemplateVersion(template, templateVersion);

        List<String> topics = strings(template, "topicIds", 100, 256);
        require(!topics.isEmpty(), "Template has no selected scope");
        Set<String> selectedTopics = new LinkedHashSet<>(topics);
        for (String topic : topics) require(topic.matches("(?:learn|grow|look-ahead):[a-z0-9-]+"), "Invalid course scope");
        Map<String, JsonNode> references = references(template, selectedTopics);
        Set<String> assumptions = new HashSet<>(strings(template, "assumedPrerequisiteIds", 10000, 256));
        require(references.keySet().containsAll(assumptions), "Unresolved assumed prerequisite");
        require(duration <= 14 || assumptions.isEmpty(), "Long template silently assumes omitted prerequisites");

        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("template", template.deepCopy());
        ObjectNode config = snapshot.putObject("config");
        config.put("days", duration).put("dailyHours", hours);
        config.set("topicIds", template.path("topicIds").deepCopy());
        config.set("accessTopicIds", template.path("topicIds").deepCopy());
        config.put("goalType", duration <= 30 ? "interview" : "learning");
        snapshot.set("includedTopics", topics(topics, references));
        snapshot.putArray("excludedTopics");
        ArrayNode days = snapshot.putArray("days");
        Map<String, String> assignments = new LinkedHashMap<>();
        Map<String, PriorSession> prior = new LinkedHashMap<>();
        Set<String> sessionIds = new HashSet<>(), actualContent = new HashSet<>();
        int newCount = 0, reviewCount = 0, focusedTotal = 0, recoveryTotal = 0, unusedTotal = 0;
        JsonNode sourceDays = array(template, "days", 180);
        require(sourceDays.size() == duration, "Template day count disagrees");
        for (int index = 0; index < duration; index++) {
            JsonNode sourceDay = sourceDays.get(index);
            fields(sourceDay, "day phase sessions scheduledMinutes focusedMinutes recoveryMinutes unallocatedMinutes");
            int dayNumber = integer(sourceDay, "day", 1, 180);
            require(dayNumber == index + 1, "Template days must be sequential");
            enumValue(sourceDay, "phase", Set.of("revision", "learning", "reinforcement", "recovery"));
            ObjectNode day = days.addObject();
            day.put("day", dayNumber).put("phase", sourceDay.path("phase").asText());
            ArrayNode projected = day.putArray("assignments");
            Set<String> courseTitles = new LinkedHashSet<>();
            int focused = 0, recovery = 0, dailyNew = 0, dailyReview = 0;
            for (JsonNode session : array(sourceDay, "sessions", 10000)) {
                validateSession(session, templateId, dayNumber, duration, references, prior, sessionIds, actualContent, assumptions, false);
                int minutes = session.path("minutes").asInt();
                if ("recovery".equals(session.path("kind").asText())) { recovery += minutes; continue; }
                String contentId = session.path("contentId").asText();
                boolean repeated = actualContent.contains(contentId);
                String kind = "new".equals(session.path("kind").asText()) || ("practice".equals(session.path("kind").asText()) && !repeated) ? "new" : "review";
                ObjectNode item = assignment(session, references.get(contentId), kind);
                projected.add(item);
                assignments.put(session.path("id").asText(), contentId);
                require(assignments.size() <= 10000, "Too many scheduled sessions");
                prior.put(session.path("id").asText(), new PriorSession(dayNumber, session));
                actualContent.add(contentId);
                courseTitles.add(item.path("courseTitle").asText());
                focused += minutes;
                if ("new".equals(kind)) dailyNew++; else dailyReview++;
            }
            int unused = integer(sourceDay, "unallocatedMinutes", 0, 900);
            require(integer(sourceDay, "focusedMinutes", 0, 900) == focused && integer(sourceDay, "recoveryMinutes", 0, 900) == recovery, "Session minute kinds disagree");
            require(integer(sourceDay, "scheduledMinutes", 0, 900) == focused + recovery && focused + recovery + unused == hours * 60, "Template daily availability disagrees");
            day.put("focus", courseTitles.isEmpty() ? "Open study time" : String.join(" · ", courseTitles));
            day.put("newCount", dailyNew).put("reviewCount", dailyReview).put("focusedMinutes", focused).put("bufferMinutes", recovery + unused);
            newCount += dailyNew; reviewCount += dailyReview; focusedTotal += focused; recoveryTotal += recovery; unusedTotal += unused;
        }
        ArrayNode future = snapshot.putArray("futureReviews");
        for (JsonNode session : array(template, "futureReviews", 10000)) {
            validateSession(session, templateId, 0, duration, references, prior, sessionIds, actualContent, assumptions, true);
            future.add(assignment(session, references.get(session.path("contentId").asText()), "review"));
        }
        validateCoverage(template.path("coverage"), actualContent, focusedTotal, recoveryTotal, unusedTotal);
        if (actualContent.stream().map(references::get).anyMatch(reference -> "dsa-problem".equals(reference.path("contentType").asText())))
            require(!pins.path("rankingVersion").isNull(), "DSA template requires a ranking pin");
        ArrayNode weeks = snapshot.putArray("weeks");
        for (int start = 0; start < duration; start += 7) {
            ObjectNode week = weeks.addObject();
            week.put("number", start / 7 + 1).put("label", days.get(start).path("phase").asText());
            ArrayNode weekDays = week.putArray("days");
            for (int offset = start; offset < Math.min(start + 7, duration); offset++) weekDays.add(days.get(offset).deepCopy());
        }
        // Existing daily selection/recovery reads these fields as a cap, not a horizon average.
        // Exact variable daily recovery/unused budgets remain in the authored template and days.
        snapshot.put("focusedDailyHours", hours);
        snapshot.put("bufferHours", 0);
        snapshot.put("uniqueNewItems", newCount).put("reviewAssignments", reviewCount);
        snapshot.put("schedulingVersion", "ready-made-schedule/v1");
        snapshot.put("eligibleNewItems", template.path("coverage").path("availableContentCount").asInt());
        snapshot.put("remainingNewItems", template.path("coverage").path("uncoveredContentIds").size());
        snapshot.put("mode", duration <= 14 ? "interview-revision" : "learning");
        ObjectNode provenance = mapper.createObjectNode();
        provenance.put("snapshotSchemaVersion", "study-plan/v1").put("origin", "ready-made-template");
        for (String name : List.of("algorithmVersion", "catalogVersion", "rankingVersion")) provenance.set(name, pins.path(name).deepCopy());
        ObjectNode templatePins = provenance.putObject("template");
        templatePins.put("templateId", templateId).put("templateVersion", templateVersion).put("templateSha256", rawSha256).put("pathId", pathId);
        templatePins.set("blueprintVersion", pins.path("blueprintVersion").deepCopy());
        templatePins.set("sourceContentVersion", pins.path("sourceContentVersion").deepCopy());
        templatePins.put("adapterVersion", ADAPTER_VERSION);
        return new Mapping(snapshot, provenance, assignments, selectedTopics);
    }

    private Map<String, JsonNode> references(JsonNode template, Set<String> topics) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        Set<String> actualTopics = new HashSet<>();
        for (JsonNode reference : array(template, "references", 10000)) {
            fields(reference, "contentId topicId title courseTitle contentType route contentVersion prerequisiteIds estimatedReadMinutes");
            String id = text(reference, "contentId", 256), topic = text(reference, "topicId", 256);
            require(result.putIfAbsent(id, reference) == null, "Duplicate template reference");
            require(topics.contains(topic), "Reference course is outside selected scope");
            actualTopics.add(topic);
            for (String name : List.of("title", "courseTitle")) text(reference, name, 1000);
            enumValue(reference, "contentType", CONTENT_TYPES);
            JsonNode route = array(reference, "route", 12);
            require(route.size() >= 3, "Invalid canonical route");
            for (JsonNode part : route) string(part, 256);
            require("/".equals(route.get(0).asText()) && topic.equals(route.get(1).asText() + ":" + route.get(2).asText()), "Reference route disagrees with course");
            text(reference, "contentVersion", 256);
            strings(reference, "prerequisiteIds", 100, 256);
            if (reference.has("estimatedReadMinutes") && !reference.path("estimatedReadMinutes").isNull()) integer(reference, "estimatedReadMinutes", 1, Integer.MAX_VALUE);
        }
        require(!result.isEmpty() && actualTopics.equals(topics), "Template selected scope is not exact");
        for (JsonNode reference : result.values()) require(result.keySet().containsAll(strings(reference, "prerequisiteIds", 100, 256)), "Missing reference prerequisite");
        return result;
    }

    private void validateSession(JsonNode session, String templateId, int day, int duration,
                                 Map<String, JsonNode> references, Map<String, PriorSession> prior,
                                 Set<String> sessionIds, Set<String> actualContent, Set<String> assumptions, boolean future) {
        fields(session, "id kind activity contentId minutes instructions prerequisiteIds requiredSessionIds review");
        String id = text(session, "id", 256), kind = text(session, "kind", 64), activity = text(session, "activity", 64);
        require(id.startsWith(templateId + "-") && sessionIds.add(id), "Unstable or duplicate template session ID");
        require(sessionIds.size() <= 20000, "Too many template sessions");
        integer(session, "minutes", 1, 900); text(session, "instructions", 2000);
        List<String> dependencies = strings(session, "requiredSessionIds", 100, 256);
        require(prior.keySet().containsAll(dependencies), "Session requires missing or later work");
        List<String> prerequisites = strings(session, "prerequisiteIds", 100, 256);
        require(references.keySet().containsAll(prerequisites), "Missing session prerequisite reference");
        require(session.has("contentId"), "Missing session content reference");
        if ("recovery".equals(kind)) {
            require(!future && "Recover".equals(activity) && session.path("contentId").isNull() && dependencies.isEmpty() && prerequisites.isEmpty() && !session.has("review"), "Recovery cannot be curriculum or completion work");
            return;
        }
        String contentId = text(session, "contentId", 256);
        require(references.containsKey(contentId), "Unresolved session content reference");
        for (String prerequisite : prerequisites) require(actualContent.contains(prerequisite) || assumptions.contains(prerequisite), "Prerequisite is neither earlier nor explicitly assumed");
        if ("new".equals(kind)) require(!future && duration > 14 && "Understand".equals(activity), "Invalid new learning session");
        else if ("practice".equals(kind)) require(!future && Set.of("Practice", "Attempt", "Apply", "Rehearse").contains(activity) && !dependencies.isEmpty() && !prerequisites.isEmpty(), "Invalid practice session");
        else require("review".equals(kind) && Set.of("Refresh", "Recall").contains(activity), "Unsupported template session kind or activity");
        if (!"review".equals(kind)) { require(!session.has("review"), "Non-review session has review provenance"); return; }
        JsonNode review = session.path("review");
        fields(review, "basis sourceSessionId sourceDay dueDay");
        String basis = text(review, "basis", 64);
        int dueDay = integer(review, "dueDay", 1, 10000);
        require(review.has("sourceSessionId") && review.has("sourceDay"), "Review source must be explicit");
        if ("declared-familiarity".equals(basis)) {
            require(!future && duration <= 14 && "Refresh".equals(activity) && review.path("sourceSessionId").isNull() && review.path("sourceDay").isNull() && dueDay <= day, "Invalid declared familiarity review");
        } else {
            require("scheduled-session".equals(basis) && "Recall".equals(activity), "Unsupported review basis");
            String sourceId = text(review, "sourceSessionId", 256);
            PriorSession source = prior.get(sourceId);
            require(source != null && source.session().path("contentId").asText().equals(contentId) && dependencies.contains(sourceId), "Review source or dependency disagrees");
            require(integer(review, "sourceDay", 1, duration) == source.day() && dueDay > source.day(), "Review source day disagrees");
            require(future ? dueDay > duration : dueDay <= day && day <= dueDay + 2, "Review is outside its scheduled window");
        }
    }

    private ObjectNode assignment(JsonNode session, JsonNode reference, String kind) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", session.path("id").asText()).put("kind", kind).put("templateKind", session.path("kind").asText());
        item.set("activity", session.path("activity").deepCopy());
        for (String name : List.of("topicId", "title", "courseTitle", "contentType", "route")) item.set(name, reference.path(name).deepCopy());
        item.set("topicTitle", reference.path("courseTitle").deepCopy());
        item.set("sourceContentId", reference.path("contentId").deepCopy());
        for (String name : List.of("minutes", "instructions", "prerequisiteIds", "requiredSessionIds")) item.set(name, session.path(name).deepCopy());
        if (!session.path("requiredSessionIds").isEmpty()) item.set("requiredSessionId", session.path("requiredSessionIds").get(0).deepCopy());
        if (session.has("review")) {
            JsonNode review = session.path("review");
            item.set("reviewBasis", review.path("basis").deepCopy());
            if (!review.path("sourceSessionId").isNull()) item.set("reviewSourceSessionId", review.path("sourceSessionId").deepCopy());
            if (!review.path("sourceDay").isNull()) item.set("reviewFromDay", review.path("sourceDay").deepCopy());
            item.set("reviewDueDay", review.path("dueDay").deepCopy());
        }
        return item;
    }

    private ArrayNode topics(List<String> topics, Map<String, JsonNode> references) {
        ArrayNode result = mapper.createArrayNode();
        for (String topic : topics) {
            List<JsonNode> matching = references.values().stream().filter(reference -> topic.equals(reference.path("topicId").asText())).toList();
            ObjectNode item = result.addObject();
            item.put("id", topic).put("path", topic.substring(0, topic.indexOf(':')));
            item.put("title", matching.getFirst().path("courseTitle").asText()).put("description", "");
            ArrayNode courses = item.putArray("courseIds");
            matching.stream().map(reference -> reference.path("route").get(2).asText()).distinct().forEach(courses::add);
        }
        return result;
    }

    private static void validateCoverage(JsonNode coverage, Set<String> actual, int focused, int recovery, int unused) {
        fields(coverage, "selectedContentCount availableContentCount selectedUnitIds availableUnitCount uncoveredUnitIds scheduledMinutes focusedMinutes recoveryMinutes unallocatedMinutes uncoveredContentIds uncoveredScope");
        int selected = integer(coverage, "selectedContentCount", 1, 100000);
        int available = integer(coverage, "availableContentCount", 1, 100000);
        List<String> uncovered = strings(coverage, "uncoveredContentIds", 100000, 256);
        require(selected == actual.size() && available == selected + uncovered.size() && Collections.disjoint(actual, uncovered), "Finite content coverage disagrees");
        List<String> units = strings(coverage, "selectedUnitIds", 10000, 256), omitted = strings(coverage, "uncoveredUnitIds", 10000, 256);
        require(!units.isEmpty() && Collections.disjoint(units, omitted) && integer(coverage, "availableUnitCount", 1, 100000) == units.size() + omitted.size(), "Finite unit coverage disagrees");
        strings(coverage, "uncoveredScope", 1000, 2000);
        require(integer(coverage, "focusedMinutes", 1, 10000000) == focused && integer(coverage, "recoveryMinutes", 0, 10000000) == recovery && integer(coverage, "scheduledMinutes", 1, 10000000) == focused + recovery && integer(coverage, "unallocatedMinutes", 0, 10000000) == unused, "Aggregate template minutes disagree");
    }

    private void verifyTemplateVersion(JsonNode template, String expected) {
        ObjectNode unversioned = (ObjectNode) template.deepCopy();
        unversioned.remove("templateVersion");
        try {
            byte[] canonical = (mapper.writeValueAsString(sorted(unversioned)) + "\n").getBytes(StandardCharsets.UTF_8);
            String actual = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            require(expected.equals(actual), "Immutable template version disagrees with content");
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Cannot verify immutable template version", ex); }
    }

    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Map<String, JsonNode> entries = new TreeMap<>();
            for (var entry : value.properties()) entries.put(entry.getKey(), entry.getValue());
            entries.forEach((name, child) -> result.set(name, sorted(child)));
            return result;
        }
        if (value.isArray()) { ArrayNode result = mapper.createArrayNode(); for (JsonNode child : value) result.add(sorted(child)); return result; }
        return value.deepCopy();
    }

    private static void fields(JsonNode node, String allowed) {
        require(node != null && node.isObject(), "Expected template object");
        Set<String> names = Set.of(allowed.split(" "));
        for (var entry : node.properties()) require(names.contains(entry.getKey()), "Unknown template field: " + entry.getKey());
    }
    private static JsonNode array(JsonNode node, String name, int max) { JsonNode value = node.path(name); require(value.isArray() && value.size() <= max, "Invalid template array: " + name); return value; }
    private static List<String> strings(JsonNode node, String name, int max, int length) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(node, name, max)) result.add(string(value, length));
        require(new HashSet<>(result).size() == result.size(), "Duplicate template identifier: " + name);
        return result;
    }
    private static String string(JsonNode value, int max) { require(value.isString() && !value.asText().isBlank() && value.asText().length() <= max && value.asText().codePoints().noneMatch(c -> c < 32 && c != 10 && c != 9 && c != 13), "Invalid template text"); return value.asText(); }
    private static String text(JsonNode node, String name, int max) { return string(node.path(name), max); }
    private static String digest(JsonNode node, String name) { String value = text(node, name, 71); require(value.matches("sha256:[a-f0-9]{64}"), "Invalid immutable pin: " + name); return value; }
    private static int integer(JsonNode node, String name, int min, int max) { JsonNode value = node.path(name); require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= min && value.asInt() <= max, "Invalid template integer: " + name); return value.asInt(); }
    private static void enumValue(JsonNode node, String name, Set<String> values) { require(values.contains(text(node, name, 64)), "Invalid template value: " + name); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
