package com.lookahead.domain.db.store.testaccounts;

import java.util.UUID;

// DLV-970 cross-lane: provided by lane C. Whether an account is in the managed test-account
// registry
// (test_account_registry → ACCOUNT#id / TEST) on the selected store.
public interface TestAccountRegistry {
  boolean isTestAccount(UUID account);
}
