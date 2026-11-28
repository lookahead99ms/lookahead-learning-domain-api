package com.lookahead.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * The account directory's answers. DLV-1016: these were nested in AccountDirectoryService; the
 * store uses them too, so they live with the model. Values only: the two refusals are in
 * util.AccountDirectoryRules.
 */
public final class AccountDirectoryModels {
  private AccountDirectoryModels() {}

  public record Account(UUID accountId, String username, String displayName, String email) {}

  public record Page(List<Account> accounts, int page, boolean hasNext) {}
}
