package com.lookahead.domain;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import com.lookahead.domain.persistence.PersistenceStore;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DLV-970: the PostgreSQL statements of {@link DomainFixtureSeeder}, verbatim. */
@Component
@Profile("accounts & local-test")
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcFixtureSeedStore implements FixtureSeedStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcFixtureSeedStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transactions = manager == null ? null : new TransactionTemplate(manager); }
    @Override public void transaction(Runnable work) { if (transactions == null) work.run(); else transactions.executeWithoutResult(status -> work.run()); }
    @Override public boolean insertSubject(UUID id) {
        return jdbc.update("INSERT INTO " + LegacyStorageNames.SUBJECTS_TABLE + "(id) VALUES (?) ON CONFLICT (id) DO NOTHING", id) != 0;
    }
    @Override public void grant(UUID id, String topic) {
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?) ON CONFLICT DO NOTHING", id, topic);
    }
}
