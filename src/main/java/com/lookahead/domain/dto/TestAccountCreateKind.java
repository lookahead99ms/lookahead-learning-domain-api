package com.lookahead.domain.dto;

/** What a body sent to {@code POST /api/v1/admin/test-accounts} asks for; the one route carries four operations. */
public enum TestAccountCreateKind {
    /** {@code {"purchaseDecision": {"purchaseId": "<uuid>", "decision": "approve"|"decline"}}}: a decision on a DEV purchase request. */
    PURCHASE_DECISION,
    /** {@code {"restoreRoster": true, "confirm": "RESTORE"}}: bring back deleted DEV roster accounts. */
    RESTORE_ROSTER,
    /** {@code {"roster": true}}: create the next DEV roster accounts. */
    NEXT_ROSTER,
    /** Anything else, a missing body included: create one test account. */
    ACCOUNT
}
