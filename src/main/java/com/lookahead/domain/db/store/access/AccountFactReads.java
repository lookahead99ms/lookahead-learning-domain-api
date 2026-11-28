package com.lookahead.domain.db.store.access;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.membership.MembershipFacts;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * DLV-1016: the four stored facts about one account that the owner's directory and account
 * maintenance both read (each store had its own copy, word for word). The test-account registry and
 * the membership facts are asked for at each call, as before; either may be absent.
 */
final class AccountFactReads {
  private final AccountAggregateStore accounts;
  private final ObjectProvider<TestAccountRegistry> testAccounts;
  private final ObjectProvider<MembershipFacts> memberships;

  AccountFactReads(
      AccountAggregateStore accounts,
      ObjectProvider<TestAccountRegistry> testAccounts,
      ObjectProvider<MembershipFacts> memberships) {
    this.accounts = accounts;
    this.testAccounts = testAccounts;
    this.memberships = memberships;
  }

  boolean author(UUID id) {
    var c = accounts.control(id);
    return c != null && c.author();
  }

  boolean testAccount(UUID id) {
    var c = accounts.control(id);
    var registry = testAccounts.getIfAvailable();
    return (c != null && c.managedTest()) || (registry != null && registry.isTestAccount(id));
  }

  boolean grants(UUID id) {
    return !query(accounts.client(), accounts.accounts(), account(id), "GRANT#").isEmpty();
  }

  boolean openPurchases(UUID id) {
    var facts = memberships.getIfAvailable();
    return facts != null && facts.hasOpenPurchases(id);
  }
}
