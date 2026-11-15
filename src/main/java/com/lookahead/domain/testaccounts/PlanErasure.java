package com.lookahead.domain.testaccounts;

import java.util.UUID;

// DLV-970 cross-lane: provided by lane B
/** Lane B's plan erasure, as the SQL test-account paths used it. */
public interface PlanErasure {
    /** Test-account erasure: every plan, version, activity, mutation receipt, lifecycle, support receipt and free ledger; the lifetime free-creation counter returns to 0 (the plan-number sequence is kept), as the SQL test-account erasure deletes the ledger rows. */
    void erase(UUID account);
    /** Test-account reset: plans (with versions, activity and lifecycle) deleted and mutation receipts tombstoned; free ledgers kept. */
    void resetPlans(UUID account);
}
