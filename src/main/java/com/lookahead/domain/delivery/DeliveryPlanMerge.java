package com.lookahead.domain.delivery;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-971 (owner, 2026-11-06): the three-way merge of the owner's local plan (the inbox) into the DEV plan, ticket by
 * ticket against the archived DEV version the local copy was edited from (its {@code baseVersion}):
 * <ul>
 * <li>changed locally and unchanged on DEV since the base: take local;</li>
 * <li>changed on DEV and unchanged locally: keep DEV;</li>
 * <li>changed in both (or new on both sides with different content): keep DEV and attach the local version to the
 *     ticket as {@code pendingLocal}, a conflict the owner resolves on the page;</li>
 * <li>new locally: add; new on DEV: keep;</li>
 * <li>a ticket missing from the inbox is not a deletion (deletion happens only on DEV); a ticket deleted on DEV
 *     stays deleted whatever the inbox says.</li>
 * </ul>
 * A change is a difference outside the stamps ({@code createdIn}, {@code updatedIn}, {@code updatedAt},
 * {@code pendingLocal}); a missing stamp reads as {@code local}. Plan-level fields other than the work items follow
 * the same rule field by field. The same code runs for a local Domain against a local inbox.
 */
public final class DeliveryPlanMerge {
    static final Set<String> STAMPS = Set.of("createdIn", "updatedIn", "updatedAt", "pendingLocal");
    static final Set<String> PLAN_LEVEL_RESERVED = Set.of("workItems", "version", "baseVersion", "schemaVersion");

    public record Counts(int added, int takenLocal, int keptDev, int conflicts, int unchanged, int deletedOnDev) {
        public String words() {
            return added + " added, " + takenLocal + " taken from local, " + keptDev + " kept from DEV, " + conflicts + " conflict(s), "
                    + unchanged + " unchanged, " + deletedOnDev + " deleted on DEV";
        }
    }
    public record Result(ObjectNode plan, Counts counts) {}

    private DeliveryPlanMerge() {}

    /**
     * {@code dev} is the current DEV plan, {@code base} the archived version the local copy came from (the DEV plan
     * itself when nothing changed since), {@code local} the inbox file and {@code now} the stamp for items taken from
     * local without their own updatedAt. Returns a new plan object; the inputs are not changed.
     */
    public static Result merge(ObjectNode dev, ObjectNode base, ObjectNode local, String now) {
        ObjectNode result = dev.objectNode();
        var devItems = items(dev); var baseItems = items(base); var localItems = items(local);
        // Plan-level fields, field by field; the reserved ones come from DEV.
        var keys = new LinkedHashSet<String>();
        dev.propertyNames().forEach(keys::add);
        local.propertyNames().forEach(keys::add);
        for (String key : keys) {
            if (PLAN_LEVEL_RESERVED.contains(key)) { if (dev.has(key)) result.set(key, dev.get(key).deepCopy()); continue; }
            boolean localChanged = !Objects.equals(local.get(key), base.get(key)), devChanged = !Objects.equals(dev.get(key), base.get(key));
            JsonNode chosen = localChanged && !devChanged ? local.get(key) : dev.get(key);
            if (chosen != null) result.set(key, chosen.deepCopy());
        }
        int added = 0, taken = 0, kept = 0, conflicts = 0, unchanged = 0, deleted = 0;
        ArrayNode merged = result.putArray("workItems");
        var seen = new LinkedHashSet<String>();
        for (var entry : devItems.entrySet()) {
            String id = entry.getKey(); ObjectNode devItem = entry.getValue(), baseItem = baseItems.get(id), localItem = localItems.get(id);
            seen.add(id);
            if (localItem == null) { merged.add(devItem.deepCopy()); if (baseItem == null) kept++; else unchanged++; continue; }
            boolean localChanged = baseItem == null || !same(localItem, baseItem), devChanged = baseItem == null || !same(devItem, baseItem);
            if (same(localItem, devItem)) { merged.add(devItem.deepCopy()); unchanged++; continue; }
            if (localChanged && !devChanged) { merged.add(takeLocal(localItem, devItem, now)); taken++; continue; }
            if (!localChanged) { merged.add(devItem.deepCopy()); kept++; continue; }
            ObjectNode conflict = devItem.deepCopy();
            conflict.set("pendingLocal", stripped(localItem));
            merged.add(conflict); conflicts++;
        }
        for (var entry : localItems.entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            if (baseItems.containsKey(entry.getKey())) { deleted++; continue; }
            merged.add(takeLocal(entry.getValue(), null, now)); added++;
        }
        return new Result(result, new Counts(added, taken, kept, conflicts, unchanged, deleted));
    }

    /** Work items by id, in file order; an item without a string id is skipped. */
    static Map<String, ObjectNode> items(JsonNode plan) {
        var map = new LinkedHashMap<String, ObjectNode>();
        for (JsonNode item : plan.path("workItems"))
            if (item instanceof ObjectNode object && object.path("id").isString()) map.putIfAbsent(object.path("id").asString(), object);
        return map;
    }

    /** Equal outside the stamps. */
    static boolean same(ObjectNode left, ObjectNode right) { return stripped(left).equals(stripped(right)); }

    static ObjectNode stripped(ObjectNode item) {
        ObjectNode copy = item.deepCopy();
        STAMPS.forEach(copy::remove);
        return copy;
    }

    /** The local item as the merged one: its own fields, DEV's createdIn and origin when it lacks them, updated in local. */
    static ObjectNode takeLocal(ObjectNode local, ObjectNode dev, String now) {
        ObjectNode item = local.deepCopy();
        item.remove("pendingLocal");
        if (!item.path("createdIn").isString()) item.put("createdIn", dev != null && dev.path("createdIn").isString() ? dev.path("createdIn").asString() : "local");
        if (dev != null && dev.hasNonNull("origin") && !item.hasNonNull("origin")) item.set("origin", dev.get("origin").deepCopy());
        item.put("updatedIn", "local");
        if (!item.path("updatedAt").isString()) item.put("updatedAt", now);
        return item;
    }
}
