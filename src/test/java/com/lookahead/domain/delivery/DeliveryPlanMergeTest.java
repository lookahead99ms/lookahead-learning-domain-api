package com.lookahead.domain.delivery;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** DLV-971: the merge rule table, ticket by ticket, against the archived base. */
class DeliveryPlanMergeTest {
    static final JsonMapper mapper = JsonMapper.builder().build();
    static final String NOW = "2026-11-06T12:00:00Z";

    static ObjectNode plan(String title, String... items) {
        ObjectNode plan = mapper.createObjectNode();
        plan.put("schemaVersion", "delivery-plan/v1"); plan.put("version", 1); plan.put("title", title);
        var array = plan.putArray("workItems");
        for (String item : items) array.add(mapper.readTree(item));
        return plan;
    }
    static String item(String id, String title, String status, String extra) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"statusId\":\"" + status + "\",\"updatedAt\":\"2026-11-01T00:00:00Z\",\"createdIn\":\"dev\",\"updatedIn\":\"dev\"" + extra + "}";
    }
    static String item(String id, String title, String status) { return item(id, title, status, ""); }
    static ObjectNode find(ObjectNode plan, String id) { return DeliveryPlanMerge.items(plan).get(id); }

    @Test void changedLocallyOnlyTakesLocalAndStampsItLocal() {
        var base = plan("T", item("A", "Old", "backlog"));
        var dev = plan("T", item("A", "Old", "backlog"));
        var local = plan("T", item("A", "New title", "ready"));
        var result = DeliveryPlanMerge.merge(dev, base, local, NOW);
        var merged = find(result.plan(), "A");
        assertThat(merged.path("title").asString()).isEqualTo("New title");
        assertThat(merged.path("statusId").asString()).isEqualTo("ready");
        assertThat(merged.path("updatedIn").asString()).isEqualTo("local");
        assertThat(merged.path("createdIn").asString()).isEqualTo("dev");
        assertThat(merged.has("pendingLocal")).isFalse();
        assertThat(result.counts()).isEqualTo(new DeliveryPlanMerge.Counts(0, 1, 0, 0, 0, 0));
    }

    @Test void changedOnDevOnlyKeepsDev() {
        var base = plan("T", item("A", "Old", "backlog"));
        var dev = plan("T", item("A", "Dev edit", "in-progress"));
        var local = plan("T", item("A", "Old", "backlog"));
        var result = DeliveryPlanMerge.merge(dev, base, local, NOW);
        assertThat(find(result.plan(), "A").path("title").asString()).isEqualTo("Dev edit");
        assertThat(find(result.plan(), "A").path("updatedIn").asString()).isEqualTo("dev");
        assertThat(result.counts().keptDev()).isEqualTo(1);
        assertThat(result.counts().conflicts()).isZero();
    }

    @Test void changedOnBothSidesKeepsDevAndAttachesTheLocalVersionAsPendingLocal() {
        var base = plan("T", item("A", "Old", "backlog"));
        var dev = plan("T", item("A", "Dev edit", "in-progress"));
        var local = plan("T", item("A", "Local edit", "ready", ",\"pendingLocal\":{\"stale\":true}"));
        var result = DeliveryPlanMerge.merge(dev, base, local, NOW);
        var merged = find(result.plan(), "A");
        assertThat(merged.path("title").asString()).isEqualTo("Dev edit");
        assertThat(merged.path("statusId").asString()).isEqualTo("in-progress");
        assertThat(merged.path("pendingLocal").path("title").asString()).isEqualTo("Local edit");
        assertThat(merged.path("pendingLocal").has("pendingLocal")).isFalse();
        assertThat(result.counts().conflicts()).isEqualTo(1);
        // Both changed to the same content: no conflict, unchanged.
        var same = DeliveryPlanMerge.merge(plan("T", item("A", "Same", "ready")), base, plan("T", item("A", "Same", "ready")), NOW);
        assertThat(find(same.plan(), "A").has("pendingLocal")).isFalse();
        assertThat(same.counts().unchanged()).isEqualTo(1);
    }

    @Test void newOnEitherSideIsAddedOrKeptAndNewOnBothWithDifferentContentIsAConflict() {
        var base = plan("T");
        var dev = plan("T", item("D", "Dev new", "backlog"), item("B", "Both dev", "backlog"));
        var local = plan("T", "{\"id\":\"L\",\"title\":\"Local new\",\"statusId\":\"backlog\"}", item("B", "Both local", "backlog"));
        var result = DeliveryPlanMerge.merge(dev, base, local, NOW);
        assertThat(DeliveryPlanMerge.items(result.plan()).keySet()).containsExactly("D", "B", "L");
        var added = find(result.plan(), "L");
        assertThat(added.path("createdIn").asString()).isEqualTo("local");
        assertThat(added.path("updatedIn").asString()).isEqualTo("local");
        assertThat(added.path("updatedAt").asString()).isEqualTo(NOW);
        assertThat(find(result.plan(), "D").path("title").asString()).isEqualTo("Dev new");
        assertThat(find(result.plan(), "B").path("title").asString()).isEqualTo("Both dev");
        assertThat(find(result.plan(), "B").path("pendingLocal").path("title").asString()).isEqualTo("Both local");
        assertThat(result.counts()).isEqualTo(new DeliveryPlanMerge.Counts(1, 0, 1, 1, 0, 0));
    }

    @Test void missingFromTheInboxIsNotADeletionAndDeletedOnDevStaysDeleted() {
        var base = plan("T", item("A", "Kept", "backlog"), item("G", "Gone on dev", "backlog"));
        var dev = plan("T", item("A", "Kept", "backlog"));
        var local = plan("T", item("G", "Local still edits it", "ready"));
        var result = DeliveryPlanMerge.merge(dev, base, local, NOW);
        assertThat(DeliveryPlanMerge.items(result.plan()).keySet()).containsExactly("A");
        assertThat(result.counts().unchanged()).isEqualTo(1);
        assertThat(result.counts().deletedOnDev()).isEqualTo(1);
        assertThat(result.counts().words()).isEqualTo("0 added, 0 taken from local, 0 kept from DEV, 0 conflict(s), 1 unchanged, 1 deleted on DEV");
    }

    @Test void stampsNeverCountAsAChangeAndAMissingStampReadsAsLocal() {
        var base = plan("T", "{\"id\":\"A\",\"title\":\"Same\",\"statusId\":\"backlog\"}");
        var dev = plan("T", item("A", "Same", "backlog"));
        var local = plan("T", "{\"id\":\"A\",\"title\":\"Same\",\"statusId\":\"backlog\",\"updatedAt\":\"2026-11-05T00:00:00Z\",\"updatedIn\":\"local\"}");
        var result = DeliveryPlanMerge.merge(dev, base, local, NOW);
        assertThat(result.counts().unchanged()).isEqualTo(1);
        assertThat(find(result.plan(), "A").path("updatedIn").asString()).isEqualTo("dev");
        // Taken from a local file without stamps: createdIn and updatedIn read as local.
        var taken = DeliveryPlanMerge.merge(plan("T", "{\"id\":\"A\",\"title\":\"Same\",\"statusId\":\"backlog\"}"), base,
                plan("T", "{\"id\":\"A\",\"title\":\"Edited\",\"statusId\":\"backlog\"}"), NOW);
        assertThat(find(taken.plan(), "A").path("createdIn").asString()).isEqualTo("local");
        assertThat(find(taken.plan(), "A").path("updatedIn").asString()).isEqualTo("local");
        assertThat(find(taken.plan(), "A").path("updatedAt").asString()).isEqualTo(NOW);
    }

    @Test void originFollowsDevWhenLocalLacksItAndPlanLevelFieldsFollowTheSameRule() {
        var base = plan("Old title", item("A", "Old", "backlog"));
        var dev = plan("Old title", item("A", "Old", "backlog", ",\"origin\":{\"id\":\"REV-000001\",\"kind\":\"review\"}"));
        dev.put("currentStageId", "stage-2");
        base.put("currentStageId", "stage-1");
        var local = plan("Local title", item("A", "Edited", "backlog"));
        local.put("currentStageId", "stage-1");
        local.put("baseVersion", 1); local.put("version", 1);
        var result = DeliveryPlanMerge.merge(dev, base, local, NOW);
        assertThat(find(result.plan(), "A").path("origin").path("id").asString()).isEqualTo("REV-000001");
        assertThat(result.plan().path("title").asString()).isEqualTo("Local title");
        assertThat(result.plan().path("currentStageId").asString()).isEqualTo("stage-2");
        assertThat(result.plan().has("baseVersion")).isFalse();
        assertThat(result.plan().path("version").asInt()).isEqualTo(1);
        // Inputs are untouched.
        assertThat(dev.path("title").asString()).isEqualTo("Old title");
        assertThat(DeliveryPlanMerge.items(plan("T", "{\"title\":\"no id\"}", "{\"id\":7}"))).isEmpty();
    }
}
