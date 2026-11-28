package com.lookahead.domain.client;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import java.util.UUID;

/**
 * Stands where the Local Identity operator stands in every environment that has no Identity service
 * (DEV and PROD). It calls nothing. No request reaches it today: AccountMaintenanceService refuses
 * first ({@code TestAccountModeService.requireLocal}). Should one reach it, the answer is the one
 * the operator gave there before DLV-1016, when it was a bean without a connection: the upstream
 * line OPERATOR_NOT_CONFIGURED and 503 TEST_IDENTITY_UNAVAILABLE.
 */
public final class AccountSignInNotConfiguredClient implements AccountSignInClient {
  @Override
  public void disableReal(UUID accountId) {
    throw refused("disable-real");
  }

  /** The line and the answer of an operator call that has no Identity connection to go to. */
  static AccountFailure refused(String operation) {
    long started = EventLog.started();
    EventLog.upstreamRefused(
        "Identity", "TestAccount." + operation, started, "OPERATOR_NOT_CONFIGURED");
    return TestAccountSignInClient.unavailable();
  }
}
