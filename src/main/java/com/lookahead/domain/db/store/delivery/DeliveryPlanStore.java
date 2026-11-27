package com.lookahead.domain.db.store.delivery;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.util.DeliveryPlanRules;
import com.lookahead.domain.db.store.review.PlatformReviewObjects;
import com.lookahead.domain.exception.AccountFailure;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-971 (owner, 2026-11-06): the delivery plan files under {@code delivery/} in the assets bucket. {@code plan.json}
 * is the plan the Domain serves and edits; every write raises its {@code version}, copies the previous file to
 * {@code archive/plan-v<N>.json} and writes the new one only if the object is still the one that was read
 * (If-Match on its ETag), so two edits cannot lose each other. {@code inbox/plan.json} is the owner's upload from
 * local, consumed by the next sync and deleted. DLV-998 (owner, 2026-11-09): the published plan seeds the first
 * {@code plan.json} and later arrives through the inbox at each start ({@code DeliveryPlanReleaseImportJob}); the digest of the
 * last import sits in {@code inbox/.release-import.json}.
 */
public final class DeliveryPlanStore {
    public static final String PLAN = "plan.json", INBOX = "inbox/plan.json";
    /** The key of the release-import marker in the delivery store (DLV-1016: the job reads it from here, not the other way round). */
    public static final String RELEASE_IMPORT = "inbox/.release-import.json";

    /** A plan as read: its parsed object (always with a numeric version and a workItems array) and the version read. */
    public record Loaded(ObjectNode plan, byte[] bytes, String etag) {
        public long version() { return plan.path("version").asLong(); }
    }

    /** The edited plan, written out, is larger than {@link DeliveryPlanRules#MAX_BYTES}: nothing was archived or written. */
    public static final class TooLarge extends RuntimeException {
        TooLarge() { super("The delivery plan file would exceed " + DeliveryPlanRules.MAX_BYTES + " bytes"); }
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
     * {@link TooLarge} before anything is written when the new file would exceed {@link DeliveryPlanRules#MAX_BYTES}; the service answers it.
     */
    public boolean save(Loaded previous, ObjectNode next) {
        long version = previous.version() + 1;
        next.put("version", version);
        next.remove("baseVersion");
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
        if (bytes.length > DeliveryPlanRules.MAX_BYTES) throw new TooLarge();
        String archive = "archive/plan-v" + previous.version() + ".json";
        storage(() -> { objects.put(archive, previous.bytes()); return null; });
        return storage(() -> objects.write(PLAN, bytes, previous.etag()));
    }

    public void deleteInbox() { storage(() -> { objects.delete(INBOX); return null; }); }

    /**
     * DLV-998: the first plan, written as it is (its own version, no archive) only while no plan.json exists. False:
     * another task seeded it first; read the current plan and merge instead. The caller has had the bytes checked by
     * {@code DeliveryPlanFileValidator} (size included) before this is called.
     */
    public boolean seed(byte[] bytes) {
        return storage(() -> objects.write(PLAN, bytes, null));
    }

    /** The last release import's marker (its {@code sha256}, release and plan version), or empty before the first import. */
    public Optional<ObjectNode> releaseImport() {
        var stored = storage(() -> objects.read(RELEASE_IMPORT));
        if (stored.isEmpty()) return Optional.empty();
        try { return mapper.readTree(stored.get().bytes()) instanceof ObjectNode marker ? Optional.of(marker) : Optional.empty(); }
        catch (JacksonException broken) { return Optional.empty(); }
    }

    public void recordReleaseImport(byte[] bytes) { storage(() -> { objects.put(RELEASE_IMPORT, bytes); return null; }); }

    /**
     * DLV-971 (owner, 2026-11-07): the plan file uploaded from the Delivery Plan page becomes the inbox, replacing any
     * file already there; {@code DeliveryPlanFileValidator} has checked it first. Unconditional: the owner's upload wins.
     */
    public void putInbox(byte[] bytes) { storage(() -> { objects.put(INBOX, bytes); return null; }); }

    private Optional<Loaded> read(String key) {
        var stored = storage(() -> objects.read(key));
        if (stored.isEmpty()) return Optional.empty();
        byte[] bytes = stored.get().bytes();
        if (bytes.length > DeliveryPlanRules.MAX_BYTES) throw damaged(key, "it is larger than 16 MB");
        JsonNode node;
        try { node = mapper.readTree(bytes); }
        catch (JacksonException broken) { throw damaged(key, "it is not valid JSON"); }
        if (!(node instanceof ObjectNode plan) || !plan.path("workItems").isArray() || !plan.path("version").isIntegralNumber())
            throw damaged(key, "it is not a plan object with a numeric version and a workItems array");
        return Optional.of(new Loaded(plan, bytes, stored.get().version()));
    }

    private static AccountFailure damaged(String key, String words) {
        EventLog.failed(EventLog.EVENT, "delivery.read", "PLAN_UNREADABLE").addKeyValue("file", key)
                .log("The delivery plan file {} cannot be read: {}; the Delivery Plan answers 503 until it is repaired", key, words);
        return unavailable();
    }

    static <T> T storage(Supplier<T> call) {
        try { return call.get(); }
        catch (UncheckedIOException failure) { throw unavailable(); }
    }

    // Exception, decided 2026-11-25 (DLV-1016): this store answers its own storage failures (an object call that failed, a
    // damaged plan file) as the 503 DELIVERY_STORAGE_UNAVAILABLE AccountFailure; DeliveryPlanService throws the same one
    // when the plan is not there or a save lost every retry. A storage exception type answered with the same 503 by a
    // method in security.handler.AccountErrorHandler was not made: the HTTP answer would stay the same, but
    // DeliveryPlanService.ticketStatuses catches AccountFailure to answer Team Reviews without ticket statuses (another
    // type would fail that read), and job.SweepRunner prints the type of the exception it caught: the line "The delivery
    // inbox sync sweep did not complete (AccountFailure)" and the inbox result "did not complete (AccountFailure)" in
    // the answer of POST /api/v1/sweeps/run would name the new type.
    public static AccountFailure unavailable() {
        return new AccountFailure(503, "DELIVERY_STORAGE_UNAVAILABLE", "The delivery plan storage is unavailable. Nothing was lost; try again.");
    }
}
