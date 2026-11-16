package com.lookahead.domain.access;

import com.lookahead.domain.access.AccountAdmissionService.Row;
import com.lookahead.domain.access.AccountAdmissionService.Waiting;
import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.cloud.CloudIdentities;
import com.lookahead.domain.persistence.PersistenceStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** DLV-970: the PostgreSQL statements of {@link AccountAdmissionService}, verbatim and in the same order. */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAdmissionStore implements AdmissionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    /** Without a manager (unit tests on a mocked JdbcTemplate) the work runs directly, as the unproxied service did. */
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcAdmissionStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.mapper=mapper;this.transactions=manager==null?null:new TransactionTemplate(manager);
    }
    @Override public <T> T transaction(Supplier<T> work) { return transactions==null?work.get():transactions.execute(status->work.get()); }
    @Override public List<Waiting> waiting() {
        // DLV-969 S36: a waiting row whose Gmail two or more accounts share was not linked; the owner sees how many.
        var rows=jdbc.query("SELECT w.account_id,w.username,w.display_name,w.verified_email,w.requested_at,"+CloudIdentities.sameEmailAccounts("w.verified_email","w.account_id")
            // DLV-971: a waiting row the owner disabled (Reject) stays out of the list until it is enabled again.
            +" AS same_email_accounts FROM cloud_accounts w WHERE NOT admitted AND NOT EXISTS (SELECT 1 FROM cloud_account_states s WHERE s.account_id=w.account_id) ORDER BY requested_at,account_id",(r,i)->{
            Instant requested=r.getTimestamp("requested_at").toInstant();
            return new Waiting(r.getObject("account_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getString("verified_email"),requested,requested.plus(CloudAdmissionCleanup.WAITING_EXPIRY),
                r.getInt("same_email_accounts"));
        });
        rows=rows.stream().map(w->new Waiting(w.accountId(),w.username(),w.displayName(),w.email(),w.requestedAt(),w.expiresAt(),w.sameEmailAccounts(),
            CloudIdentities.signInMethods(jdbc,w.accountId()))).toList();
        return rows;
    }
    @Override public Row lock(UUID accountId) {
        var rows=jdbc.query("SELECT account_id,username,display_name,issuer,subject,admitted FROM cloud_accounts WHERE account_id=? FOR UPDATE",
            (r,i)->new Row(r.getObject("account_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getString("issuer"),r.getString("subject"),r.getBoolean("admitted"),null),accountId);
        if(rows.isEmpty())return null;
        Row row=rows.getFirst();
        if(row.admitted())return row;
        // DLV-969 S36: never admit an account that was disabled or deleted (read under the row lock, on its own).
        var state=jdbc.queryForList("SELECT state FROM cloud_account_states WHERE account_id=?",String.class,accountId);
        return state!=null&&!state.isEmpty()?new Row(row.accountId(),row.username(),row.displayName(),row.issuer(),row.subject(),false,state.getFirst()):row;
    }
    @Override public Instant admit(UUID actor, Row row, List<String> roles, Runnable assignRoles) {
        UUID accountId=row.accountId();
        Instant admittedAt=jdbc.queryForObject("UPDATE cloud_accounts SET admitted=true,admitted_at=clock_timestamp(),admitted_by=? WHERE account_id=? RETURNING admitted_at",Timestamp.class,actor,accountId).toInstant();
        assignRoles.run();
        jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,?,'admit',?::jsonb)",
            UUID.randomUUID(),accountId,row.issuer(),row.subject(),actor,mapper.writeValueAsString(roles));
        return admittedAt;
    }
}
