package com.lookahead.domain.dto;

import java.util.UUID;

/**
 * DLV-969 (owner, 2026-11-04): the owner's or an admin's decision on a DEV purchase request: {@code
 * approve} or {@code decline}. DLV-1016: was nested in MembershipService.
 */
public record MembershipPurchaseDecision(UUID purchaseId, String decision) {}
