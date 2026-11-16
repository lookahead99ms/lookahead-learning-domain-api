package com.lookahead.domain.testaccounts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** DLV-970: where {@link ScenarioPurchases} writes its historical synthetic purchases. */
public interface ScenarioPurchaseStore {
    /** The plan definition with {@code priceCents}; {@link IllegalStateException} when the catalog lacks it. */
    ObjectNode definition(String id);
    /** One succeeded fixture purchase; {@code paymentId} null for a staff upgrade (then provider status not-required, no mock payment). */
    void insertPurchase(UUID id, UUID account, UUID actor, ObjectNode plan, Instant startsAt, Instant endsAt, int price,
            String discount, UUID replaces, boolean staff, String paymentId, Instant superseded);
    void audit(UUID actor, String action, String subject, JsonNode detail);
}
