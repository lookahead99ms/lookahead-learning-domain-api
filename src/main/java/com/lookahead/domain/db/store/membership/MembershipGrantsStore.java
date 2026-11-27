package com.lookahead.domain.db.store.membership;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.util.MembershipRules;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;

/** DLV-970: the catalogs of the account's purchases granting access now, from a strong read of its purchase items. */
@Component
public class MembershipGrantsStore implements MembershipGrants {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper json;
    private final Clock clock;
    @Autowired
    public MembershipGrantsStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json) { this(client, tables, json, Clock.systemUTC()); }
    MembershipGrantsStore(DynamoDbClient client, DynamoTableNames tables, ObjectMapper json, Clock clock) {
        this.client = Objects.requireNonNull(client); this.tables = Objects.requireNonNull(tables); this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public Set<String> catalogScopes(UUID account) {
        var now = clock.instant();
        var scopes = new HashSet<String>();
        for (var p : MembershipItems.purchases(client, tables, json, account))
            if (MembershipRules.grantsAt(p, now)) p.path("plan").path("scope").path("catalogs").forEach(c -> scopes.add(c.asText()));
        return Set.copyOf(scopes);
    }
}
