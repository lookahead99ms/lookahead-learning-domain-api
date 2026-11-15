package com.lookahead.domain.access;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DLV-970: the owner's directory facts on DynamoDB; test-account and membership facts come from lane C's lookups. */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoAdminDirectoryStore implements AdminDirectoryStore {
    private final DynamoCloudAccounts accounts;
    private final ObjectProvider<TestAccountRegistryLookup> testAccounts;
    private final ObjectProvider<MembershipFacts> memberships;

    @Autowired
    public DynamoAdminDirectoryStore(DynamoDbClient client, DynamoTableNames tables, ObjectProvider<TestAccountRegistryLookup> testAccounts, ObjectProvider<MembershipFacts> memberships) {
        this(client, tables, testAccounts, memberships, Clock.systemUTC());
    }
    DynamoAdminDirectoryStore(DynamoDbClient client, DynamoTableNames tables, ObjectProvider<TestAccountRegistryLookup> testAccounts, ObjectProvider<MembershipFacts> memberships, Clock clock) {
        this.accounts = new DynamoCloudAccounts(client, tables, clock); this.testAccounts = testAccounts; this.memberships = memberships;
    }
    @Override public boolean author(UUID account) { var c = accounts.control(account); return c != null && c.author(); }
    @Override public boolean testAccount(UUID account) {
        var c = accounts.control(account); var registry = testAccounts.getIfAvailable();
        return (c != null && c.managedTest()) || (registry != null && registry.isTestAccount(account));
    }
    @Override public boolean openPurchases(UUID account) { var facts = memberships.getIfAvailable(); return facts != null && facts.hasOpenPurchases(account); }
    @Override public boolean grants(UUID account) { return !query(accounts.client(), accounts.accounts(), account(account), "GRANT#").isEmpty(); }
    @Override public int sameEmailAccounts(UUID account, String email) { return accounts.sameEmailAccounts(account, email); }
    @Override public List<Map<String, Object>> signInMethods(UUID account) { return accounts.signInMethods(account, false); }
    @Override public String github(UUID account) {
        var links = get(accounts.client(), accounts.accounts(), account(account), "LINKS").get("links");
        if (links == null || !links.hasM() || links.m().get("github") == null) return null;
        return links.m().get("github").s();
    }
}
