package com.lookahead.domain.service.access;

import com.lookahead.domain.client.AccountDirectoryClient;
import com.lookahead.domain.db.store.access.AccountDirectory;
import com.lookahead.domain.model.AccountDirectoryModels.Account;
import com.lookahead.domain.model.AccountDirectoryModels.Page;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * The account directory of Local: the accounts are in the Identity service. The checks of the
 * query, the page and the owner are {@link AccountDirectoryService}'s; only where the accounts are
 * read from differs. A looked-up account gets its subject row in the store, as before. Exists only
 * in Local, like the Identity client it calls.
 */
@Service
@ConditionalOnExpression("'${app.deployment-environment:}' == 'local'")
public class AccountDirectoryLocalService extends AccountDirectoryService {
  private final AccountDirectory store;
  private final AccountDirectoryClient identity;

  /**
   * The client is the last argument: it is built after the store and the staff service, as it was
   * when the service asked for it in its constructor.
   */
  public AccountDirectoryLocalService(
      AccountDirectory store, StaffAccessService staff, AccountDirectoryClient identity) {
    super(store, staff);
    this.store = store;
    this.identity = identity;
  }

  @Override
  Page page(String term, int page) {
    return identity.list(term, page);
  }

  @Override
  Account match(String query) {
    return identity.lookup(query, account -> store.ensureSubject(account.accountId()));
  }

  @Override
  Account account(UUID id) {
    return identity.byId(id);
  }
}
