package com.lookahead.domain.membership;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: the membership aggregate behind {@link MembershipService}. One method is one transaction with all its
 * conditions; the service keeps validation, pricing and policy. {@link DynamoMembershipStore} commits the aggregate under the account's
 * {@code ACCOUNT#id / META} revision and the catalog revision.
 */
public interface MembershipStore {
    /** The whole catalog, each definition carrying {@code priceCents}, ordered by price then id. */
    List<ObjectNode> catalog();
    /** One definition with {@code priceCents}, or null when the plan is unknown. */
    ObjectNode definition(String id);
    /** The sale running at {@code now} (latest start wins), or null. */
    ObjectNode activeSale(Instant now);
    /** The purchase row the API answers with ({@code fingerprint} included), or null when the account has none by that id. */
    ObjectNode purchase(UUID account, UUID id);
    /** Ids of the account's succeeded, scheduled and expired purchases, newest first. */
    List<UUID> accessIds(UUID account);
    /** Plan snapshots granting access at {@code now}: succeeded or scheduled, not superseded, within dates; by plan id then id. */
    List<JsonNode> activeSnapshots(UUID account, Instant now);
    /** DEV: an active test account staff created on the owner's domain. */
    boolean staffCreatedTestAccount(UUID account);
    /** Pending or scheduled purchases that replace {@code replacesId}. */
    int replacementsScheduled(UUID replacesId);
    /** The oldest DEV request waiting for a decision (not superseded), or null. */
    PendingRow pendingRequest(UUID account, String awaiting);
    record PendingRow(UUID id, Instant createdAt) {}
    /** Who sent a DEV request: the {@code requestedBy} its purchase-created audit row names, or null. */
    UUID requestedBy(UUID purchase);
    /** The latest purchase-approved audit row after the account's latest reset whose purchase was paid or scheduled, or null. */
    Approval approvedRequest(UUID account);
    record Approval(UUID purchaseId, UUID approvedBy, Instant approvedAt) {}
    /** One transaction as {@code actor}; the body locks the account it works on through {@link Work#lockAccount}. */
    <T> T transact(UUID actor, Function<Work, T> body);
    /** The owner changes a price: the price before, the changed definition; audited. */
    PriceChange changePrice(UUID actor, String id, int price);
    record PriceChange(int before, ObjectNode changed) {}
    /** A new sale when no running sale overlaps it (else 409 SALE_OVERLAP); audited. Returns its id. */
    UUID startSale(UUID actor, MembershipService.Sale sale);
    /** Stops a sale (404 SALE_NOT_FOUND); audited. */
    void stopSale(UUID actor, UUID id, Instant now);

    /** A new purchase row exactly as the checkout transaction inserts it. */
    record NewPurchase(UUID id, UUID account, String planId, ObjectNode plan, String fingerprint, String key, int price,
            Instant startsAt, Instant endsAt, String source, UUID actor, UUID replacesId) {}

    /** The reads and writes of one purchase transaction, in the order the service issues them. */
    interface Work {
        /** Serialises every writer of the account (404 ACCOUNT_NOT_FOUND when it has no subject). */
        void lockAccount(UUID account);
        /** The account owning a purchase id, or null. */
        UUID purchaseAccount(UUID purchaseId);
        /** As {@link MembershipStore#purchase}; {@code lock} is the SQL row lock. */
        ObjectNode purchase(UUID account, UUID id, boolean lock);
        List<UUID> purchasesByKey(UUID account, String key);
        /** Provider statuses of pending purchases that still block: every one except a superseded DEV request. */
        List<String> pendingProviderStatuses(UUID account, String awaiting);
        ObjectNode definition(String id);
        ObjectNode activeSale(Instant now);
        int replacementsScheduled(UUID replacesId);
        List<UUID> accessIds(UUID account);
        void insertPurchase(NewPurchase purchase);
        int paymentAttempts(UUID purchase);
        void recordAttempt(UUID purchase, String status, String error);
        /** status failed, provider_status validation-failed. */
        void failValidation(UUID purchase);
        /** status failed with the given provider status, updated now. */
        void decline(UUID purchase, String providerStatus);
        /** The provider's answer: status, payment id and provider status, updated now. */
        void settle(UUID purchase, String state, String providerPaymentId, String providerStatus);
        /** True when this provider event was not recorded before. */
        boolean recordProviderEvent(String eventId, UUID purchase);
        void supersede(UUID account, UUID purchase, Instant at);
        void audit(UUID actor, String action, String subject, JsonNode detail);
        /** Prior decisions on a request: purchase-approved / purchase-declined audit actions. */
        List<String> decisions(UUID purchase);
    }
}
