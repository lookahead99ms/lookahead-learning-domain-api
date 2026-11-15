package com.lookahead.domain.access;

import com.lookahead.domain.access.AccountDirectory.Account;
import com.lookahead.domain.access.AccountDirectory.Page;
import com.lookahead.domain.persistence.PersistenceStore;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DLV-970: the PostgreSQL statements of {@link AccountDirectory}, verbatim. */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAccountDirectoryStore implements AccountDirectoryStore {
    private final JdbcTemplate jdbc;
    public JdbcAccountDirectoryStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Page list(String term,int page) {
            // DLV-969: disabled accounts stay listed (the owner can enable them); deleted ones are gone. The owner sees
            // the provider-verified email (a Google account's Gmail).
            var rows=jdbc.query("""
                SELECT account_id,username,display_name,verified_email FROM cloud_accounts a WHERE admitted AND enabled
                AND NOT EXISTS(SELECT 1 FROM cloud_account_states s WHERE s.account_id=a.account_id AND s.state='removed')
                AND (?='' OR position(? in lower(username))>0 OR position(? in lower(display_name))>0
                  OR position(? in lower(coalesce(verified_email,'')))>0)
                ORDER BY lower(username),account_id LIMIT 51 OFFSET ?
                """,(r,i)->new Account(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4)),term,term,term,term,page*50);
            return new Page(java.util.List.copyOf(rows.subList(0,Math.min(50,rows.size()))),page,rows.size()>50);
    }
    @Override public Account lookup(String query) {
            // Cloud identities already admitted by the provider mapping; no email-based linking. DLV-969 S36: any Cognito
            // username of the account (V19 cloud_identities), or its own username for a row without identity rows.
            var rows=jdbc.query("""
                SELECT account_id,username,display_name FROM cloud_accounts a WHERE admitted AND enabled
                AND (lower(username)=? OR EXISTS(SELECT 1 FROM cloud_identities i WHERE i.account_id=a.account_id AND lower(i.username)=?))
                AND NOT EXISTS(SELECT 1 FROM cloud_account_states s WHERE s.account_id=a.account_id AND s.state='removed') LIMIT 2
                """,(r,i)->new Account(r.getObject(1,UUID.class),r.getString(2),r.getString(3),null),query,query);
            if(rows.size()!=1)throw AccountDirectory.missing();return rows.getFirst();
    }
    @Override public Account byId(UUID id) {
            var rows=jdbc.query("SELECT account_id,username,display_name FROM cloud_accounts WHERE account_id=? AND admitted AND enabled",
                (r,i)->new Account(r.getObject(1,UUID.class),r.getString(2),r.getString(3),null),id);
            if (rows.isEmpty()) throw AccountDirectory.missing();
            return rows.getFirst();
    }
    @Override public void ensureSubject(UUID id) {
                    jdbc.update("INSERT INTO platform_subjects(id) VALUES(?) ON CONFLICT (id) DO NOTHING",id);
    }
}
