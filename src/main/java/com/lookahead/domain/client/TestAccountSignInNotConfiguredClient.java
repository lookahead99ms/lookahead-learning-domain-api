package com.lookahead.domain.client;

import java.util.UUID;

/**
 * The test-account sign-in client of an environment that has neither the Local Identity service nor
 * DEV's database-only test accounts (PROD, and DEV while {@code app.test-accounts.identity} is not
 * {@code dev}). It calls nothing. No request reaches it today: TestAccountCreationService refuses
 * first ({@code TestAccountModeService.requireEnabled}). Should one reach it, the answer is the one
 * the Local Identity operator gave there before DLV-1016, when it was a bean without a connection:
 * the upstream line OPERATOR_NOT_CONFIGURED and 503 TEST_IDENTITY_UNAVAILABLE.
 */
public final class TestAccountSignInNotConfiguredClient implements TestAccountSignInClient {
  @Override
  public String provision(UUID accountId, String username, String password) {
    throw AccountSignInNotConfiguredClient.refused("provision");
  }

  @Override
  public void activate(UUID accountId, String username) {
    throw AccountSignInNotConfiguredClient.refused("activate");
  }

  @Override
  public void disable(UUID accountId, String username) {
    throw AccountSignInNotConfiguredClient.refused("disable");
  }
}
