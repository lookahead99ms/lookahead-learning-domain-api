package com.lookahead.domain.db.store.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipFacts;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.signin.SignInStoreLocalFixture;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The account facts the owner's directory and account maintenance share, and the one deletion of a
 * subject's grants.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
class AccountFactReadsDatabaseTest {
  private static final DynamoTableNames TABLES = SignInStoreLocalFixture.TABLES;
  private static DynamoDbClient client;
  private AccountAggregateStore accounts;

  @BeforeAll
  static void connect() {
    client = SignInStoreLocalFixture.connect();
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
  }

  @BeforeEach
  void fresh() {
    SignInStoreLocalFixture.purgeLaneA(client);
    accounts = new AccountAggregateStore(client, TABLES, Clock.systemUTC());
  }

  @Test
  void anAccountNobodyKnowsHasNoFactsWhenTheOtherLookupsAreAbsent() {
    var none = new StaticListableBeanFactory();
    var facts =
        new AccountFactReads(
            accounts,
            none.getBeanProvider(TestAccountRegistry.class),
            none.getBeanProvider(MembershipFacts.class));
    UUID unknown = UUID.randomUUID();
    assertThat(facts.author(unknown)).isFalse();
    assertThat(facts.testAccount(unknown)).isFalse();
    assertThat(facts.openPurchases(unknown)).isFalse();
    assertThat(facts.grants(unknown)).isFalse();
  }

  @Test
  void theRegistryAndTheMembershipFactsAnswerWhenTheyArePresent() {
    UUID test = UUID.randomUUID(), buyer = UUID.randomUUID();
    var beans = new StaticListableBeanFactory();
    beans.addBean("tests", (TestAccountRegistry) test::equals);
    beans.addBean(
        "memberships",
        new MembershipFacts() {
          @Override
          public boolean hasOpenPurchases(UUID account) {
            return account.equals(buyer);
          }

          @Override
          public boolean hasPaidMembership(UUID account) {
            return false;
          }
        });
    var facts =
        new AccountFactReads(
            accounts,
            beans.getBeanProvider(TestAccountRegistry.class),
            beans.getBeanProvider(MembershipFacts.class));
    assertThat(facts.testAccount(test)).isTrue();
    assertThat(facts.testAccount(buyer)).isFalse();
    assertThat(facts.openPurchases(buyer)).isTrue();
    assertThat(facts.openPurchases(test)).isFalse();
  }

  @Test
  void everyGrantOfASubjectIsDeletedAndOtherSubjectsKeepTheirs() {
    var none = new StaticListableBeanFactory();
    var facts =
        new AccountFactReads(
            accounts,
            none.getBeanProvider(TestAccountRegistry.class),
            none.getBeanProvider(MembershipFacts.class));
    var subjects = new AccountSubjectStore(client, TABLES, Clock.systemUTC());
    UUID leaving = UUID.randomUUID(), staying = UUID.randomUUID();
    // One more than a transaction holds, so the deletion needs its second transaction.
    for (int topic = 0; topic <= DynamoWrites.MAX_ITEMS; topic++)
      subjects.grant(leaving, "learn:topic-" + topic);
    subjects.grant(staying, "learn:docker");
    assertThat(facts.grants(leaving)).isTrue();
    AccountSubjectStore.revokeAll(accounts, leaving);
    assertThat(facts.grants(leaving)).isFalse();
    assertThat(subjects.grantTopics(leaving)).isEmpty();
    assertThat(subjects.grantTopics(staying)).containsExactly("learn:docker");
  }
}
