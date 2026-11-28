package com.lookahead.domain.db.store.plan;

import java.util.UUID;

// DLV-970 cross-lane: provided by lane B. Erases every plan, version, receipt, lifecycle row and
// free ledger of an
// account (lifetime quota counters kept, as the design says) on the selected store; resumable, safe
// to repeat.
public interface StudyPlanAccountErasure {
  void eraseAccount(UUID account);
}
