package com.lookahead.domain.service.testaccounts;

import com.lookahead.domain.dto.TestAccountCreate;
import com.lookahead.domain.dto.TestAccountCreateKind;
import com.lookahead.domain.security.token.AccountPrincipal;
import org.springframework.stereotype.Service;

/**
 * The two answers of the Test accounts page that need more than one of the test-account services:
 * the allowance with the roster status, and the one create route that carries four operations.
 * DLV-1016: the controller combined the services itself.
 */
@Service
public class TestAccountAdminService {
  private final TestAccountService accounts;
  private final TestAccountCreationService creation;
  private final TestAccountDevRosterService roster;

  public TestAccountAdminService(
      TestAccountService accounts,
      TestAccountCreationService creation,
      TestAccountDevRosterService roster) {
    this.accounts = accounts;
    this.creation = creation;
    this.roster = roster;
  }

  /** The creator's allowance, with the DEV roster status the same page shows. */
  public TestAccountCreationService.Allowance allowance(AccountPrincipal actor) {
    return creation.allowance(actor).with(roster.statusFor(actor));
  }

  /**
   * The one route carries four operations (the Gateway BFF forwards only this route); {@code kind}
   * says which one the body asked for.
   */
  public Object create(
      AccountPrincipal actor, TestAccountCreateKind kind, TestAccountCreate request) {
    return switch (kind) {
      case PURCHASE_DECISION -> accounts.decidePurchase(actor, request.purchaseDecision());
      case RESTORE_ROSTER ->
          roster.restore(actor, new TestAccountDevRosterService.Restore(request.confirm()));
      case NEXT_ROSTER -> roster.next(actor);
      case ACCOUNT -> creation.create(actor, request);
    };
  }
}
