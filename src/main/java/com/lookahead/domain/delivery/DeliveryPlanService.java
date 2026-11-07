package com.lookahead.domain.delivery;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.StaffPermissions;
import com.lookahead.domain.review.platform.ReviewStatusHistory;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-971 (owner, 2026-11-06): the delivery plan the Domain serves and edits. Reads need delivery read (owner,
 * co-author, admin, reviewer); a new ticket needs delivery create; a status change follows
 * {@link StaffPermissions#permitsDeliveryWrite}; a conflict is resolved by the owner or an admin. Every write checks
 * the caller's {@code expectedVersion} against the plan (409 VERSION_CONFLICT when stale), raises the version, archives
 * the previous file and uses the store's conditional put. Tickets carry {@code createdIn} / {@code updatedIn}
 * ({@code dev} when the cloud policy is on, else {@code local}) and {@code updatedAt}; a missing stamp reads as
 * {@code local}. {@code origin {id, kind}} names the Team Reviews record a ticket was promoted from;
 * {@code pendingLocal} holds a local edit the inbox sync could not merge. Every status change is a history row.
 */
public class DeliveryPlanService {
    static final int WRITE_ATTEMPTS = 8;
    public static final Set<String> SERVER_FIELDS = Set.of("createdIn", "updatedIn", "updatedAt", "origin", "pendingLocal");

    public record Created(long version, JsonNode item) {}
    /** An inbox sync: merged into {@code version} with {@code counts}, or left alone for {@code reason} (plain words; {@code null} when synced). */
    public record Sync(boolean synced, DeliveryPlanMerge.Counts counts, long version, String reason) {}

    private final DeliveryPlanStore store;
    private final StaffAccessService access;
    private final ReviewStatusHistory history;
    private final String environment;
    private final Clock clock;
    private final java.util.concurrent.locks.ReentrantLock writes = new java.util.concurrent.locks.ReentrantLock();

    public DeliveryPlanService(DeliveryPlanStore store, StaffAccessService access, ReviewStatusHistory history, String environment, Clock clock) {
        this.store = store; this.access = access; this.history = history; this.environment = environment; this.clock = clock;
    }

    public String environment() { return environment; }

    // ---- Reads

    /** The plan with every ticket's stamps filled in for the reader; 404 DELIVERY_PLAN_MISSING until the owner uploads it. */
    public JsonNode plan(AccountPrincipal actor) {
        if (!permissions(actor).deliveryRead()) throw denied();
        ObjectNode plan = current().plan().deepCopy();
        for (JsonNode item : plan.path("workItems")) if (item instanceof ObjectNode object) stampsRead(object);
        return plan;
    }

    /** Ticket id to its statusId; empty when the plan is not there (never an error for a reader of records). */
    public Map<String, String> ticketStatuses() {
        var statuses = new HashMap<String, String>();
        try {
            store.current().ifPresent(loaded -> DeliveryPlanMerge.items(loaded.plan()).forEach((id, item) -> statuses.put(id, item.path("statusId").asString())));
        } catch (AccountFailure unavailable) {
            Events.failed(Events.EVENT, "delivery.read", unavailable.code()).log("Ticket statuses are not shown on Team Reviews: {}", unavailable.getMessage());
        }
        return statuses;
    }

    // ---- Writes

    public Created create(AccountPrincipal actor, Long expectedVersion, JsonNode body) {
        var permissions = permissions(actor);
        ObjectNode item = item(body);
        if (!permissions.deliveryCreate() || !permissions.permitsDeliveryWrite(true, null, item.path("statusId").asString())) throw denied();
        String now = clock.instant().toString();
        ObjectNode[] saved = {null};
        long version = write(expectedVersion, plan -> {
            validate(item, plan);
            String id = item.path("id").isString() ? item.path("id").asString() : nextTicketId(plan);
            if (DeliveryPlanMerge.items(plan).containsKey(id)) throw new AccountFailure(409, "DELIVERY_ITEM_EXISTS", "A ticket with this id is already on the plan.");
            ObjectNode created = item.deepCopy();
            created.put("id", id);
            created.put("createdIn", environment); created.put("updatedIn", environment); created.put("updatedAt", now);
            created.putNull("origin"); created.putNull("pendingLocal");
            ((ArrayNode) plan.path("workItems")).add(created);
            saved[0] = created;
            return plan;
        });
        history.record(saved[0].path("id").asString(), "ticket", actor.accountId(), null, saved[0].path("statusId").asString(), false);
        Events.success(Events.EVENT, "delivery.create").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", saved[0].path("id").asString())
                .addKeyValue("plan.version", version).log("Delivery ticket {} added by account {}: plan version {}", saved[0].path("id").asString(), actor.accountId(), version);
        return new Created(version, saved[0]);
    }

    public Created update(AccountPrincipal actor, String id, Long expectedVersion, JsonNode body) {
        var permissions = permissions(actor);
        ObjectNode item = item(body);
        if (item.has("id") && !id.equals(item.path("id").asString())) throw invalid("The ticket id in the body must match the address.");
        String now = clock.instant().toString();
        String[] statuses = {null, null};
        ObjectNode[] saved = {null};
        long version = write(expectedVersion, plan -> {
            ObjectNode previous = DeliveryPlanMerge.items(plan).get(id);
            if (previous == null) throw notFound();
            validate(item, plan);
            String before = previous.path("statusId").asString(), after = item.path("statusId").asString();
            if (!permissions.permitsDeliveryWrite(false, before, after) && !(before.equals(after) && (permissions.deliveryCreate() || permissions.deliveryReview())))
                throw denied();
            ObjectNode next = item.deepCopy();
            next.put("id", id);
            next.put("createdIn", previous.path("createdIn").isString() ? previous.path("createdIn").asString() : "local");
            next.put("updatedIn", environment); next.put("updatedAt", now);
            next.set("origin", previous.hasNonNull("origin") ? previous.get("origin").deepCopy() : previous.nullNode());
            next.set("pendingLocal", previous.hasNonNull("pendingLocal") ? previous.get("pendingLocal").deepCopy() : previous.nullNode());
            replace(plan, id, next);
            statuses[0] = before; statuses[1] = after; saved[0] = next;
            return plan;
        });
        if (!statuses[0].equals(statuses[1])) history.record(id, "ticket", actor.accountId(), statuses[0], statuses[1], false);
        Events.success(Events.EVENT, "delivery.update").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("plan.version", version).addKeyValue("ticket.status.before", statuses[0]).addKeyValue("ticket.status", statuses[1])
                .log("Delivery ticket {} updated by account {}: {} -> {}, plan version {}", id, actor.accountId(), statuses[0], statuses[1], version);
        return new Created(version, saved[0]);
    }

    /** {@code keep} is "dev" (drop the local version) or "local" (the local version replaces the ticket's fields). */
    public Created resolveConflict(AccountPrincipal actor, String id, Long expectedVersion, String keep) {
        var permissions = permissions(actor);
        if (!permissions.manageAccess() && !"admin".equals(permissions.feedbackRole())) throw denied();
        if (!"dev".equals(keep) && !"local".equals(keep)) throw invalid("Choose dev or local.");
        String now = clock.instant().toString();
        String[] statuses = {null, null};
        ObjectNode[] saved = {null};
        long version = write(expectedVersion, plan -> {
            ObjectNode previous = DeliveryPlanMerge.items(plan).get(id);
            if (previous == null) throw notFound();
            if (!(previous.get("pendingLocal") instanceof ObjectNode pending)) throw new AccountFailure(409, "DELIVERY_NO_CONFLICT", "This ticket has no pending local version.");
            ObjectNode next;
            if ("dev".equals(keep)) { next = previous.deepCopy(); next.putNull("pendingLocal"); }
            else {
                next = pending.deepCopy();
                next.put("id", id);
                next.put("createdIn", previous.path("createdIn").isString() ? previous.path("createdIn").asString() : "local");
                next.put("updatedIn", environment); next.put("updatedAt", now);
                next.set("origin", previous.hasNonNull("origin") ? previous.get("origin").deepCopy() : previous.nullNode());
                next.putNull("pendingLocal");
                validate(next, plan);
            }
            replace(plan, id, next);
            statuses[0] = previous.path("statusId").asString(); statuses[1] = next.path("statusId").asString(); saved[0] = next;
            return plan;
        });
        if (!statuses[0].equals(statuses[1])) history.record(id, "ticket", actor.accountId(), statuses[0], statuses[1], false);
        Events.success(Events.EVENT, "delivery.resolve").addKeyValue("account.id", actor.accountId()).addKeyValue("target.id", id)
                .addKeyValue("plan.version", version).addKeyValue("keep", keep)
                .log("Delivery ticket {} conflict resolved by account {}: kept {}, plan version {}", id, actor.accountId(), keep, version);
        return new Created(version, saved[0]);
    }

    /**
     * Promote from Team Reviews: a ticket in the workflow's first status, in the current stage, with {@code origin}.
     * The caller has checked the record's rights. Answers the ticket as added and the new plan version.
     */
    public Created promote(UUID actorId, String title, String summary, String type, String priority, String originId, String originKind) {
        String now = clock.instant().toString();
        ObjectNode[] saved = {null};
        long version = write(null, plan -> {
            ObjectNode ticket = plan.objectNode();
            ticket.put("id", nextTicketId(plan));
            ticket.put("title", title); ticket.put("summary", summary); ticket.put("type", type);
            ticket.put("stageId", plan.path("currentStageId").asString());
            ticket.put("statusId", firstStatus(plan));
            ticket.put("priorityId", priorityId(plan, priority));
            ticket.putArray("labels").add("team-reviews").add(originKind);
            ticket.putArray("acceptanceCriteria"); ticket.putArray("dependencies"); ticket.putArray("blockedBy");
            ticket.put("notes", "Promoted from Team Reviews record " + originId + ".");
            ticket.putArray("evidence");
            ticket.put("createdIn", environment); ticket.put("updatedIn", environment); ticket.put("updatedAt", now);
            ObjectNode origin = ticket.putObject("origin"); origin.put("id", originId); origin.put("kind", originKind);
            ticket.putNull("pendingLocal");
            ((ArrayNode) plan.path("workItems")).add(ticket);
            saved[0] = ticket;
            return plan;
        });
        history.record(saved[0].path("id").asString(), "ticket", actorId, null, saved[0].path("statusId").asString(), false);
        return new Created(version, saved[0]);
    }

    /** The inbox merged into the plan (see {@link DeliveryPlanMerge}); the inbox file is deleted after; one line with the counts. */
    public Sync syncInbox() {
        var inbox = store.inbox();
        if (inbox.isEmpty()) return new Sync(false, null, store.current().map(DeliveryPlanStore.Loaded::version).orElse(0L), "no inbox file");
        var current = store.current();
        if (current.isEmpty()) {
            Events.failed(Events.EVENT, "delivery.inbox-sync", "DELIVERY_PLAN_MISSING")
                    .log("The delivery inbox is waiting but delivery/plan.json is not there yet; the inbox is kept until the owner uploads the plan");
            return new Sync(false, null, 0, "delivery/plan.json missing");
        }
        long baseVersion = inbox.get().plan().path("baseVersion").isIntegralNumber() ? inbox.get().plan().path("baseVersion").asLong() : inbox.get().version();
        var counts = new DeliveryPlanMerge.Counts[1];
        long version;
        writes.lock();
        try {
            for (int attempt = 0; ; attempt++) {
                var loaded = store.current().orElseThrow(DeliveryPlanStore::unavailable);
                ObjectNode base = baseVersion == loaded.version() ? loaded.plan() : store.archived(baseVersion).map(DeliveryPlanStore.Loaded::plan).orElse(null);
                if (base == null) {
                    Events.failed(Events.EVENT, "delivery.inbox-sync", "BASE_VERSION_MISSING").addKeyValue("plan.version", loaded.version()).addKeyValue("plan.base", baseVersion)
                            .log("The delivery inbox names base version {} but the archive has no such plan (current {}); the inbox is kept for the owner to fix", baseVersion, loaded.version());
                    return new Sync(false, null, loaded.version(), "base version " + baseVersion + " not in the archive");
                }
                var merged = DeliveryPlanMerge.merge(loaded.plan(), base, inbox.get().plan(), clock.instant().toString());
                counts[0] = merged.counts();
                if (store.save(loaded, merged.plan())) { version = loaded.version() + 1; break; }
                if (attempt >= WRITE_ATTEMPTS - 1) throw DeliveryPlanStore.unavailable();
            }
        } finally { writes.unlock(); }
        store.deleteInbox();
        var c = counts[0];
        Events.success(Events.EVENT, "delivery.inbox-sync").addKeyValue("plan.version", version).addKeyValue("plan.base", baseVersion)
                .addKeyValue("count", c.added() + c.takenLocal() + c.conflicts()).addKeyValue("added", c.added()).addKeyValue("taken", c.takenLocal())
                .addKeyValue("kept", c.keptDev()).addKeyValue("conflicts", c.conflicts()).addKeyValue("unchanged", c.unchanged()).addKeyValue("deleted", c.deletedOnDev())
                .log("Delivery inbox merged into plan version {} (base {}): {}", version, baseVersion, c.words());
        return new Sync(true, c, version, null);
    }

    // ---- Plan edits

    /** Reads, checks the expected version, edits and saves; repeats the edit when another writer came first. */
    private long write(Long expectedVersion, Function<ObjectNode, ObjectNode> edit) {
        writes.lock();
        try {
            for (int attempt = 0; ; attempt++) {
                var loaded = current();
                if (expectedVersion != null && expectedVersion != loaded.version())
                    throw new AccountFailure(409, "VERSION_CONFLICT", "The plan changed since you loaded it (now version " + loaded.version() + "). Reload and try again.", loaded.version());
                ObjectNode next = edit.apply(loaded.plan().deepCopy());
                if (store.save(loaded, next)) return loaded.version() + 1;
                if (attempt >= WRITE_ATTEMPTS - 1) throw DeliveryPlanStore.unavailable();
                Events.retry(Events.EVENT, "delivery.write", "WRITE_CONFLICT", attempt + 1, WRITE_ATTEMPTS)
                        .log("The delivery plan was changed by another writer at the same moment: attempt {} of {}; reading it again", attempt + 1, WRITE_ATTEMPTS);
            }
        } finally { writes.unlock(); }
    }

    private DeliveryPlanStore.Loaded current() {
        return store.current().orElseThrow(() -> new AccountFailure(404, "DELIVERY_PLAN_MISSING",
                "The delivery plan has not been uploaded yet. The owner uploads delivery/plan.json to the assets bucket by hand; nothing is seeded from the publication."));
    }

    private static void replace(ObjectNode plan, String id, ObjectNode next) {
        ArrayNode items = (ArrayNode) plan.path("workItems");
        for (int i = 0; i < items.size(); i++)
            if (id.equals(items.get(i).path("id").asString())) { items.set(i, next); return; }
        throw notFound();
    }

    static String firstStatus(JsonNode plan) {
        JsonNode first = plan.path("workflow").path(0).path("id");
        return first.isString() ? first.asString() : "backlog";
    }

    static String priorityId(JsonNode plan, String priority) {
        String wanted = switch (priority == null ? "" : priority) { case "high" -> "P1"; case "low" -> "P3"; default -> "P2"; };
        for (JsonNode entry : plan.path("priorities")) if (wanted.equals(entry.path("id").asString())) return wanted;
        JsonNode first = plan.path("priorities").path(0).path("id");
        return first.isString() ? first.asString() : wanted;
    }

    /** The next DLV-N after the highest numbered ticket on the plan. */
    static String nextTicketId(JsonNode plan) {
        long highest = 0;
        for (String id : DeliveryPlanMerge.items(plan).keySet())
            if (id.matches("DLV-\\d{1,9}")) highest = Math.max(highest, Long.parseLong(id.substring(4)));
        return "DLV-" + (highest + 1);
    }

    private static Set<String> ids(JsonNode plan, String field) {
        var ids = new HashSet<String>();
        for (JsonNode entry : plan.path(field)) if (entry.path("id").isString()) ids.add(entry.path("id").asString());
        return ids;
    }

    static void validate(ObjectNode item, JsonNode plan) {
        if (item.has("id") && !(item.path("id").isString() && item.path("id").asString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) throw invalid("The ticket id is not valid.");
        if (!item.path("title").isString() || item.path("title").asString().isBlank() || item.path("title").asString().length() > 200) throw invalid("Give the ticket a title of at most 200 characters.");
        if (item.has("summary") && !item.path("summary").isString() || item.path("summary").asString().length() > 5000) throw invalid("Keep the summary within 5000 characters.");
        if (!item.path("type").isString() || !item.path("type").asString().matches("[a-z][a-z-]{0,39}")) throw invalid("Choose a ticket type.");
        if (!item.path("statusId").isString() || !ids(plan, "workflow").contains(item.path("statusId").asString())) throw invalid("Choose a status from the workflow.");
        if (!plan.path("priorities").isEmpty() && !ids(plan, "priorities").contains(item.path("priorityId").asString())) throw invalid("Choose a priority from the plan.");
        if (item.has("stageId") && !item.path("stageId").isNull() && !ids(plan, "stages").contains(item.path("stageId").asString())) throw invalid("Choose a stage from the plan.");
    }

    private static ObjectNode item(JsonNode body) {
        if (!(body instanceof ObjectNode item) || item.isEmpty()) throw invalid("The ticket is incomplete.");
        ObjectNode clean = item.deepCopy();
        SERVER_FIELDS.forEach(clean::remove);
        return clean;
    }

    private static void stampsRead(ObjectNode item) {
        if (!item.path("createdIn").isString()) item.put("createdIn", "local");
        if (!item.path("updatedIn").isString()) item.put("updatedIn", "local");
        if (!item.has("origin")) item.putNull("origin");
        if (!item.has("pendingLocal")) item.putNull("pendingLocal");
    }

    private StaffPermissions permissions(AccountPrincipal actor) {
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
        return access.permissions(actor);
    }

    static AccountFailure invalid(String message) { return new AccountFailure(422, "DELIVERY_ITEM_INVALID", message); }
    static AccountFailure notFound() { return new AccountFailure(404, "DELIVERY_ITEM_NOT_FOUND", "This ticket is not on the plan."); }
    static AccountFailure denied() { return new AccountFailure(403, "DELIVERY_ACCESS_DENIED", "Delivery plan access is unavailable for this account."); }
}
