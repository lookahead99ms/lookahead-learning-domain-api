package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.model.MembershipModels;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: the plan catalog and the sales of the membership aggregate. One method is one transaction with all its
 * conditions, fenced on the catalog (or sales) revision. DLV-1016: split from the former membership store interface.
 */
public interface MembershipPlanCatalog {
    /** The whole catalog, each definition carrying {@code priceCents}, ordered by price then id. */
    List<ObjectNode> catalog();
    /** One definition with {@code priceCents}, or null when the plan is unknown. */
    ObjectNode definition(String id);
    /** The sale running at {@code now} (latest start wins), or null. */
    ObjectNode activeSale(Instant now);
    /** The owner changes a price: the price before, the changed definition; audited. Null when the plan is unknown (nothing written). */
    PriceChange changePrice(UUID actor, String id, int price);
    record PriceChange(int before, ObjectNode changed) {}
    /** A new sale when no running sale overlaps it; audited. Returns its id, or null when a sale already covers the period (nothing written). */
    UUID startSale(UUID actor, MembershipModels.Sale sale);
    /** Stops a sale; audited. False when no sale has that id (nothing written). */
    boolean stopSale(UUID actor, UUID id, Instant now);
}
