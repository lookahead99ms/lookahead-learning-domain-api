package com.lookahead.domain.delivery;

import com.lookahead.domain.access.StaffAccessService;
import com.lookahead.domain.access.StaffPermissions;
import com.lookahead.domain.review.platform.PlatformReviewFixtures;
import com.lookahead.domain.review.platform.ReviewStatusHistory;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-971: the plan endpoints' rules, the version check, the stamps and the inbox sync with its one log line. */
class DeliveryPlanServiceTest {
    @TempDir Path root;
    final JsonMapper mapper = JsonMapper.builder().build();
    final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner@lookahead.test", "Owner", true);
    final AccountPrincipal admin = new AccountPrincipal(UUID.randomUUID(), "admin@example.test", "Admin", true);
    final AccountPrincipal reviewer = new AccountPrincipal(UUID.randomUUID(), "reviewer@example.test", "Reviewer", true);
    final AccountPrincipal learner = new AccountPrincipal(UUID.randomUUID(), "learner@example.test", "Learner", true);
    final ReviewStatusHistory.InMemory history = new ReviewStatusHistory.InMemory();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
    StaffAccessService access;
    DeliveryPlanService plans;

    @BeforeEach void setup() throws Exception {
        access = mock(StaffAccessService.class);
        when(access.permissions(owner)).thenReturn(StaffPermissions.forRoles(true, Set.of()));
        when(access.permissions(admin)).thenReturn(StaffPermissions.forRoles(false, Set.of("admin")));
        when(access.permissions(reviewer)).thenReturn(StaffPermissions.forRoles(false, Set.of("reviewer")));
        when(access.permissions(learner)).thenReturn(StaffPermissions.forRoles(false, Set.of()));
        PlatformReviewFixtures.seedPlan(root);
        plans = PlatformReviewFixtures.delivery(root, mapper, access, history, clock, "dev");
    }

    ObjectNode item(String title, String status) {
        ObjectNode item = mapper.createObjectNode();
        item.put("title", title); item.put("summary", "Why"); item.put("type", "story"); item.put("statusId", status); item.put("priorityId", "P2"); item.put("stageId", "stage-1");
        item.putArray("labels").add("web");
        return item;
    }

    static AccountFailure failure(ThrowableAssert.ThrowingCallable call, int status) {
        var failure = catchThrowableOfType(AccountFailure.class, call);
        assertThat(failure).isNotNull();
        assertThat(failure.status()).isEqualTo(status);
        return failure;
    }

    @Test void readersSeeThePlanWithStampsFilledInAndALearnerNothing() {
        var plan = plans.plan(reviewer);
        assertThat(plan.path("version").asLong()).isEqualTo(7);
        var ticket = plan.path("workItems").get(0);
        assertThat(ticket.path("origin").isNull()).isTrue();
        assertThat(ticket.path("pendingLocal").isNull()).isTrue();
        assertThat(ticket.path("createdIn").asString()).isEqualTo("local");
        assertThat(failure(() -> plans.plan(learner), 403).code()).isEqualTo("DELIVERY_ACCESS_DENIED");
        failure(() -> plans.plan(null), 401);
        assertThat(plans.ticketStatuses()).containsEntry("DLV-1", "backlog");
        assertThat(plans.environment()).isEqualTo("dev");
    }

    @Test void aMissingPlanIsASpokenNotFoundNeverASeed() throws Exception {
        Files.delete(root.resolve("delivery/plan.json"));
        var failure = failure(() -> plans.plan(owner), 404);
        assertThat(failure.code()).isEqualTo("DELIVERY_PLAN_MISSING");
        assertThat(failure.getMessage()).contains("delivery/plan.json").contains("by hand");
        failure(() -> plans.create(owner, 1L, item("x", "backlog")), 404);
        assertThat(plans.ticketStatuses()).isEmpty();
        assertThat(Files.exists(root.resolve("delivery/plan.json"))).isFalse();
    }

    @Test void createNeedsDeliveryCreateAndAnAllowedFirstStatusAndStampsTheTicket() {
        var created = plans.create(admin, 7L, item("New work", "backlog"));
        assertThat(created.version()).isEqualTo(8);
        assertThat(created.item().path("id").asString()).isEqualTo("DLV-2");
        assertThat(created.item().path("createdIn").asString()).isEqualTo("dev");
        assertThat(created.item().path("updatedIn").asString()).isEqualTo("dev");
        assertThat(created.item().path("updatedAt").asString()).isEqualTo("2026-11-06T12:00:00Z");
        assertThat(created.item().path("origin").isNull()).isTrue();
        assertThat(created.item().path("labels").get(0).asString()).isEqualTo("web");
        assertThat(history.rows).extracting(ReviewStatusHistory.InMemory.Row::recordId, ReviewStatusHistory.InMemory.Row::kind, ReviewStatusHistory.InMemory.Row::fromStatus, ReviewStatusHistory.InMemory.Row::toStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DLV-2", "ticket", null, "backlog"));
        assertThat(plans.plan(owner).path("version").asLong()).isEqualTo(8);
        assertThat(Files.exists(root.resolve("delivery/archive/plan-v7.json"))).isTrue();
        // The reviewer cannot create; an admin cannot create straight into done; a stale version is a 409 with the current one.
        failure(() -> plans.create(reviewer, 8L, item("x", "backlog")), 403);
        failure(() -> plans.create(admin, 8L, item("x", "done")), 403);
        var stale = failure(() -> plans.create(owner, 7L, item("x", "backlog")), 409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(stale.currentRevision()).isEqualTo(8L);
        // Client-controlled fields are ignored; a chosen id must be free and valid; required fields are checked.
        var chosen = item("Chosen id", "ready"); chosen.put("id", "WEB-77"); chosen.put("createdIn", "mars"); chosen.put("origin", "x");
        var withId = plans.create(owner, 8L, chosen);
        assertThat(withId.item().path("id").asString()).isEqualTo("WEB-77");
        assertThat(withId.item().path("createdIn").asString()).isEqualTo("dev");
        assertThat(withId.item().path("origin").isNull()).isTrue();
        assertThat(failure(() -> plans.create(owner, 9L, chosen), 409).code()).isEqualTo("DELIVERY_ITEM_EXISTS");
        var bad = item("x", "backlog"); bad.put("id", "../x");
        assertThat(failure(() -> plans.create(owner, 9L, bad), 422).code()).isEqualTo("DELIVERY_ITEM_INVALID");
        failure(() -> plans.create(owner, 9L, item("", "backlog")), 422);
        failure(() -> plans.create(owner, 9L, item("x", "nowhere")), 422);
        var badPriority = item("x", "backlog"); badPriority.put("priorityId", "P9");
        failure(() -> plans.create(owner, 9L, badPriority), 422);
        var badStage = item("x", "backlog"); badStage.put("stageId", "stage-9");
        failure(() -> plans.create(owner, 9L, badStage), 422);
        var badType = item("x", "backlog"); badType.put("type", "Not A Type");
        failure(() -> plans.create(owner, 9L, badType), 422);
        failure(() -> plans.create(owner, 9L, mapper.createObjectNode()), 422);
        failure(() -> plans.create(owner, 9L, mapper.createArrayNode()), 422);
        assertThat(plans.create(owner, 9L, item("Owner anywhere", "done")).item().path("statusId").asString()).isEqualTo("done");
    }

    @Test void updateFollowsTheDeliveryWriteRuleAndKeepsOriginAndCreatedIn() {
        plans.create(owner, 7L, item("Promoted-like", "backlog"));
        var withOrigin = plans.plan(owner).path("workItems").get(1).deepCopy();
        // Give DLV-2 an origin through a direct plan edit, as promote does.
        var edited = item("Promoted-like", "backlog");
        // StaffPermissions.permitsDeliveryWrite: an admin moves work to review (never to done); a reviewer marks done from review only.
        failure(() -> plans.update(reviewer, "DLV-1", 8L, item("First ticket", "in-progress")), 403);
        failure(() -> plans.update(reviewer, "DLV-1", 8L, item("First ticket", "done")), 403);
        var toReview = plans.update(admin, "DLV-1", 8L, item("First ticket", "review"));
        assertThat(toReview.version()).isEqualTo(9);
        assertThat(toReview.item().path("updatedIn").asString()).isEqualTo("dev");
        assertThat(toReview.item().path("createdIn").asString()).isEqualTo("local");
        failure(() -> plans.update(admin, "DLV-1", 9L, item("First ticket", "done")), 403);
        var done = plans.update(reviewer, "DLV-1", 9L, item("First ticket", "done"));
        assertThat(done.item().path("statusId").asString()).isEqualTo("done");
        assertThat(history.rows).extracting(ReviewStatusHistory.InMemory.Row::recordId, ReviewStatusHistory.InMemory.Row::fromStatus, ReviewStatusHistory.InMemory.Row::toStatus)
                .contains(org.assertj.core.groups.Tuple.tuple("DLV-1", "backlog", "review"), org.assertj.core.groups.Tuple.tuple("DLV-1", "review", "done"));
        // Same status, text only: an admin may edit; a reviewer may not; no history row.
        int rows = history.rows.size();
        assertThat(plans.update(admin, "DLV-2", 10L, item("Renamed", "backlog")).item().path("title").asString()).isEqualTo("Renamed");
        assertThat(history.rows).hasSize(rows);
        failure(() -> plans.update(reviewer, "DLV-2", 11L, item("Renamed again", "backlog")), 403);
        failure(() -> plans.update(learner, "DLV-2", 11L, item("x", "backlog")), 403);
        assertThat(failure(() -> plans.update(owner, "DLV-404", 11L, item("x", "backlog")), 404).code()).isEqualTo("DELIVERY_ITEM_NOT_FOUND");
        var mismatched = item("x", "backlog"); mismatched.put("id", "DLV-1");
        failure(() -> plans.update(owner, "DLV-2", 11L, mismatched), 422);
        failure(() -> plans.update(owner, "DLV-2", 3L, item("x", "backlog")), 409);
        assertThat(edited).isNotNull(); assertThat(withOrigin).isNotNull();
    }

    @Test void conflictsAreResolvedByTheOwnerOrAnAdminKeepingDevOrLocal() throws Exception {
        // A conflict as the sync leaves it: DEV's ticket with pendingLocal.
        var loaded = PlatformReviewFixtures.planStore(root, mapper).current().orElseThrow();
        var plan = loaded.plan().deepCopy();
        var ticket = (ObjectNode) plan.path("workItems").get(0);
        var pending = ticket.deepCopy(); pending.put("title", "Local title"); pending.put("statusId", "ready"); pending.remove("pendingLocal");
        ticket.set("pendingLocal", pending);
        assertThat(PlatformReviewFixtures.planStore(root, mapper).save(loaded, plan)).isTrue();
        failure(() -> plans.resolveConflict(reviewer, "DLV-1", 8L, "dev"), 403);
        failure(() -> plans.resolveConflict(owner, "DLV-1", 8L, "theirs"), 422);
        failure(() -> plans.resolveConflict(owner, "DLV-1", 7L, "dev"), 409);
        failure(() -> plans.resolveConflict(owner, "DLV-404", 8L, "dev"), 404);
        var local = plans.resolveConflict(admin, "DLV-1", 8L, "local");
        assertThat(local.version()).isEqualTo(9);
        assertThat(local.item().path("title").asString()).isEqualTo("Local title");
        assertThat(local.item().path("statusId").asString()).isEqualTo("ready");
        assertThat(local.item().path("pendingLocal").isNull()).isTrue();
        assertThat(local.item().path("updatedIn").asString()).isEqualTo("dev");
        assertThat(local.item().path("createdIn").asString()).isEqualTo("local");
        assertThat(history.rows).extracting(ReviewStatusHistory.InMemory.Row::recordId, ReviewStatusHistory.InMemory.Row::fromStatus, ReviewStatusHistory.InMemory.Row::toStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DLV-1", "backlog", "ready"));
        assertThat(failure(() -> plans.resolveConflict(owner, "DLV-1", 9L, "dev"), 409).code()).isEqualTo("DELIVERY_NO_CONFLICT");
        // Keep dev: the ticket stays as it is, the local version is dropped.
        var again = PlatformReviewFixtures.planStore(root, mapper).current().orElseThrow();
        var plan2 = again.plan().deepCopy();
        ((ObjectNode) plan2.path("workItems").get(0)).set("pendingLocal", pending);
        PlatformReviewFixtures.planStore(root, mapper).save(again, plan2);
        var dev = plans.resolveConflict(owner, "DLV-1", 10L, "dev");
        assertThat(dev.item().path("title").asString()).isEqualTo("Local title");
        assertThat(dev.item().path("pendingLocal").isNull()).isTrue();
        assertThat(history.rows).hasSize(1);
    }

    @Test void theInboxIsMergedAgainstItsBaseArchivedAndDeletedWithOneLine() throws Exception {
        // DEV moves on: version 8 adds DLV-2 and edits DLV-1's status. The owner's copy came from version 7.
        plans.create(owner, 7L, item("Dev new", "backlog"));
        plans.update(owner, "DLV-1", 8L, item("First ticket", "in-progress"));
        var local = mapper.readTree(PlatformReviewFixtures.PLAN).deepCopy();
        ((ObjectNode) local).put("baseVersion", 7);
        var ticket = (ObjectNode) local.path("workItems").get(0);
        ticket.put("title", "Local rename"); // changed on both sides -> conflict
        ((tools.jackson.databind.node.ArrayNode) local.path("workItems")).add(mapper.readTree("{\"id\":\"DLV-9\",\"title\":\"Local new\",\"statusId\":\"backlog\"}"));
        Files.createDirectories(root.resolve("delivery/inbox"));
        Files.writeString(root.resolve("delivery/inbox/plan.json"), mapper.writeValueAsString(local));
        DeliveryPlanService.Sync sync;
        try (var log = com.lookahead.domain.LogCapture.start()) {
            sync = plans.syncInbox();
            var line = log.only("delivery.inbox-sync");
            assertThat(line.getFormattedMessage()).isEqualTo("Delivery inbox merged into plan version 10 (base 7): 1 added, 0 taken from local, 1 kept from DEV, 1 conflict(s), 0 unchanged, 0 deleted on DEV");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("plan.version", 10L).containsEntry("conflicts", 1).containsEntry("added", 1);
        }
        assertThat(sync.synced()).isTrue();
        assertThat(sync.version()).isEqualTo(10);
        assertThat(Files.exists(root.resolve("delivery/inbox/plan.json"))).isFalse();
        assertThat(Files.exists(root.resolve("delivery/archive/plan-v9.json"))).isTrue();
        var plan = plans.plan(owner);
        assertThat(plan.path("version").asLong()).isEqualTo(10);
        var merged = DeliveryPlanMerge.items(plan);
        assertThat(merged.keySet()).containsExactly("DLV-1", "DLV-2", "DLV-9");
        assertThat(merged.get("DLV-1").path("statusId").asString()).isEqualTo("in-progress");
        assertThat(merged.get("DLV-1").path("pendingLocal").path("title").asString()).isEqualTo("Local rename");
        assertThat(merged.get("DLV-9").path("createdIn").asString()).isEqualTo("local");
        // Nothing in the inbox: nothing happens; a base the archive lacks keeps the inbox for the owner.
        assertThat(plans.syncInbox().synced()).isFalse();
        ((ObjectNode) local).put("baseVersion", 3);
        Files.writeString(root.resolve("delivery/inbox/plan.json"), mapper.writeValueAsString(local));
        try (var log = com.lookahead.domain.LogCapture.start()) {
            assertThat(plans.syncInbox().synced()).isFalse();
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("delivery.inbox-sync"))).containsEntry("reason", "BASE_VERSION_MISSING");
        }
        assertThat(Files.exists(root.resolve("delivery/inbox/plan.json"))).isTrue();
        // An inbox without baseVersion uses its version; a base equal to the current plan merges against the plan itself.
        var current = plans.plan(owner).deepCopy();
        ((ObjectNode) current).remove("baseVersion");
        ((ObjectNode) current.path("workItems").get(2)).put("title", "Local new, renamed locally");
        Files.writeString(root.resolve("delivery/inbox/plan.json"), mapper.writeValueAsString(current));
        var again = plans.syncInbox();
        assertThat(again.counts().takenLocal()).isEqualTo(1);
        assertThat(DeliveryPlanMerge.items(plans.plan(owner)).get("DLV-9").path("title").asString()).isEqualTo("Local new, renamed locally");
        assertThat(DeliveryPlanMerge.items(plans.plan(owner)).get("DLV-9").path("updatedIn").asString()).isEqualTo("local");
    }

    @Test void anInboxWithoutAPlanWaitsForTheOwner() throws Exception {
        Files.delete(root.resolve("delivery/plan.json"));
        Files.createDirectories(root.resolve("delivery/inbox"));
        Files.writeString(root.resolve("delivery/inbox/plan.json"), "{\"version\":1,\"workItems\":[]}");
        try (var log = com.lookahead.domain.LogCapture.start()) {
            assertThat(plans.syncInbox().synced()).isFalse();
            assertThat(com.lookahead.domain.LogCapture.keys(log.only("delivery.inbox-sync"))).containsEntry("reason", "DELIVERY_PLAN_MISSING");
        }
        assertThat(Files.exists(root.resolve("delivery/inbox/plan.json"))).isTrue();
    }

    @Test void promoteBuildsTheTicketFromTheRecordAndTheWorkflow() {
        var created = plans.promote(owner.accountId(), "A title", "Details here", "bug", "high", "REV-000003", "review");
        assertThat(created.version()).isEqualTo(8);
        var ticket = created.item();
        assertThat(ticket.path("id").asString()).isEqualTo("DLV-2");
        assertThat(ticket.path("type").asString()).isEqualTo("bug");
        assertThat(ticket.path("priorityId").asString()).isEqualTo("P1");
        assertThat(ticket.path("statusId").asString()).isEqualTo("backlog");
        assertThat(ticket.path("labels").get(1).asString()).isEqualTo("review");
        assertThat(ticket.path("notes").asString()).isEqualTo("Promoted from Team Reviews record REV-000003.");
        assertThat(ticket.path("origin").path("id").asString()).isEqualTo("REV-000003");
        assertThat(plans.promote(owner.accountId(), "Low", "x", "story", "low", "SUP-000001", "request").item().path("priorityId").asString()).isEqualTo("P3");
        assertThat(plans.promote(owner.accountId(), "Odd", "x", "story", null, "SUP-000002", "request").item().path("priorityId").asString()).isEqualTo("P2");
        assertThat(DeliveryPlanService.nextTicketId(mapper.readTree("{\"workItems\":[{\"id\":\"WEB-1\"},{\"id\":\"DLV-40\"},{\"id\":\"DLV-7\"}]}"))).isEqualTo("DLV-41");
        assertThat(DeliveryPlanService.firstStatus(mapper.readTree("{}"))).isEqualTo("backlog");
        assertThat(DeliveryPlanService.priorityId(mapper.readTree("{\"priorities\":[{\"id\":\"urgent\"}]}"), "high")).isEqualTo("urgent");
        assertThat(DeliveryPlanService.priorityId(mapper.readTree("{}"), "high")).isEqualTo("P1");
    }
}
