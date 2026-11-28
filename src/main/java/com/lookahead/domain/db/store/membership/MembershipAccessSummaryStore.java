package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.db.store.access.AccountGrants;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.util.MembershipRules;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: purchases from the account's membership items; grants through lane A's {@link
 * AccountGrants}, which {@code db.store.access.AccountRecordsStore} implements in dynamodb mode
 * (the SQL {@code account_grants} valid now plus {@code staff_access.topic_grants}).
 */
@Component
public class MembershipAccessSummaryStore implements MembershipAccessSummary {
  private final DynamoDbClient client;
  private final DynamoTableNames tables;
  private final ObjectMapper json;
  private final AccountGrants grants;

  public MembershipAccessSummaryStore(
      DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, AccountGrants grants) {
    this.client = Objects.requireNonNull(client);
    this.tables = Objects.requireNonNull(tables);
    this.json = Objects.requireNonNull(json);
    this.grants = Objects.requireNonNull(grants);
  }

  @Override
  public List<JsonNode> activeSnapshots(UUID account, Instant now) {
    return MembershipItems.purchases(client, tables, json, account).stream()
        .filter(p -> MembershipRules.grantsAt(p, now))
        .sorted(
            Comparator.comparing((ObjectNode p) -> p.path("planId").asText())
                .thenComparing(p -> p.path("id").asText()))
        .map(p -> (JsonNode) p.path("plan"))
        .toList();
  }

  @Override
  public Set<String> grantedTopics(UUID account, Instant now) {
    return new TreeSet<>(grants.grantedTopics(account, now));
  }
}
