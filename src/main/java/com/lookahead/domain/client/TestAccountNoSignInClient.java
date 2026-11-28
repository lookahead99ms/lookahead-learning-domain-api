package com.lookahead.domain.client;

import java.util.UUID;

/**
 * DEV test accounts have no sign-in of their own (DLV-969 S35): nothing to provision, activate or
 * disable. DLV-1016: this was an anonymous class inside TestAccountCreationService; it is the bean
 * DEV gets while {@code TestAccountModeService.cloud()} holds ({@code
 * config.AccountSignInConfiguration}). Like {@code EmailLoggingClient} it stands where a real
 * client stands in another environment and calls nothing.
 */
public final class TestAccountNoSignInClient implements TestAccountSignInClient {
  @Override
  public String provision(UUID accountId, String username, String password) {
    return accountId.toString();
  }

  @Override
  public void activate(UUID accountId, String username) {}

  @Override
  public void disable(UUID accountId, String username) {}
}
