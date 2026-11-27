package com.lookahead.domain.db.store.membership;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: the purchase transaction of the membership aggregate behind {@code MembershipService}. The service keeps
 * validation, pricing and policy; {@link MembershipStore} commits the body's writes together under the account's
 * {@code ACCOUNT#id / META} revision and the catalog revision. DLV-1016: one of the four interfaces the former
 * membership store interface was split into ({@link MembershipPlanCatalog}, {@link MembershipPurchases},
 * {@link MembershipRequests}); one class implements all four.
 */
public interface MembershipCheckout {
    /** One transaction as {@code actor}; the body locks the account it works on through {@link Work#lockAccount}. */
    <T> T transact(Function<Work, T> body);

    /** A new purchase row exactly as the checkout transaction inserts it. */
    record NewPurchase(UUID id, UUID account, String planId, ObjectNode plan, String fingerprint, String key, int price,
            Instant startsAt, Instant endsAt, String source, UUID actor, UUID replacesId) {}

    /**
     * The reads and writes of one purchase transaction: every job below on the one object the transaction hands out.
     * DLV-1016: it declares nothing of its own; a helper that needs one job takes that job's interface.
     */
    interface Work extends AccountPurchases, Offer, PurchaseChanges, Payment, AuditTrail {}

    /** The account, locked, and the purchases it already has, as the body reads them. */
    interface AccountPurchases {
        /** Serialises every writer of the account. False when the account has no subject: nothing is locked and the caller refuses. */
        boolean lockAccount(UUID account);
        /** The account owning a purchase id, or null. */
        UUID purchaseAccount(UUID purchaseId);
        /** As {@link MembershipPurchases#purchase}. */
        ObjectNode purchase(UUID account, UUID id);
        List<UUID> purchasesByKey(UUID account, String key);
        /** Provider statuses of pending purchases that still block: every one except a superseded DEV request. */
        List<String> pendingProviderStatuses(UUID account, String awaiting);
        int replacementsScheduled(UUID replacesId);
        List<UUID> accessIds(UUID account);
    }

    /** What is on offer: the plan being bought and the sale that is running. */
    interface Offer {
        ObjectNode definition(String id);
        ObjectNode activeSale(Instant now);
    }

    /** The purchase rows a checkout adds or ends: the new purchase, and the one it replaces. */
    interface PurchaseChanges {
        void insertPurchase(NewPurchase purchase);
        void supersede(UUID account, UUID purchase, Instant at);
    }

    /** The payment of one pending purchase: its attempts and how it ended. */
    interface Payment {
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
    }

    /** The audit rows of a purchase: writing one, and reading the decisions already made on a request. */
    interface AuditTrail {
        void audit(UUID actor, String action, String subject, JsonNode detail);
        /** Prior decisions on a request: purchase-approved / purchase-declined audit actions. */
        List<String> decisions(UUID purchase);
    }
}
