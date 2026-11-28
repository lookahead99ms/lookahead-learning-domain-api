package com.lookahead.domain.service.access;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lookahead.domain.client.SignInPoolClient;
import com.lookahead.domain.db.store.access.AccountRemoval;
import com.lookahead.domain.dto.AccountRemovalConfirmation;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Local has no sign-in pool. The removal service answers there before anything is read or stored:
 * no options, and 404 for the three actions once the owner, the confirmation word and the id have
 * passed (DLV-1016: this order is why the missing pool is asked about in the service and not
 * answered by a stand-in client, which would be called only after the stored step).
 */
class AccountRemovalServiceNoPoolTest {
  final AccountRemoval store =
      mock(AccountRemoval.class, withSettings().mockMaker(MockMakers.PROXY));
  final StaffAccessService staff =
      mock(StaffAccessService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
  final AccountRemovalService removal =
      new AccountRemovalService(
          store, staff, new StaticListableBeanFactory().getBeanProvider(SignInPoolClient.class));
  final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true);
  final UUID learner = UUID.randomUUID();

  private static void refused(ThrowingCallable call, int status, String code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            AccountFailure.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(status);
              assertThat(failure.code()).isEqualTo(code);
            });
  }

  @Test
  void withoutAPoolThereAreNoOptionsAndTheThreeActionsAnswer404BeforeTheStoreIsAsked() {
    assertThat(removal.options(owner.accountId(), learner, false))
        .isEqualTo(AccountRemovalService.Options.NONE);
    refused(
        () -> removal.disable(owner, learner, new AccountRemovalConfirmation("DISABLE")),
        404,
        "SIGN_IN_ACCOUNT_NOT_FOUND");
    refused(() -> removal.enable(owner, learner), 404, "SIGN_IN_ACCOUNT_NOT_FOUND");
    refused(
        () -> removal.remove(owner, learner, new AccountRemovalConfirmation("DELETE")),
        404,
        "SIGN_IN_ACCOUNT_NOT_FOUND");
    verifyNoInteractions(store);
  }

  @Test
  void theChecksOfTheWordAndOfTheOwnersOwnAccountStillComeFirst() {
    refused(
        () -> removal.disable(owner, learner, new AccountRemovalConfirmation("nope")),
        422,
        "ACCOUNT_CONFIRMATION_REQUIRED");
    refused(() -> removal.remove(owner, learner, null), 422, "ACCOUNT_CONFIRMATION_REQUIRED");
    refused(
        () -> removal.disable(owner, owner.accountId(), new AccountRemovalConfirmation("DISABLE")),
        403,
        "OWNER_ACCOUNT_PROTECTED");
    refused(() -> removal.enable(owner, null), 403, "OWNER_ACCOUNT_PROTECTED");
    verify(staff, times(4)).requireOwner(owner);
    verifyNoInteractions(store);
  }
}
