package com.lookahead.learning.content.service;

import com.lookahead.learning.content.validator.SnapshotValidator;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.model.PlanRecord;
import com.lookahead.learning.content.model.PlanVersion;
import com.lookahead.learning.content.model.MutationReceipt;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.util.PlanCardMetadata;
import com.lookahead.learning.content.util.StudyActivity;
import static com.lookahead.learning.content.util.PayloadReaders.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Profile("accounts")
public class PlanService {
    private final PlanRepository repository;
    private final PlanJson planJson;
    private final ObjectMapper mapper;
    private final SnapshotValidator validator;
    public PlanService(PlanRepository repository, ObjectMapper mapper, SnapshotValidator validator, PlanJson planJson) {
        this.repository = repository; this.mapper = mapper; this.validator = validator; this.planJson = planJson;
    }
    public record Mutation(int status, JsonNode data) {}

    public Set<String> grants(UUID account) {
        return repository.grants(account);
    }

    public JsonNode catalogMetadata() { return validator.metadata(); }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public JsonNode get(UUID account, UUID id, UUID historicalVersion) {
        PlanRecord plan = plan(account, id, false);
        PlanVersion version = version(account, id, historicalVersion == null ? plan.version() : historicalVersion);
        return view(account, plan, version);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public JsonNode list(UUID account, int limit, String after) {
        require(limit > 0 && limit <= 100, "limit must be between 1 and 100");
        // An offset cursor is scoped by the owner on every query; it contains no private record ID.
        int offset = 0;
        if (after != null) {
            try { offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8)); }
            catch (Exception ex) { throw new IllegalArgumentException("Invalid plan cursor"); }
            require(offset >= 0 && offset <= 100000, "Invalid plan cursor");
        }
        var page = repository.list(account, limit + 1, offset);
        var rows = page.stream().limit(limit).map(plan -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("planId", plan.id().toString()); node.put("versionId", plan.version().toString());
            node.put("revision", plan.revision()); node.put("goal", plan.goal());
            node.put("createdAt", plan.createdAt()); node.put("updatedAt", plan.updatedAt());
            PlanVersion current = version(account, plan.id(), plan.version());
            node.set("card", PlanCardMetadata.from(current.snapshot(), plan.progress(), mapper));
            return node;
        }).toList();
        ObjectNode result = mapper.createObjectNode();
        result.set("plans", mapper.valueToTree(rows));
        if (page.size() > limit) result.put("nextCursor", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset + limit).getBytes(StandardCharsets.UTF_8)));
        else result.putNull("nextCursor");
        return result;
    }

    @Transactional
    public Mutation create(UUID account, UUID key, JsonNode body, boolean imported) {
        fields(body, imported ? "sourceSchemaVersion localSnapshot provenance" : "goal snapshot provenance");
        String operation = imported ? "import" : "create";
        Mutation repeat = replay(account, key, operation, body);
        if (repeat != null) return repeat;
        JsonNode local = body.path("localSnapshot");
        if (imported) require("study-plan-local/v1".equals(text(body, "sourceSchemaVersion", 64)), "Unsupported import source");
        JsonNode snapshot = imported ? local.path("snapshot") : body.path("snapshot");
        JsonNode provenance = body.path("provenance");
        var validated = validator.validate(snapshot, provenance, imported, grants(account));
        String goal = text(imported ? local : body, "goal", 160);
        JsonNode progress = imported ? validator.mapImportedProgress(local, validated) : emptyProgress();
        UUID id = UUID.randomUUID(), versionId = UUID.randomUUID();
        ObjectNode recovery = imported ? (ObjectNode) validator.validateImportedRecovery(local, grants(account)) : mapper.createObjectNode();
        if (!imported) {
            recovery.put("strategy", "none"); recovery.put("elapsedDays", 0);
            recovery.put("deadlineDays", snapshot.path("config").path("days").asInt());
            recovery.set("deferredContentIds", mapper.createArrayNode());
        }
        repository.createPlan(account, id, versionId, goal, progress);
        insertVersion(account, id, versionId, null, snapshot, validated.provenance(), recovery, imported ? "import" : "create", mapper.valueToTree(validated.assignmentContentIds()));
        if (imported) event(account, id, versionId, 1, "legacyImport", progress);
        return receipt(account, key, operation, body, id, 201, view(account, plan(account, id, false), version(account, id, versionId)));
    }

    @Transactional
    public Mutation activity(UUID account, UUID id, UUID key, JsonNode body) {
        fields(body, "expectedRevision versionId operations studyDay");
        Mutation repeat = replay(account, key, "activity:" + id, body);
        if (repeat != null) return repeat;
        PlanRecord plan = plan(account, id, true);
        expect(plan, body);
        require(plan.version().equals(uuid(text(body, "versionId", 100))), "Activity targets an inactive version");
        PlanVersion version = version(account, id, plan.version());
        int studyDay = body.has("studyDay") ? integer(body,"studyDay",1,version.snapshot().path("config").path("days").asInt()) : 0;
        Set<String> currentGrants = grants(account);
        // Membership was validated and pinned when this version was saved. A catalog release
        // cannot rewrite it or block editing one's own notes about retired content.
        Map<String, String> membership = new HashMap<>();
        version.membership().properties().forEach(e -> membership.put(e.getKey(), e.getValue().asText()));
        Set<String> canonicalIds = new HashSet<>(membership.values());
        ObjectNode progress = (ObjectNode) plan.progress().deepCopy();
        JsonNode operations = body.path("operations");
        require(operations.isArray() && operations.size() > 0 && operations.size() <= 100, "Supply 1 to 100 operations");
        long revision = plan.revision() + 1;
        for (JsonNode op : operations) {
            String type = text(op, "type", 40);
            String contentId;
            switch (type) {
                case "recordAttempt" -> {
                    fields(op, "type assignmentId canonicalContentId outcome");
                    String assignment = text(op, "assignmentId", 256);
                    contentId = text(op, "canonicalContentId", 256);
                    require(contentId.equals(membership.get(assignment)), "Attempt does not match assignment");
                    accessible(version.snapshot(), contentId, currentGrants);
                    String outcome = text(op, "outcome", 40);
                    require(Set.of("attempted", "needs-review").contains(outcome), "Invalid attempt outcome");
                    setMember(progress, "attemptedContentIds", contentId, true);
                    if (outcome.equals("needs-review")) setMember(progress, "needsReviewContentIds", contentId, true);
                    object(progress, "sessionOutcomes").put(assignment, outcome);
                    StudyActivity.record(progress,version.snapshot(),assignment,studyDay,mapper);
                }
                case "setSessionCompletion" -> {
                    fields(op, "type assignmentId completed");
                    String assignment = text(op, "assignmentId", 300);
                    contentId = membership.get(assignment);
                    if (contentId == null && studyDay > 0) {
                        JsonNode derived = StudyActivity.resolve(version.snapshot(),assignment,studyDay);
                        require(derived != null,"Unknown session");
                        contentId = StudyActivity.source(derived);
                        if (!canonicalIds.contains(contentId)) contentId = membership.get(contentId);
                        require(contentId != null && canonicalIds.contains(contentId),"Recall outside plan membership");
                        if (StudyActivity.dynamicDay(assignment) > 0)
                            require(StudyActivity.dailyEligible(version.snapshot(),progress,derived,contentId,studyDay),"Complete the original on an earlier study day before recalling it");
                    }
                    require(contentId != null, "Unknown session");
                    boolean complete = bool(op, "completed");
                    if (complete) accessible(version.snapshot(), contentId, currentGrants);
                    setMember(progress, "completedSessionIds", assignment, complete);
                    if (complete) {
                        object(progress, "sessionOutcomes").put(assignment, "completed");
                        StudyActivity.record(progress,version.snapshot(),assignment,studyDay,mapper);
                    }
                    else object(progress, "sessionOutcomes").remove(assignment);
                }
                case "setContentCompletion" -> {
                    fields(op, "type canonicalContentId completed");
                    contentId = text(op, "canonicalContentId", 256);
                    require(canonicalIds.contains(contentId), "Content outside active plan");
                    boolean complete = bool(op, "completed");
                    if (complete) accessible(version.snapshot(), contentId, currentGrants);
                    setMember(progress, "completedContentIds", contentId, complete);
                    if (complete) setMember(progress, "needsReviewContentIds", contentId, false);
                }
                case "setNote" -> {
                    fields(op, "type canonicalContentId text");
                    contentId = text(op, "canonicalContentId", 256);
                    require(knownInPlan(account, id, contentId), "Note content outside plan history");
                    String note = textAllowEmpty(op, "text", 1000);
                    if (note.isEmpty()) object(progress, "notes").remove(contentId);
                    else object(progress, "notes").put(contentId, note);
                }
                default -> throw new IllegalArgumentException("Unknown activity type");
            }
            event(account, id, plan.version(), revision, type, op);
        }
        repository.updateProgress(account, id, progress, revision);
        return receipt(account, key, "activity:" + id, body, id, 200, view(account, plan(account, id, false), version));
    }

    @Transactional
    public Mutation saveVersion(UUID account, UUID id, UUID key, JsonNode body) {
        fields(body, "expectedRevision goal snapshot provenance reason recovery");
        Mutation repeat = replay(account, key, "version:" + id, body);
        if (repeat != null) return repeat;
        PlanRecord plan = plan(account, id, true); expect(plan, body);
        PlanVersion prior = version(account, id, plan.version());
        JsonNode snapshot = body.path("snapshot"), recovery = body.path("recovery");
        String reason = text(body, "reason", 160);
        require(Set.of("recovery", "update-plan", "extend-deadline").contains(reason), "Invalid version reason");
        var validated = reason.equals("update-plan")
                ? validator.validate(snapshot, body.path("provenance"), false, grants(account))
                : validator.validateExistingVersion(snapshot, body.path("provenance"), grants(account));
        validateRecovery(prior, snapshot, validated.provenance(), recovery, reason);
        String goal = text(body, "goal", 160);
        UUID next = UUID.randomUUID(); long revision = plan.revision() + 1;
        insertVersion(account, id, next, plan.version(), snapshot, validated.provenance(), recovery, reason, mapper.valueToTree(validated.assignmentContentIds()));
        repository.activateVersion(account, id, next, revision, goal);
        event(account, id, next, revision, reason, recovery);
        return receipt(account, key, "version:" + id, body, id, 201, view(account, plan(account, id, false), version(account, id, next)));
    }

    private void validateRecovery(PlanVersion prior, JsonNode next, JsonNode pins, JsonNode recovery, String reason) {
        fields(recovery, "strategy elapsedDays deadlineDays deferredContentIds deferredSessions");
        String strategy = text(recovery, "strategy", 40);
        int elapsed = integer(recovery, "elapsedDays", 0, 180);
        int deadline = integer(recovery, "deadlineDays", 1, 180);
        require(elapsed <= deadline, "Elapsed days exceed this deadline");
        require(next.path("config").path("days").asInt() == deadline, "Snapshot horizon must match deadline");
        JsonNode deferred = recovery.path("deferredContentIds");
        require(deferred.isArray() && deferred.size() <= 10000, "Invalid deferred content list");
        Map<String, JsonNode> scheduledBefore = assignments(prior.snapshot());
        Map<String, JsonNode> oldAssignments = new LinkedHashMap<>(scheduledBefore), newAssignments = assignments(next);
        for (JsonNode a : prior.snapshot().path("futureReviews")) oldAssignments.putIfAbsent(a.path("id").asText(), a);
        for (JsonNode entry : prior.recovery().path("deferredSessions")) {
            JsonNode a = entry.path("assignment"); oldAssignments.putIfAbsent(a.path("id").asText(), a);
        }
        Set<String> oldContent = new HashSet<>();
        for (JsonNode a : oldAssignments.values()) oldContent.add(source(a));
        for (JsonNode item : deferred) require(item.isString() && oldContent.contains(item.asText()), "Unknown deferred content");
        int oldDeadline = prior.recovery().path("deadlineDays").asInt(prior.snapshot().path("config").path("days").asInt());
        require(elapsed >= prior.recovery().path("elapsedDays").asInt(), "Recovery cannot move elapsed time backwards");
        if (recovery.has("deferredSessions")) {
            JsonNode ledger = recovery.path("deferredSessions");
            require(ledger.isArray() && ledger.size() <= 10000, "Invalid deferred session ledger");
            for (JsonNode entry : ledger) {
                fields(entry, "assignment originalDay reason");
                require(Set.of("window-ended", "daily-budget", "prerequisite", "review-session", "review-spacing", "remaining-capacity")
                        .contains(text(entry, "reason", 40)), "Invalid deferral reason");
                JsonNode assignment = entry.path("assignment"), day = entry.path("originalDay");
                boolean found = false;
                if (day.isNull()) {
                    for (JsonNode candidate : prior.snapshot().path("futureReviews")) if (candidate.equals(assignment)) found = true;
                } else {
                    int original = integer(entry, "originalDay", 1, prior.snapshot().path("days").size());
                    for (JsonNode candidate : prior.snapshot().path("days").get(original - 1).path("assignments")) if (candidate.equals(assignment)) found = true;
                }
                if (!found) for (JsonNode oldEntry : prior.recovery().path("deferredSessions"))
                    if (oldEntry.path("assignment").equals(assignment) && oldEntry.path("originalDay").equals(day)) found = true;
                require(found, "Deferred session differs from original snapshot");
            }
        }
        if (reason.equals("update-plan")) {
            require(strategy.equals("none"), "A plan update must use strategy none");
            if (prior.snapshot().path("config").path("goalType").asText().equals("interview"))
                require(deadline == oldDeadline, "Move an interview deadline only with explicit extension");
            return;
        }
        require(next.path("config").path("dailyHours").equals(prior.snapshot().path("config").path("dailyHours")), "Recovery cannot increase the daily budget");
        for (String pin : List.of("algorithmVersion", "catalogVersion", "rankingVersion"))
            require(pins.path(pin).equals(prior.provenance().path(pin)), "Recovery must preserve version pins");
        require(pins.path("origin").equals(prior.provenance().path("origin")), "Recovery must preserve snapshot origin");
        if (reason.equals("recovery")) require(strategy.equals("fixed-window") && deadline == oldDeadline, "Recovery must preserve the interview deadline");
        else require(strategy.equals("explicit-extension") && deadline > oldDeadline, "Extension must explicitly move the deadline later");
        for (var entry : newAssignments.entrySet()) {
            JsonNode old = oldAssignments.get(entry.getKey());
            require(old != null && withoutReviewTiming(old).equals(withoutReviewTiming(entry.getValue())), "Recovery cannot replace or alter scheduled sessions");
            if (!old.equals(entry.getValue())) {
                JsonNode changed = entry.getValue();
                require("review".equals(changed.path("kind").asText()), "Only review timing fields may change");
                String parent = changed.path("requiredSessionId").asText(source(changed));
                int parentDay = assignmentDay(next, parent);
                int oldParentDay = assignmentDay(prior.snapshot(), parent);
                if (oldParentDay == 0) oldParentDay = old.path("reviewFromDay").asInt(0);
                int oldDue = old.path("reviewDueDay").asInt(assignmentDay(prior.snapshot(), entry.getKey()));
                int spacing = Math.max(1, oldDue - oldParentDay);
                require(parentDay > 0, "Changed review timing requires its scheduled parent");
                int due = integer(changed, "reviewDueDay", parentDay + spacing, deadline);
                require(assignmentDay(next, entry.getKey()) >= due, "Review scheduled before its due day");
                if (changed.has("reviewFromDay")) require(changed.path("reviewFromDay").asInt() == parentDay, "Review origin must match its parent day");
            }
        }
        Set<String> deferredIds = new HashSet<>(); deferred.forEach(n -> deferredIds.add(n.asText()));
        for (var entry : scheduledBefore.entrySet())
            if (!newAssignments.containsKey(entry.getKey())) require(deferredIds.contains(source(entry.getValue())), "Omitted work must be disclosed as deferred");
        // Days that have already elapsed may retain historical work, but cannot gain a new assignment.
        for (int day = 0; day < elapsed; day++) {
            Set<String> oldIds = new HashSet<>();
            if (day < prior.snapshot().path("days").size()) prior.snapshot().path("days").get(day).path("assignments").forEach(a -> oldIds.add(a.path("id").asText()));
            for (JsonNode a : next.path("days").get(day).path("assignments")) require(oldIds.contains(a.path("id").asText()), "Cannot schedule new work in elapsed days");
        }
    }

    private JsonNode withoutReviewTiming(JsonNode assignment) {
        ObjectNode result = (ObjectNode) assignment.deepCopy();
        result.remove("reviewFromDay"); result.remove("reviewDueDay"); return result;
    }
    private int assignmentDay(JsonNode snapshot, String id) {
        for (JsonNode day : snapshot.path("days")) for (JsonNode assignment : day.path("assignments"))
            if (assignment.path("id").asText().equals(id)) return day.path("day").asInt();
        return 0;
    }

    @Transactional
    public Mutation delete(UUID account, UUID id, UUID key, long revision) {
        ObjectNode body = mapper.createObjectNode().put("expectedRevision", revision);
        Mutation repeat = replay(account, key, "delete:" + id, body);
        if (repeat != null) return repeat;
        PlanRecord plan = plan(account, id, true); expect(plan, body);
        repository.tombstoneReceipts(account, id);
        repository.deletePlan(account, id);
        receipt(account, key, "delete:" + id, body, id, 204, mapper.nullNode());
        return new Mutation(204, mapper.nullNode());
    }

    private Mutation replay(UUID account, UUID key, String operation, JsonNode body) {
        // Serialize the key, including concurrent creates, without any out-of-transaction success cache.
        repository.lockMutation(account, key);
        Optional<MutationReceipt> stored = repository.findReceipt(account, key);
        if (stored.isEmpty()) return null;
        MutationReceipt row = stored.get();
        if (!row.requestHash().equals(planJson.digest(operation + ":" + planJson.canonical(body))))
            throw new AccountFailure(409, "IDEMPOTENCY_KEY_REUSED", "This mutation key already belongs to a different request");
        if (row.deleted()) throw new AccountFailure(410, "PLAN_DELETED", "This plan was deleted");
        int status = row.status();
        if (status == 204) return new Mutation(status, mapper.nullNode());
        // Recheck ownership and current access; an old receipt is never a new grant.
        plan(account, row.planId(), false);
        ObjectNode result = (ObjectNode) row.response().deepCopy();
        applyAccess(result, grants(account));
        return new Mutation(status, result);
    }

    private Mutation receipt(UUID account, UUID key, String operation, JsonNode request, UUID id, int status, JsonNode result) {
        repository.insertReceipt(account, key, planJson.digest(operation + ":" + planJson.canonical(request)), id, result, status);
        return new Mutation(status, result);
    }
    private void expect(PlanRecord plan, JsonNode request) {
        if (!request.has("expectedRevision")) throw new AccountFailure(428, "REVISION_REQUIRED", "An expected revision is required");
        JsonNode revision = request.path("expectedRevision");
        require(revision.isIntegralNumber() && revision.asLong() > 0, "Invalid revision");
        if (revision.asLong() != plan.revision()) throw new AccountFailure(409, "REVISION_CONFLICT", "The plan changed; reload before resolving your draft", plan.revision());
    }
    private PlanRecord plan(UUID owner, UUID id, boolean lock) {
        return repository.findPlan(owner, id, lock).orElseThrow(() ->
                new AccountFailure(404, "PLAN_NOT_FOUND", "Plan not found"));
    }

    private PlanVersion version(UUID owner, UUID id, UUID version) {
        return repository.findVersion(owner, id, version).orElseThrow(() ->
                new AccountFailure(404, "VERSION_NOT_FOUND", "Version not found"));
    }
    private void insertVersion(UUID owner, UUID id, UUID version, UUID parent, JsonNode snapshot, JsonNode pins, JsonNode recovery, String reason, JsonNode membership) {
        repository.insertVersion(owner, id, version, parent, snapshot, planJson.digest(planJson.canonical(snapshot)), pins, recovery, reason, membership);
    }
    private void event(UUID owner, UUID id, UUID version, long revision, String kind, JsonNode payload) {
        repository.insertActivity(owner, id, version, revision, kind, payload);
    }
    private ObjectNode view(UUID account, PlanRecord plan, PlanVersion version) {
        ObjectNode result = mapper.createObjectNode();
        result.put("planId", plan.id().toString()); result.put("versionId", version.id().toString()); result.put("revision", plan.revision());
        result.put("goal", plan.goal()); result.set("snapshot", version.snapshot()); result.set("provenance", version.provenance());
        result.set("progress", plan.progress()); result.set("recovery", version.recovery());
        result.put("createdAt", plan.createdAt()); result.put("updatedAt", plan.updatedAt());
        applyAccess(result, grants(account)); return result;
    }
    private void applyAccess(ObjectNode view, Set<String> grants) {
        Set<String> restricted = new TreeSet<>();
        for (JsonNode a : assignments(view.path("snapshot")).values())
            if (!validator.isAccessible(source(a), grants)) restricted.add(source(a));
        view.set("restrictedContentIds", mapper.valueToTree(restricted));
    }
    private void accessible(JsonNode snapshot, String content, Set<String> grants) {
        boolean allowed = validator.isAccessible(content, grants);
        if (!allowed) throw new AccountFailure(403, "CONTENT_ACCESS_DENIED", "Current account access does not permit this activity");
    }
    private boolean knownInPlan(UUID account, UUID id, String content) {
        return repository.containsHistoricalContent(account, id, content);
    }
    private Map<String, JsonNode> assignments(JsonNode snapshot) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode day : snapshot.path("days")) for (JsonNode a : day.path("assignments")) result.put(a.path("id").asText(), a);
        return result;
    }
    private String source(JsonNode a) { return validator.canonicalContentId(a.hasNonNull("sourceContentId") ? a.path("sourceContentId").asText() : a.path("id").asText()); }

    private ObjectNode emptyProgress() {
        ObjectNode node = mapper.createObjectNode();
        for (String key : List.of("completedContentIds", "completedSessionIds", "attemptedContentIds", "needsReviewContentIds")) node.set(key, mapper.createArrayNode());
        node.set("notes", mapper.createObjectNode()); node.set("sessionOutcomes", mapper.createObjectNode()); return node;
    }
    private ObjectNode object(ObjectNode parent, String key) { return (ObjectNode) parent.path(key); }
    private void setMember(ObjectNode progress, String field, String id, boolean present) {
        Set<String> values = new TreeSet<>(); progress.path(field).forEach(n -> values.add(n.asText()));
        if (present) values.add(id); else values.remove(id);
        progress.set(field, mapper.valueToTree(values));
    }

}
