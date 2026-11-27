package com.lookahead.domain.db.store.review;

import java.util.UUID;

/**
 * DLV-971: every status change on a record (kind review or request) or on a delivery ticket (kind ticket) is one
 * append-only item in Administrator Audit Events; S3 holds current state only. {@code actorId} is null for the
 * automatic close. A history failure never undoes the change it describes: the store throws it, and the caller
 * ({@code PlatformReviewService} and {@code DeliveryPlanService}, each in its {@code recordStatus}; DLV-1016) logs it and goes on.
 */
@FunctionalInterface
public interface PlatformReviewStatusHistory {
    void record(String recordId, String kind, UUID actorId, String fromStatus, String toStatus, boolean noteSet);

}
