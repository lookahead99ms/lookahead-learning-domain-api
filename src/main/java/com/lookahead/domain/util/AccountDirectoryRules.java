package com.lookahead.domain.util;

import com.lookahead.domain.exception.AccountFailure;

/**
 * The account directory's two refusals, built by the directory service and by the Local Identity
 * client. DLV-1016: they were static methods of AccountDirectoryService, then of the model class
 * AccountDirectoryModels.
 */
public final class AccountDirectoryRules {
  private AccountDirectoryRules() {}

  public static AccountFailure missing() {
    return new AccountFailure(
        404,
        "ACCOUNT_NOT_FOUND",
        "No eligible existing account matched. You can prepare a pending email assignment.");
  }

  public static AccountFailure unavailable() {
    return new AccountFailure(
        503, "ACCOUNT_LOOKUP_UNAVAILABLE", "Account lookup is unavailable. Retry later.");
  }
}
