package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.testaccounts.TestAccountStudyPlanErasure;
import com.lookahead.domain.model.StudyPlanVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970 lane G: lane B's plans for the other lanes. {@link StudyPlanAccountErasure} (lane A's
 * account removal and learner deletion: ledgers and lifetime counter kept) and {@link
 * TestAccountStudyPlanErasure} (lane C's test-account erasure, which also clears the ledgers and
 * resets the counter, and reset) delegate to {@link StudyPlanErasureStore}; {@link
 * StudyPlanRosterActivity} (the DEV roster status) reads the account's {@code PLAN#} items: every
 * mutation of a plan, its versions or its activity rewrites the plan item's {@code updatedAt} in
 * the same transaction, so that one attribute is the latest plan write the SQL status computed
 * across four tables. {@link StudyPlanFacts} (the merge page) reads each live plan with its current
 * version's snapshot through the repository.
 */
@Component
@Profile("accounts")
public class StudyPlanFactsStore
    implements StudyPlanAccountErasure,
        TestAccountStudyPlanErasure,
        StudyPlanRosterActivity,
        StudyPlanFacts {
  private final StudyPlanErasureStore erasure;
  private final ObjectMapper mapper;
  private final StudyPlanStore versions;

  public StudyPlanFactsStore(
      DynamoDbClient client, DynamoTableNames tables, StudyPlanErasureStore erasure) {
    this(client, tables, erasure, new ObjectMapper());
  }

  /**
   * The account merge and the cross-lane fixtures build this one; the reads go through a private
   * repository on the client.
   */
  @Autowired
  public StudyPlanFactsStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      StudyPlanErasureStore erasure,
      ObjectMapper mapper) {
    this.erasure = erasure;
    this.mapper = mapper;
    this.versions = new StudyPlanStore(client, tables, mapper);
  }

  @Override
  public void eraseAccount(UUID account) {
    erasure.eraseAccount(account);
  }

  @Override
  public void erase(UUID account) {
    erasure.erase(account);
  }

  @Override
  public void resetPlans(UUID account) {
    erasure.resetPlans(account);
  }

  @Override
  public boolean hasPlan(UUID account) {
    for (var plan : plans(account)) if (!DynamoItems.flag(plan, "deleted")) return true;
    return false;
  }

  @Override
  public Instant latestPlanWriteAt(UUID account) {
    Instant latest = null;
    for (var plan : plans(account)) {
      Instant at = Instant.parse(DynamoItems.string(plan, "updatedAt"));
      if (latest == null || at.isAfter(latest)) latest = at;
    }
    return latest;
  }

  @Override
  public List<Card> cards(UUID account) {
    var cards = new ArrayList<Card>();
    for (var plan : plans(account)) {
      if (DynamoItems.flag(plan, "deleted")) continue;
      UUID id = DynamoItems.uuid(plan, "planId"),
          version = DynamoItems.uuid(plan, "currentVersion");
      JsonNode snapshot =
          versions.findVersion(account, id, version).map(StudyPlanVersion::snapshot).orElse(null);
      cards.add(
          new Card(
              id,
              DynamoItems.stringOrNull(plan, "name"),
              snapshot,
              DynamoItems.json(plan.get("progress"), mapper),
              DynamoItems.string(plan, "createdAt")));
    }
    cards.sort(Comparator.comparing(Card::createdAt).thenComparing(card -> card.id().toString()));
    return List.copyOf(cards);
  }

  private List<Map<String, AttributeValue>> plans(UUID account) {
    return versions.queryAll(DynamoItems.account(account), "PLAN#");
  }
}
