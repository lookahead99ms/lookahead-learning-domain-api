package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-970: purchases from the account's membership items; grants through lane A's {@link AccountGrantsLookup}, which
 * {@code access.DynamoAccountRecords} implements in dynamodb mode (the SQL {@code account_grants} valid now plus
 * {@code staff_access.topic_grants}). The lookup is optional only for narrow test wiring: without one the summary shows
 * memberships and no grants.
 */
@Component
public class DynamoAccessSummaryStore implements AccessSummaryStore {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper json;
    private final AccountGrantsLookup grants;
    @org.springframework.beans.factory.annotation.Autowired
    public DynamoAccessSummaryStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, ObjectProvider<AccountGrantsLookup> grants) {
        this(client, tables, json, grants.getIfAvailable());
    }
    public DynamoAccessSummaryStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, AccountGrantsLookup grants) {
        this.client = Objects.requireNonNull(client); this.tables = Objects.requireNonNull(tables); this.json = Objects.requireNonNull(json); this.grants = grants;
    }
    @Override public List<JsonNode> activeSnapshots(UUID account, Instant now) {
        return MembershipItems.purchases(client, tables, json, account).stream().filter(p -> MembershipItems.grantsAt(p, now))
                .sorted(Comparator.comparing((ObjectNode p) -> p.path("planId").asText()).thenComparing(p -> p.path("id").asText()))
                .map(p -> (JsonNode) p.path("plan")).toList();
    }
    @Override public Set<String> grantedTopics(UUID account, Instant now) {
        return grants == null ? Set.of() : new TreeSet<>(grants.grantedTopics(account, now));
    }
}
