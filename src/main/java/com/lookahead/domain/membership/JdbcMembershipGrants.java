package com.lookahead.domain.membership;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.learning.content.validator.SnapshotValidator;

/** Purchases snapshot catalog scope, but resolve courses dynamically on every access check. */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcMembershipGrants implements MembershipGrants {
    private final JdbcTemplate jdbc;
    private final SnapshotValidator catalog;
    public JdbcMembershipGrants(JdbcTemplate jdbc,SnapshotValidator catalog){this.jdbc=jdbc;this.catalog=catalog;}
    public Set<String> topics(UUID account) {
        var scopes=jdbc.queryForList("SELECT DISTINCT jsonb_array_elements_text(plan_snapshot->'scope'->'catalogs') FROM membership_purchases WHERE account_id=? AND status IN ('succeeded','scheduled') AND starts_at<=now() AND ends_at>now() AND superseded_at IS NULL",String.class,account);
        var result=new HashSet<String>();
        for(String topic:catalog.allTopicIds())if(scopes.contains(topic.split(":",2)[0]))result.add(topic);
        return Set.copyOf(result);
    }
}
