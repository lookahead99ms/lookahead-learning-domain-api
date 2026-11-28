package com.lookahead.domain.service.access;

import com.lookahead.domain.client.SignInPoolClient;
import com.lookahead.domain.db.store.access.AccountRemoval;
import com.lookahead.domain.model.AccountRemovalModels.Changed;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Test only: the stored steps of the removal service (the decisions that were the store's until
 * DLV-1016) over a real store, for the database tests that need the {@code changed} answer. The
 * steps are package-private in the service: in the application they are reached only through its
 * three actions, behind the owner check.
 */
public final class AccountRemovalSteps {
  private final AccountRemovalService service;

  public AccountRemovalSteps(AccountRemoval store) {
    this.service =
        new AccountRemovalService(
            store,
            Mockito.mock(StaffAccessService.class),
            new StaticListableBeanFactory().getBeanProvider(SignInPoolClient.class));
  }

  public Changed disableAccount(UUID actor, UUID id) {
    return service.disableAccount(actor, id);
  }

  public Changed enableAccount(UUID actor, UUID id) {
    return service.enableAccount(actor, id);
  }

  public Changed removeAccount(UUID actor, UUID id) {
    return service.removeAccount(actor, id);
  }
}
