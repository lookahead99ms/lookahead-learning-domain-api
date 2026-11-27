package com.lookahead.domain.db.store.access;

import com.lookahead.domain.db.store.membership.MembershipFacts;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DLV-970: the owner's directory facts on DynamoDB; test-account and membership facts come from lane C's lookups. */
@Repository
public class AccountAdminDirectoryStore implements AccountAdminDirectory {
    private final AccountAggregateStore accounts;
    private final AccountFactReads facts;

    @Autowired
    public AccountAdminDirectoryStore(DynamoDbClient client, DynamoTableNames tables, ObjectProvider<TestAccountRegistry> testAccounts, ObjectProvider<MembershipFacts> memberships) {
        this(client, tables, testAccounts, memberships, Clock.systemUTC());
    }
    AccountAdminDirectoryStore(DynamoDbClient client, DynamoTableNames tables, ObjectProvider<TestAccountRegistry> testAccounts, ObjectProvider<MembershipFacts> memberships, Clock clock) {
        this.accounts = new AccountAggregateStore(client, tables, clock); this.facts = new AccountFactReads(accounts, testAccounts, memberships);
    }
    @Override public boolean author(UUID account) { return facts.author(account); }
    @Override public boolean testAccount(UUID account) { return facts.testAccount(account); }
    @Override public boolean openPurchases(UUID account) { return facts.openPurchases(account); }
    @Override public boolean grants(UUID account) { return facts.grants(account); }
    @Override public int sameEmailAccounts(UUID account, String email) { return accounts.sameEmailAccounts(account, email); }
    @Override public List<Map<String, Object>> signInMethods(UUID account) { return accounts.signInMethods(account, false); }
    @Override public String github(UUID account) {
        var links = get(accounts.client(), accounts.accounts(), account(account), "LINKS").get("links");
        if (links == null || !links.hasM() || links.m().get("github") == null) return null;
        return links.m().get("github").s();
    }
}
