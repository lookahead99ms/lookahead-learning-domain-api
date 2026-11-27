package com.lookahead.domain.dto;

import java.util.UUID;

/**
 * {@code scenario}: DLV-969 S35, a learner {@code TestAccountTemplates#creatable} id the account starts from (none: a
 * learner without access, as before). {@code roster}: the owner's DEV roster ({@code TestAccountDevRosterService}) through the same route.
 * {@code password}: Local only. {@code restoreRoster} with {@code confirm} "RESTORE": the owner's Restore of deleted
 * DEV roster accounts ({@code TestAccountDevRosterService#restore}), on the route the Gateway already forwards.
 * {@code purchaseDecision}: DLV-969 (owner, 2026-11-04), the owner's or an admin's decision on a DEV purchase
 * request ({@code TestAccountService#decidePurchase}), on the same route.
 * <p>Each request sends only its own fields, so the flags are {@code Boolean}: a JSON reader refuses an absent value
 * for a primitive {@code boolean} (DLV-969, 2026-11-04).
 */
public record TestAccountCreate(UUID key, String password, String scenario, Boolean roster, Boolean restoreRoster, String confirm,
        MembershipPurchaseDecision purchaseDecision) {
    /** True only when the body asked for the next DEV roster accounts. */
    public boolean createsRoster() { return Boolean.TRUE.equals(roster); }
    /** True only when the body asked to restore deleted DEV roster accounts. */
    public boolean restoresRoster() { return Boolean.TRUE.equals(restoreRoster); }
    @Override public String toString() { return "TestAccountCreate[redacted]"; }
}
