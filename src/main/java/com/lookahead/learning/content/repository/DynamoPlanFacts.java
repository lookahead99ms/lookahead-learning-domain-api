package com.lookahead.learning.content.repository;

import com.lookahead.domain.access.LearningDataErasure;
import com.lookahead.domain.access.StudyPlanFacts;
import com.lookahead.learning.content.model.PlanVersion;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.testaccounts.PlanActivityLookup;
import com.lookahead.domain.testaccounts.PlanErasure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970 lane G: lane B's plans for the other lanes, in dynamodb mode. {@link LearningDataErasure} (lane A's account
 * removal and learner deletion: ledgers and lifetime counter kept) and {@link PlanErasure} (lane C's test-account
 * erasure, which also clears the ledgers and resets the counter, and reset) delegate to {@link DynamoPlanErasure};
 * {@link PlanActivityLookup} (the DEV roster status) reads the account's {@code PLAN#} items:
 * every mutation of a plan, its versions or its activity rewrites the plan item's {@code updatedAt} in the same
 * transaction, so that one attribute is the latest plan write the SQL status computed across four tables.
 * {@link StudyPlanFacts} (the merge page) reads each live plan with its current version's snapshot through the repository.
 */
@Component
@Profile("accounts")
public class DynamoPlanFacts implements LearningDataErasure, PlanErasure, PlanActivityLookup, StudyPlanFacts {
    private final DynamoPlanErasure erasure;
    private final ObjectMapper mapper;
    private final DynamoPlanRepository versions;

    public DynamoPlanFacts(DynamoDbClient client, DynamoTableNames tables, DynamoPlanErasure erasure) { this(client, tables, erasure, new ObjectMapper()); }
    /** The account merge and the cross-lane fixtures build this one; the reads go through a private repository on the client. */
    @org.springframework.beans.factory.annotation.Autowired
    public DynamoPlanFacts(DynamoDbClient client, DynamoTableNames tables, DynamoPlanErasure erasure, ObjectMapper mapper) {
        this.erasure = erasure; this.mapper = mapper;
        this.versions = new DynamoPlanRepository(client, tables, mapper);
    }

    @Override public void eraseAccount(UUID account) { erasure.eraseAccount(account); }
    @Override public void erase(UUID account) { erasure.erase(account); }
    @Override public void resetPlans(UUID account) { erasure.resetPlans(account); }

    @Override public boolean hasPlan(UUID account) {
        for (var plan : plans(account)) if (!DynamoItems.flag(plan, "deleted")) return true;
        return false;
    }
    @Override public Instant latestPlanWriteAt(UUID account) {
        Instant latest = null;
        for (var plan : plans(account)) {
            Instant at = Instant.parse(DynamoItems.string(plan, "updatedAt"));
            if (latest == null || at.isAfter(latest)) latest = at;
        }
        return latest;
    }
    @Override public List<Card> cards(UUID account) {
        var cards = new ArrayList<Card>();
        for (var plan : plans(account)) {
            if (DynamoItems.flag(plan, "deleted")) continue;
            UUID id = DynamoItems.uuid(plan, "planId"), version = DynamoItems.uuid(plan, "currentVersion");
            JsonNode snapshot = versions.findVersion(account, id, version).map(PlanVersion::snapshot).orElse(null);
            cards.add(new Card(id, DynamoItems.stringOrNull(plan, "name"), snapshot, DynamoItems.json(plan.get("progress"), mapper), DynamoItems.string(plan, "createdAt")));
        }
        cards.sort(Comparator.comparing(Card::createdAt).thenComparing(card -> card.id().toString()));
        return List.copyOf(cards);
    }

    private java.util.List<java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> plans(UUID account) {
        return versions.queryAll(DynamoItems.account(account), "PLAN#");
    }
}
