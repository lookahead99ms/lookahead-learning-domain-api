package com.lookahead.learning.content.repository;

import java.util.UUID;

/**
 * DLV-970: the two erasures of an account's learning data in dynamodb mode, matching the two SQL paths. Both remove
 * every plan, version, activity, mutation receipt, lifecycle row and support receipt and keep the plan-number sequence
 * on {@code ACCOUNT#id / META}; they differ on the free-plan ledgers. Resumable: calling either again continues or is a
 * no-op. Consumed through {@link DynamoPlanFacts}, which adapts it to the cross-lane interfaces
 * {@code access.LearningDataErasure} (account removal) and {@code testaccounts.PlanErasure} (test accounts).
 */
public interface PlanErasure {
    /**
     * Test-account erasure ({@code JdbcAccountDataErasure}): the free-plan ledgers go too and the lifetime
     * free-creation counter on META returns to 0, so a reseeded test account starts with its full allowance.
     */
    void erase(UUID account);
    /**
     * Account removal ({@code JdbcAccountRemovalStore.erase}): the free-plan ledgers and the lifetime counter stay;
     * the runtime may not delete them, and a returning person has a new account ID.
     */
    void eraseAccount(UUID account);
}
