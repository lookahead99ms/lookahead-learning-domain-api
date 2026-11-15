package com.lookahead.domain.access;

import com.lookahead.domain.persistence.PersistenceStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DLV-970: the PostgreSQL statements of {@link AdminAccountDirectory}, verbatim. */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAdminDirectoryStore implements AdminDirectoryStore {
    private final JdbcTemplate jdbc;
    public JdbcAdminDirectoryStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public boolean author(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND author_access)",Boolean.class,account));
    }
    @Override public boolean testAccount(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM test_account_registry WHERE account_id=?)",Boolean.class,account));
    }
    @Override public boolean openPurchases(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM membership_purchases WHERE account_id=? AND superseded_at IS NULL)",Boolean.class,account));
    }
    @Override public boolean grants(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_grants WHERE account_id=?)",Boolean.class,account));
    }
    @Override public int sameEmailAccounts(UUID account, String email) { return com.lookahead.domain.cloud.CloudIdentities.sameEmailAccounts(jdbc,account,email); }
    @Override public List<Map<String,Object>> signInMethods(UUID account) { return com.lookahead.domain.cloud.CloudIdentities.signInMethods(jdbc,account); }
    @Override public String github(UUID account) {
        var links=jdbc.queryForList("SELECT url FROM cloud_account_profile_links WHERE account_id=? AND kind='github'",String.class,account);
        return links==null||links.isEmpty()?null:links.get(0);
    }
}
