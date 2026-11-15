package com.lookahead.learning.content.repository;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import com.lookahead.domain.persistence.PersistenceStore;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DLV-970: the PostgreSQL statements of {@link AccountRepository}, verbatim. */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAccountStore implements AccountStore {
    private final JdbcTemplate jdbc;
    public JdbcAccountStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void ensureSubject(UUID subject) {
        jdbc.update("INSERT INTO " + LegacyStorageNames.SUBJECTS_TABLE + "(id) VALUES (?) ON CONFLICT (id) DO NOTHING", subject);
    }
    @Override public List<String> grantTopics(UUID subject) {
        return jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id = ? AND (valid_until IS NULL OR valid_until > now())", String.class, subject);
    }
    // The identity tables exist in every deployed schema; narrow fixtures (author-review and staff tests) create only the tables they use,
    // and a failed statement would also poison an open transaction, so the table is checked first rather than the error caught.
    @Override public boolean authorEnabled(UUID subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('cloud_accounts') IS NOT NULL",Boolean.class))
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND author_access AND enabled)",Boolean.class,subject));
    }
}
