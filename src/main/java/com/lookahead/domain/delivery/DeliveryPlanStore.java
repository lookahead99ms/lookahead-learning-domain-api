package com.lookahead.domain.delivery;

import com.lookahead.domain.review.platform.PlatformReviewObjects;
import com.lookahead.learning.content.exception.AccountFailure;
import java.io.UncheckedIOException;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-971 (owner, 2026-11-06): the delivery plan files under {@code delivery/} in the assets bucket. {@code plan.json}
 * is the plan the Domain serves and edits; every write raises its {@code version}, copies the previous file to
 * {@code archive/plan-v<N>.json} and writes the new one only if the object is still the one that was read
 * (If-Match on its ETag), so two edits cannot lose each other. {@code inbox/plan.json} is the owner's upload from
 * local, consumed by the next sync and deleted. Nothing here is ever seeded from the publication: the owner uploads
 * the first {@code plan.json} by hand.
 */
public final class DeliveryPlanStore {
    public static final String PLAN = "plan.json", INBOX = "inbox/plan.json";
    static final int MAX_BYTES = 16 * 1024 * 1024;

    /** A plan as read: its parsed object (always with a numeric version and a workItems array) and the version read. */
    public record Loaded(ObjectNode plan, byte[] bytes, String etag) {
        public long version() { return plan.path("version").asLong(); }
    }

    private final PlatformReviewObjects objects;
    private final ObjectMapper mapper;

    public DeliveryPlanStore(PlatformReviewObjects objects, ObjectMapper mapper) { this.objects = objects; this.mapper = mapper; }

    public Optional<Loaded> current() { return read(PLAN); }
    public Optional<Loaded> inbox() { return read(INBOX); }
    public Optional<Loaded> archived(long version) { return read("archive/plan-v" + version + ".json"); }

    /**
     * Archives {@code previous} and writes {@code next} as version {@code previous.version + 1}, only when plan.json
     * is still the object that was read. False: another writer came first; read again and repeat the edit.
     */
    public boolean save(Loaded previous, ObjectNode next) {
        long version = previous.version() + 1;
        next.put("version", version);
        next.remove("baseVersion");
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
        if (bytes.length > MAX_BYTES) throw new AccountFailure(413, "DELIVERY_PLAN_TOO_LARGE", "The delivery plan exceeds 16 MB.");
        String archive = "archive/plan-v" + previous.version() + ".json";
        storage(() -> { objects.put(archive, previous.bytes()); return null; });
        return storage(() -> objects.write(PLAN, bytes, previous.etag()));
    }

    public void deleteInbox() { storage(() -> { objects.delete(INBOX); return null; }); }

    private Optional<Loaded> read(String key) {
        var stored = storage(() -> objects.read(key));
        if (stored.isEmpty()) return Optional.empty();
        byte[] bytes = stored.get().bytes();
        if (bytes.length > MAX_BYTES) throw damaged(key, "it is larger than 16 MB");
        JsonNode node;
        try { node = mapper.readTree(bytes); }
        catch (JacksonException broken) { throw damaged(key, "it is not valid JSON"); }
        if (!(node instanceof ObjectNode plan) || !plan.path("workItems").isArray() || !plan.path("version").isIntegralNumber())
            throw damaged(key, "it is not a plan object with a numeric version and a workItems array");
        return Optional.of(new Loaded(plan, bytes, stored.get().version()));
    }

    private static AccountFailure damaged(String key, String words) {
        com.lookahead.domain.Events.failed(com.lookahead.domain.Events.EVENT, "delivery.read", "PLAN_UNREADABLE").addKeyValue("file", key)
                .log("The delivery plan file {} cannot be read: {}; the Delivery Plan answers 503 until it is repaired", key, words);
        return unavailable();
    }

    static <T> T storage(java.util.function.Supplier<T> call) {
        try { return call.get(); }
        catch (UncheckedIOException failure) { throw unavailable(); }
    }

    static AccountFailure unavailable() {
        return new AccountFailure(503, "DELIVERY_STORAGE_UNAVAILABLE", "The delivery plan storage is unavailable. Nothing was lost; try again.");
    }
}
