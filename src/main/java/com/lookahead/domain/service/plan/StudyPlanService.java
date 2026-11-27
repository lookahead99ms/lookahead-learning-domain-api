package com.lookahead.domain.service.plan;

import static com.lookahead.domain.util.JsonChecks.require;
import com.lookahead.domain.db.store.membership.MembershipGrants;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.content.ProtectedContentManifestService;
import com.lookahead.domain.util.MembershipRules;
import com.lookahead.domain.validator.StudyPlanImportValidator;
import com.lookahead.domain.validator.StudyPlanRecoveryValidator;
import com.lookahead.domain.validator.StudyPlanSnapshotValidator;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.db.store.plan.StudyPlan;
import com.lookahead.domain.db.store.plan.StudyPlanReceipts;
import com.lookahead.domain.db.store.plan.StudyPlanVersions;
import com.lookahead.domain.model.StudyPlanRecord;
import com.lookahead.domain.model.StudyPlanVersion;
import com.lookahead.domain.model.StudyPlanMutationReceipt;
import com.lookahead.domain.util.CatalogIds;
import com.lookahead.domain.util.StudyPlanJson;
import com.lookahead.domain.util.StudyPlanCardMetadata;
import com.lookahead.domain.util.StudyPlanActivity;
import static com.lookahead.domain.util.PayloadReaders.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Profile("accounts")
public class StudyPlanService {
    private final StudyPlan plans;
    private final StudyPlanVersions planVersions;
    private final StudyPlanReceipts planReceipts;
    private final StudyPlanJson planJson;
    private final ObjectMapper mapper;
    private final StudyPlanSnapshotValidator validator;
    private final AccountCatalogService catalog;
    private final StudyPlanImportValidator imports;
    private final StudyPlanRecoveryValidator recoveryValidator;
    private StudyPlanLifecycleService lifecycle;
    private StudyPlanReadyMadeWriteService readyMade;
    private StudyPlanFreeAllowanceService freeAllowance;
    @Autowired
    public void configureLifecycle(StudyPlanLifecycleService lifecycle,StudyPlanReadyMadeWriteService readyMade){this.lifecycle=lifecycle;this.readyMade=readyMade;}
    @Autowired
    public void configureFreeAllowance(StudyPlanFreeAllowanceService freeAllowance){this.freeAllowance=freeAllowance;}
    public StudyPlanService(StudyPlan plans, StudyPlanVersions planVersions, StudyPlanReceipts planReceipts, ObjectMapper mapper, StudyPlanSnapshotValidator validator, AccountCatalogService catalog, StudyPlanJson planJson, StudyPlanRecoveryValidator recoveryValidator) {
        this.plans = plans; this.planVersions = planVersions; this.planReceipts = planReceipts; this.mapper = mapper; this.validator = validator; this.catalog = catalog; this.planJson = planJson; this.recoveryValidator = recoveryValidator;
        this.imports = new StudyPlanImportValidator(mapper, validator, catalog.catalog());
    }
    /**
     * What a plan change did. DLV-1016: the service used to name the HTTP status itself ({@code Mutation(int status, …)});
     * the controller now chooses the status from this. A replayed request answers with the outcome of its first run.
     */
    public enum Outcome {
        /** A new plan or a new version of a plan was stored. */ CREATED,
        /** The plan was changed in place: its name, its activity or its lifecycle state. */ CHANGED,
        /** The plan was deleted: there is no content to answer with. */ DELETED;
        /**
         * The number a replay receipt keeps for this outcome. It is the receipt's stored format (attribute
         * {@code status}: 201, 200 or 204), written and read back unchanged since the receipts were introduced.
         */
        int receiptCode() { return switch (this) { case CREATED -> 201; case CHANGED -> 200; case DELETED -> 204; }; }
        /** The outcome a stored receipt number stands for; a receipt is only ever written with one of the three. */
        static Outcome ofReceiptCode(int code) {
            return switch (code) {
                case 201 -> CREATED; case 200 -> CHANGED; case 204 -> DELETED;
                default -> throw new IllegalStateException("Plan receipt with unknown status " + code);
            };
        }
    }
    public record Mutation(Outcome outcome, JsonNode data) {}

    // DLV-1016: what an account may put into a plan is decided here (it was computed inside the plan repository, a store
    // that held three services for it). The three collaborators are optional, as they were there.
    private AccountGrantService grantAccounts;
    private MembershipGrants grantMemberships;
    private ProtectedContentManifestService grantPublication;
    @Autowired(required=false)
    public void configureGrantAccounts(AccountGrantService value){grantAccounts=value;}
    @Autowired(required=false)
    public void configureGrantMemberships(MembershipGrants value){grantMemberships=value;}
    @Autowired(required=false)
    public void configureGrantPublication(ProtectedContentManifestService value){grantPublication=value;}

    public Set<String> grants(UUID account) {
        // AccountGrantService.findTopicGrants already includes grants, staff assignment, owner check and memberships.
        // A staff scope never authorizes plan content.
        var result = new HashSet<String>();
        if (grantAccounts != null) result.addAll(CatalogIds.curriculumGrants(grantAccounts.findTopicGrants(account)));
        else if (grantMemberships != null) result.addAll(MembershipRules.topics(grantMemberships.catalogScopes(account), catalog.allTopicIds()));
        if (grantPublication != null && AccountGrantService.currentIdentityEnabled(account))
            grantPublication.contentGrants(Set.copyOf(result)).forEach(id -> result.add("content:" + id));
        return result;
    }

    public JsonNode catalogMetadata() {
        ObjectNode metadata = (ObjectNode) catalog.metadata().deepCopy();
        metadata.putArray("planNamingPolicies").add("plan-name-v1");
        if(lifecycle!=null){metadata.putArray("planLifecyclePolicies").add("dated-plan-v1");
            metadata.putArray("readyMadePlanPolicies").add("ready-made-template-v1");}
        return metadata;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JsonNode get(UUID account, UUID id, UUID historicalVersion) {
        StudyPlanRecord plan = plan(account, id, false);
        StudyPlanVersion version = version(account, id, historicalVersion == null ? plan.version() : historicalVersion);
        return view(account, plan, version);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JsonNode list(UUID account, int limit, String after) {
        require(limit > 0 && limit <= 100, "limit must be between 1 and 100");
        // An offset cursor is scoped by the owner on every query; it contains no private record ID.
        int offset = 0;
        if (after != null) {
            try { offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8)); }
            catch (Exception ex) { throw new IllegalArgumentException("Invalid plan cursor"); }
            require(offset >= 0 && offset <= 100000, "Invalid plan cursor");
        }
        var page = plans.list(account, limit + 1, offset);
        Set<String> currentGrants = freeAllowance == null ? Set.of() : grants(account);
        var rows = page.stream().limit(limit).map(plan -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("planId", plan.id().toString()); node.put("versionId", plan.version().toString());
            node.put("revision", plan.revision()); node.put("goal", plan.goal());
            node.put("name", plan.name()); node.put("planNumber", plan.planNumber());
            node.put("createdAt", plan.createdAt()); node.put("updatedAt", plan.updatedAt());
            StudyPlanVersion current = version(account, plan.id(), plan.version());
            node.set("card", StudyPlanCardMetadata.from(current.snapshot(), plan.progress(), mapper));
            if(freeAllowance!=null){Set<String> content=new HashSet<>();
                current.membership().properties().forEach(entry->content.add(entry.getValue().asText()));
                node.put("freeContentPlan",freeAllowance.freeContent(content));
                node.put("accessNeeded",content.stream().anyMatch(id->!catalog.isAccessible(id,currentGrants)));}
            if(lifecycle!=null)node.set("lifecycle",lifecycle.view(account,plan.id()));
            return node;
        }).toList();
        ObjectNode result = mapper.createObjectNode();
        result.set("plans", mapper.valueToTree(rows));
        result.put("nextPlanNumber", plans.nextPlanNumber(account));
        if (page.size() > limit) result.put("nextCursor", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset + limit).getBytes(StandardCharsets.UTF_8)));
        else result.putNull("nextCursor");
        return result;
    }

    @Transactional
    public Mutation create(UUID account, UUID key, JsonNode raw, boolean imported) {
        fields(raw, imported ? "sourceSchemaVersion localSnapshot provenance startDate" : "goal snapshot provenance name startDate");
        String operation = imported ? "import" : "create";
        // Retry receipts hash the request exactly as sent; everything after works on blaze IDs.
        Mutation repeat = replay(account, key, operation, raw);
        if (repeat != null) return repeat;
        JsonNode body = CatalogIds.normalize(raw);
        JsonNode local = body.path("localSnapshot");
        if (imported) require("study-plan-local/v1".equals(text(body, "sourceSchemaVersion", 64)), "Unsupported import source");
        JsonNode snapshot = imported ? local.path("snapshot") : body.path("snapshot");
        JsonNode provenance = body.path("provenance");
        StudyPlanSnapshotValidator.ValidationResult validated;
        if("ready-made-template".equals(provenance.path("origin").asText())){
            require(readyMade!=null,"Ready-made persistence is unavailable");
            var mapped=readyMade.adopt(account,imported?raw.path("localSnapshot").path("snapshot"):raw.path("snapshot"),raw.path("provenance"));
            snapshot=mapped.snapshot();provenance=mapped.provenance();
            validated=new StudyPlanSnapshotValidator.ValidationResult(mapped.assignmentContentIds(),Set.copyOf(mapped.assignmentContentIds().values()),catalog.catalogVersion(),provenance);
        }else validated = validator.validate(snapshot, provenance, imported, grants(account));
        String goal = text(imported ? local : body, "goal", 160);
        JsonNode progress = imported ? imports.mapImportedProgress(local, validated) : emptyProgress();
        UUID id = UUID.randomUUID(), versionId = UUID.randomUUID();
        ObjectNode recovery = imported ? (ObjectNode) imports.validateImportedRecovery(local, grants(account)) : mapper.createObjectNode();
        if (!imported) {
            recovery.put("strategy", "none"); recovery.put("elapsedDays", 0);
            recovery.put("deadlineDays", snapshot.path("config").path("days").asInt());
            recovery.set("deferredContentIds", mapper.createArrayNode());
        }
        boolean freeCreation = freeAllowance != null && freeAllowance.beforeCreate(account, validated.canonicalContentIds());
        long planNumber = plans.allocatePlanNumber(account);
        JsonNode namingSource = imported ? local : body;
        String name = namingSource.has("name") ? planName(namingSource) : defaultPlanName(planNumber, snapshot);
        plans.createPlan(account, id, versionId, goal, progress, name, planNumber);
        // DLV-970: every refusal that can be decided before the version is written (the lifecycle reservation, 409
        // PLAN_SUBJECT_CONFLICT and its kin) comes first: in dynamodb mode insertVersion publishes a version above the
        // inline budget as a document before the owner transaction, and a refusal after it would orphan that document.
        // The lifecycle row is written after createPlan, as it always was (the retired PostgreSQL schema required that order).
        if(lifecycle!=null)lifecycle.create(account,id,snapshot,body.path("startDate"),imported);
        insertVersion(account, id, versionId, null, snapshot, validated.provenance(), recovery, imported ? "import" : "create", mapper.valueToTree(validated.assignmentContentIds()));
        if(freeAllowance!=null)freeAllowance.afterCreate(account,id,freeCreation);
        if (imported) event(account, id, versionId, 1, "legacyImport", progress);
        return receipt(account, key, operation, raw, id, Outcome.CREATED, view(account, plan(account, id, false), version(account, id, versionId)));
    }

    static String defaultPlanName(long number, JsonNode snapshot) {
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        String hours = new BigDecimal(snapshot.path("config").path("dailyHours").asText()).stripTrailingZeros().toPlainString();
        return "Study plan #" + number + "_" + date + "_" + hours + "X" + snapshot.path("config").path("days").asInt();
    }

    private String planName(JsonNode body) {
        String name = text(body, "name", 160).strip();
        require(name.codePoints().noneMatch(Character::isISOControl), "Invalid name");
        return name;
    }

    @Transactional
    public Mutation rename(UUID account, UUID id, UUID key, JsonNode body) {
        fields(body, "expectedRevision name");
        Mutation repeat = replay(account, key, "rename:" + id, body);
        if (repeat != null) return repeat;
        StudyPlanRecord plan = plan(account, id, true);
        expect(plan, body);
        String name = planName(body);
        plans.rename(account, id, name, plan.revision() + 1);
        event(account, id, plan.version(), plan.revision() + 1, "rename", body);
        return receipt(account, key, "rename:" + id, body, id, Outcome.CHANGED,
                view(account, plan(account, id, false), version(account, id, plan.version())));
    }

    @Transactional
    public Mutation activity(UUID account, UUID id, UUID key, JsonNode raw) {
        fields(raw, "expectedRevision versionId operations studyDay");
        Mutation repeat = replay(account, key, "activity:" + id, raw);
        if (repeat != null) return repeat;
        // An old tab may still send look-ahead session and content IDs; saved plans hold blaze IDs.
        JsonNode body = CatalogIds.normalize(raw);
        StudyPlanRecord plan = plan(account, id, true);
        expect(plan, body);
        require(plan.version().equals(uuid(text(body, "versionId", 100))), "Activity targets an inactive version");
        StudyPlanVersion version = version(account, id, plan.version());
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
                    accessible(contentId, currentGrants);
                    String outcome = text(op, "outcome", 40);
                    require(Set.of("attempted", "needs-review").contains(outcome), "Invalid attempt outcome");
                    setMember(progress, "attemptedContentIds", contentId, true);
                    if (outcome.equals("needs-review")) setMember(progress, "needsReviewContentIds", contentId, true);
                    object(progress, "sessionOutcomes").put(assignment, outcome);
                    StudyPlanActivity.record(progress,version.snapshot(),assignment,studyDay,mapper);
                }
                case "setSessionCompletion" -> {
                    fields(op, "type assignmentId completed");
                    String assignment = text(op, "assignmentId", 300);
                    contentId = membership.get(assignment);
                    if (contentId == null && studyDay > 0) {
                        JsonNode derived = StudyPlanActivity.resolve(version.snapshot(),assignment,studyDay);
                        require(derived != null,"Unknown session");
                        contentId = StudyPlanActivity.source(derived);
                        if (!canonicalIds.contains(contentId)) contentId = membership.get(contentId);
                        require(contentId != null && canonicalIds.contains(contentId),"Recall outside plan membership");
                        if (StudyPlanActivity.dynamicDay(assignment) > 0)
                            require(StudyPlanActivity.dailyEligible(version.snapshot(),progress,derived,contentId,studyDay),"Complete the original on an earlier study day before recalling it");
                    }
                    require(contentId != null, "Unknown session");
                    boolean complete = bool(op, "completed");
                    if (complete) accessible(contentId, currentGrants);
                    setMember(progress, "completedSessionIds", assignment, complete);
                    if (complete) {
                        object(progress, "sessionOutcomes").put(assignment, "completed");
                        StudyPlanActivity.record(progress,version.snapshot(),assignment,studyDay,mapper);
                    }
                    else object(progress, "sessionOutcomes").remove(assignment);
                }
                case "setContentCompletion" -> {
                    fields(op, "type canonicalContentId completed");
                    contentId = text(op, "canonicalContentId", 256);
                    require(canonicalIds.contains(contentId), "Content outside active plan");
                    boolean complete = bool(op, "completed");
                    if (complete) accessible(contentId, currentGrants);
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
        plans.updateProgress(account, id, progress, revision);
        return receipt(account, key, "activity:" + id, raw, id, Outcome.CHANGED, view(account, plan(account, id, false), version));
    }

    @Transactional
    public Mutation saveVersion(UUID account, UUID id, UUID key, JsonNode raw) {
        fields(raw, "expectedRevision goal snapshot provenance reason recovery");
        Mutation repeat = replay(account, key, "version:" + id, raw);
        if (repeat != null) return repeat;
        JsonNode body = CatalogIds.normalize(raw);
        StudyPlanRecord plan = plan(account, id, true); expect(plan, body);
        StudyPlanVersion prior = version(account, id, plan.version());
        JsonNode snapshot = body.path("snapshot"), recovery = body.path("recovery");
        String reason = text(body, "reason", 160);
        require(Set.of("recovery", "update-plan", "extend-deadline").contains(reason), "Invalid version reason");
        StudyPlanSnapshotValidator.ValidationResult validated;
        if("ready-made-template".equals(prior.provenance().path("origin").asText())&&!reason.equals("update-plan")){
            require(raw.path("snapshot").path("template").equals(prior.snapshot().path("template")),"Recovery must preserve the original template");
            require(raw.path("provenance").equals(prior.provenance()),"Recovery must preserve all template pins");
            snapshot=raw.path("snapshot");
            validated=validator.validateOwnedTemplateVersion(snapshot,prior.provenance(),grants(account));
        }else if("ready-made-template".equals(body.path("provenance").path("origin").asText())){
            require(readyMade!=null,"Ready-made persistence is unavailable");
            var mapped=readyMade.adopt(account,raw.path("snapshot"),raw.path("provenance"));snapshot=mapped.snapshot();
            validated=new StudyPlanSnapshotValidator.ValidationResult(mapped.assignmentContentIds(),Set.copyOf(mapped.assignmentContentIds().values()),catalog.catalogVersion(),mapped.provenance());
        }else validated = reason.equals("update-plan") ? validator.validate(snapshot,body.path("provenance"),false,grants(account))
                : validator.validateExistingVersion(snapshot,body.path("provenance"),grants(account));
        recoveryValidator.validate(prior, snapshot, validated.provenance(), recovery, reason);
        boolean freeScopeChange=false;
        if(freeAllowance!=null){
            Set<String> priorContent=new HashSet<>();
            prior.membership().properties().forEach(entry->priorContent.add(entry.getValue().asText()));
            boolean active=lifecycle!=null && lifecycle.view(account,id).path("state").asText().equals("active");
            freeScopeChange=freeAllowance.beforeFreeScopeChange(account,id,priorContent,validated.canonicalContentIds(),active);
            if(active)freeAllowance.beforeActivation(account,id,validated.canonicalContentIds());
        }
        if(lifecycle!=null)lifecycle.replace(account,id,snapshot);
        String goal = text(body, "goal", 160);
        UUID next = UUID.randomUUID(); long revision = plan.revision() + 1;
        insertVersion(account, id, next, plan.version(), snapshot, validated.provenance(), recovery, reason, mapper.valueToTree(validated.assignmentContentIds()));
        planVersions.activateVersion(account, id, next, revision, goal);
        if(freeAllowance!=null)freeAllowance.afterCreate(account,id,freeScopeChange);
        event(account, id, next, revision, reason, recovery);
        return receipt(account, key, "version:" + id, raw, id, Outcome.CREATED, view(account, plan(account, id, false), version(account, id, next)));
    }

    @Transactional
    public Mutation lifecycle(UUID account,UUID id,UUID key,JsonNode body){
        fields(body,"expectedRevision state startDate");
        Mutation repeat=replay(account,key,"lifecycle:"+id,body);if(repeat!=null)return repeat;
        require(lifecycle!=null,"Plan lifecycle is unavailable");
        StudyPlanRecord plan=plan(account,id,true);expect(plan,body);
        StudyPlanVersion current=version(account,id,plan.version());String state=text(body,"state",20);
        if(state.equals("active")){
            var grants=grants(account);
            for(var assignment:StudyPlanJson.assignments(current.snapshot()).values())accessible(catalog.canonicalContentId(StudyPlanJson.sourceId(assignment)),grants);
            Set<String> content=new HashSet<>();
            current.membership().properties().forEach(entry->content.add(entry.getValue().asText()));
            boolean freeContentPlan=freeAllowance!=null && freeAllowance.freeAccount(account)
                    && freeAllowance.freeContent(content);
            if(!freeContentPlan)for(var topic:current.snapshot().path("config").path("topicIds"))
                require(grants.contains(topic.asText()),"Current access must cover every selected course before activation");
            if(freeAllowance!=null)freeAllowance.beforeActivation(account,id,content);
        }
        lifecycle.transition(account,id,current.snapshot(),state,body.path("startDate"));
        plans.updateProgress(account,id,plan.progress(),plan.revision()+1);
        event(account,id,plan.version(),plan.revision()+1,"lifecycle",body);
        return receipt(account,key,"lifecycle:"+id,body,id,Outcome.CHANGED,view(account,plan(account,id,false),current));
    }


    @Transactional
    public Mutation delete(UUID account, UUID id, UUID key, long revision) {
        ObjectNode body = mapper.createObjectNode().put("expectedRevision", revision);
        Mutation repeat = replay(account, key, "delete:" + id, body);
        if (repeat != null) return repeat;
        StudyPlanRecord plan = plan(account, id, true); expect(plan, body);
        boolean freeAccountAtDelete=freeAllowance!=null && freeAllowance.freeAccount(account);
        if(freeAllowance!=null)freeAllowance.beforeDelete(account);
        plans.deletePlan(account, id);
        if(freeAllowance!=null)freeAllowance.afterDelete(account,id,freeAccountAtDelete);
        receipt(account, key, "delete:" + id, body, id, Outcome.DELETED, mapper.nullNode());
        return new Mutation(Outcome.DELETED, mapper.nullNode());
    }

    private Mutation replay(UUID account, UUID key, String operation, JsonNode body) {
        // Serialize the key, including concurrent creates, without any out-of-transaction success cache.
        planReceipts.lockMutation(account, key);
        Optional<StudyPlanMutationReceipt> stored = planReceipts.findReceipt(account, key);
        if (stored.isEmpty()) return null;
        StudyPlanMutationReceipt row = stored.get();
        if (!row.requestHash().equals(planJson.digest(operation + ":" + planJson.canonical(body))))
            throw new AccountFailure(409, "IDEMPOTENCY_KEY_REUSED", "This mutation key already belongs to a different request");
        if (row.deleted()) throw new AccountFailure(410, "PLAN_DELETED", "This plan was deleted");
        Outcome outcome = Outcome.ofReceiptCode(row.status());
        if (outcome == Outcome.DELETED) return new Mutation(outcome, mapper.nullNode());
        // Recheck ownership and current access; an old receipt is never a new grant.
        plan(account, row.planId(), false);
        ObjectNode result = (ObjectNode) row.response().deepCopy();
        applyAccess(result, grants(account));
        return new Mutation(outcome, result);
    }

    private Mutation receipt(UUID account, UUID key, String operation, JsonNode request, UUID id, Outcome outcome, JsonNode result) {
        planReceipts.insertReceipt(account, key, planJson.digest(operation + ":" + planJson.canonical(request)), id, result, outcome.receiptCode());
        return new Mutation(outcome, result);
    }
    private void expect(StudyPlanRecord plan, JsonNode request) {
        if (!request.has("expectedRevision")) throw new AccountFailure(428, "REVISION_REQUIRED", "An expected revision is required");
        JsonNode revision = request.path("expectedRevision");
        require(revision.isIntegralNumber() && revision.asLong() > 0, "Invalid revision");
        if (revision.asLong() != plan.revision()) throw new AccountFailure(409, "REVISION_CONFLICT", "The plan changed; reload before resolving your draft", plan.revision());
    }
    private StudyPlanRecord plan(UUID owner, UUID id, boolean lock) {
        return plans.findPlan(owner, id, lock).orElseThrow(() ->
                new AccountFailure(404, "PLAN_NOT_FOUND", "Plan not found"));
    }

    private StudyPlanVersion version(UUID owner, UUID id, UUID version) {
        return planVersions.findVersion(owner, id, version).orElseThrow(() ->
                new AccountFailure(404, "VERSION_NOT_FOUND", "Version not found"));
    }
    private void insertVersion(UUID owner, UUID id, UUID version, UUID parent, JsonNode snapshot, JsonNode pins, JsonNode recovery, String reason, JsonNode membership) {
        planVersions.insertVersion(owner, id, version, parent, snapshot, planJson.digest(planJson.canonical(snapshot)), pins, recovery, reason, membership);
    }
    private void event(UUID owner, UUID id, UUID version, long revision, String kind, JsonNode payload) {
        planVersions.insertActivity(owner, id, version, revision, kind, payload);
    }
    private ObjectNode view(UUID account, StudyPlanRecord plan, StudyPlanVersion version) {
        ObjectNode result = mapper.createObjectNode();
        result.put("planId", plan.id().toString()); result.put("versionId", version.id().toString()); result.put("revision", plan.revision());
        result.put("name", plan.name()); result.put("planNumber", plan.planNumber());
        result.put("goal", plan.goal()); result.set("snapshot", version.snapshot()); result.set("provenance", version.provenance());
        result.set("progress", plan.progress()); result.set("recovery", version.recovery());
        if(lifecycle!=null)result.set("lifecycle",lifecycle.view(account,plan.id()));
        result.put("createdAt", plan.createdAt()); result.put("updatedAt", plan.updatedAt());
        applyAccess(result, grants(account)); return result;
    }
    private void applyAccess(ObjectNode view, Set<String> grants) {
        Set<String> restricted = new TreeSet<>();
        for (JsonNode a : StudyPlanJson.assignments(view.path("snapshot")).values())
            if (!catalog.isAccessible(catalog.canonicalContentId(StudyPlanJson.sourceId(a)), grants)) restricted.add(catalog.canonicalContentId(StudyPlanJson.sourceId(a)));
        view.set("restrictedContentIds", mapper.valueToTree(restricted));
    }
    private void accessible(String content, Set<String> grants) {
        boolean allowed = catalog.isAccessible(content, grants);
        if (!allowed) throw new AccountFailure(403, "CONTENT_ACCESS_DENIED", "Current account access does not permit this activity");
    }
    private boolean knownInPlan(UUID account, UUID id, String content) {
        return planVersions.containsHistoricalContent(account, id, content);
    }

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
