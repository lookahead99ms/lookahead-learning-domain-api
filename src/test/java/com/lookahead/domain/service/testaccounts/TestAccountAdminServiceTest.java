package com.lookahead.domain.service.testaccounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lookahead.domain.dto.MembershipPurchaseDecision;
import com.lookahead.domain.dto.TestAccountCreate;
import com.lookahead.domain.dto.TestAccountCreateKind;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-1016: the allowance with the roster status, and the one create route's four operations, each
 * handed to the service that owns it.
 */
class TestAccountAdminServiceTest {
  private final TestAccountService accounts = mock(TestAccountService.class);
  private final TestAccountCreationService creation = mock(TestAccountCreationService.class);
  private final TestAccountDevRosterService roster = mock(TestAccountDevRosterService.class);
  private final TestAccountAdminService admin =
      new TestAccountAdminService(accounts, creation, roster);
  private final AccountPrincipal actor =
      new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true);

  @Test
  void theAllowanceCarriesTheRosterStatus() {
    var status = new TestAccountDevRosterService.Status(27, 20, 1, 2, 3);
    when(creation.allowance(actor))
        .thenReturn(new TestAccountCreationService.Allowance(5, 2, 3, null, List.of()));
    when(roster.statusFor(actor)).thenReturn(status);
    var allowance = admin.allowance(actor);
    assertThat(allowance.limit()).isEqualTo(5);
    assertThat(allowance.used()).isEqualTo(2);
    assertThat(allowance.remaining()).isEqualTo(3);
    assertThat(allowance.roster()).isSameAs(status);
  }

  @Test
  void aPurchaseDecisionGoesToTheAccountService() {
    var decision = new MembershipPurchaseDecision(UUID.randomUUID(), "approve");
    var answer = new JsonMapper().createObjectNode().put("decided", true);
    var request = new TestAccountCreate(null, null, null, null, null, null, decision);
    when(accounts.decidePurchase(actor, decision)).thenReturn(answer);
    assertThat(admin.create(actor, TestAccountCreateKind.PURCHASE_DECISION, request))
        .isSameAs(answer);
    verifyNoInteractions(creation, roster);
  }

  @Test
  void restoringAndExtendingTheRosterGoToTheRosterService() {
    var restored = new TestAccountDevRosterService.Restored(4);
    var next = new TestAccountDevRosterService.Status(27, 27, 0, 0, 0);
    when(roster.restore(actor, new TestAccountDevRosterService.Restore("RESTORE")))
        .thenReturn(restored);
    when(roster.next(actor)).thenReturn(next);
    assertThat(
            admin.create(
                actor,
                TestAccountCreateKind.RESTORE_ROSTER,
                new TestAccountCreate(null, null, null, null, Boolean.TRUE, "RESTORE", null)))
        .isSameAs(restored);
    assertThat(
            admin.create(
                actor,
                TestAccountCreateKind.NEXT_ROSTER,
                new TestAccountCreate(null, null, null, Boolean.TRUE, null, null, null)))
        .isSameAs(next);
    verifyNoInteractions(accounts, creation);
  }

  @Test
  void anythingElseCreatesOneAccount() {
    var request = new TestAccountCreate(UUID.randomUUID(), "pw", null, false, false, null, null);
    var created =
        new TestAccountCreationService.Created(UUID.randomUUID(), "learner28@lookahead.dev", true);
    when(creation.create(actor, request)).thenReturn(created);
    assertThat(admin.create(actor, TestAccountCreateKind.ACCOUNT, request)).isSameAs(created);
    verify(creation).create(actor, request);
    verifyNoMoreInteractions(creation);
    verifyNoInteractions(accounts, roster);
  }
}
