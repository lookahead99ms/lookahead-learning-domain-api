package com.lookahead.domain.membership;

import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;

/** DLV-970: the catalogs of the account's purchases granting access now, from a strong read of its purchase items. */
@Component
public class DynamoMembershipGrants implements MembershipGrants {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper json;
    private final SnapshotValidator catalog;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public DynamoMembershipGrants(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, SnapshotValidator catalog) { this(client, tables, json, catalog, Clock.systemUTC()); }
    DynamoMembershipGrants(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, SnapshotValidator catalog, Clock clock) {
        this.client = Objects.requireNonNull(client); this.tables = Objects.requireNonNull(tables); this.json = Objects.requireNonNull(json);
        this.catalog = Objects.requireNonNull(catalog); this.clock = Objects.requireNonNull(clock);
    }
    @Override public Set<String> topics(UUID account) {
        var now = clock.instant();
        var scopes = new HashSet<String>();
        for (var p : MembershipItems.purchases(client, tables, json, account))
            if (MembershipItems.grantsAt(p, now)) p.path("plan").path("scope").path("catalogs").forEach(c -> scopes.add(c.asText()));
        var result = new HashSet<String>();
        for (String topic : catalog.allTopicIds()) if (scopes.contains(topic.split(":", 2)[0])) result.add(topic);
        return Set.copyOf(result);
    }
}
