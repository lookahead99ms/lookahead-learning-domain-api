package com.lookahead.domain.testaccounts;

import com.lookahead.domain.persistence.PersistenceStore;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The PostgreSQL test-account statements, exactly as the services ran them before DLV-970. */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcTestAccountStore implements TestAccountStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();
    public JdbcTestAccountStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); }

    private RegistryRow row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new RegistryRow(r.getObject("account_id",UUID.class),r.getString("username"),r.getString("initialization"),r.getString("creation_state"),
                r.getObject("creator_id",UUID.class),r.getString("last_changed_by"),r.getTimestamp("last_changed_at").toInstant(),r.getLong("revision"));
    }
    @Override public List<RegistryRow> activeRegistry(boolean cloud) {
        String rows=cloud?"SELECT * FROM test_account_registry WHERE creation_state='active' AND (initialization='created' OR creator_id IS NOT NULL) ORDER BY username"
            :"SELECT * FROM test_account_registry WHERE creation_state='active' ORDER BY username";
        return jdbc.query(rows,(r,n)->row(r));
    }
    @Override public Set<UUID> registeredIds() { return new HashSet<>(jdbc.queryForList("SELECT account_id FROM test_account_registry", UUID.class)); }
    @Override public Set<UUID> deletedRosterIds() {
        return new HashSet<>(jdbc.queryForList(
                "SELECT account_id FROM test_account_registry WHERE creation_state='deleted' AND creator_id IS NOT NULL AND initialization<>'created'", UUID.class));
    }
    @Override public List<RosterStatus> rosterStatus() {
        var rows = new ArrayList<RosterStatus>();
        for (var row : jdbc.queryForList(DevTestRoster.STATUS))
            rows.add(new RosterStatus((UUID) row.get("account_id"), (String) row.get("creation_state"), Boolean.TRUE.equals(row.get("has_plan")), Boolean.TRUE.equals(row.get("changed"))));
        return rows;
    }
    @Override public List<String> staffRoles(UUID id) {
        var roles=new ArrayList<String>();
        for(String row:jdbc.queryForList("SELECT roles::text FROM staff_access WHERE account_id=?",String.class,id))json.readTree(row).forEach(role->roles.add(role.asText()));
        return roles;
    }
    @Override public String displayName(UUID account) {
        return jdbc.queryForList("SELECT display_name FROM cloud_accounts WHERE account_id=?",String.class,account).stream().findFirst().orElse(null);
    }
    @Override public int lifetimeSpend(UUID creator) {
        return jdbc.queryForObject("SELECT count(*) FROM test_account_creations WHERE creator_id=? AND state IN ('active','deleted')",Integer.class,creator);
    }
    @Override public UUID accountOf(String issuer, String subject) {
        return jdbc.queryForList(com.lookahead.domain.cloud.CloudIdentities.ACCOUNT_OF_IDENTITY, UUID.class, issuer, subject, issuer, subject)
                .stream().findFirst().orElse(null);
    }
    @Override public Holder holder(String issuer, String subject) {
        var holders = jdbc.query("""
                SELECT c.account_id,c.author_access FROM cloud_accounts c WHERE c.account_id IN (%s) AND c.admitted AND c.enabled
                AND NOT EXISTS (SELECT 1 FROM cloud_account_states s WHERE s.account_id=c.account_id)
                """.formatted(com.lookahead.domain.cloud.CloudIdentities.ACCOUNT_OF_IDENTITY),
                (row, n) -> new Object[]{row.getObject(1, UUID.class), row.getBoolean(2)}, issuer, subject, issuer, subject);
        if (holders.isEmpty()) return null;
        return new Holder((UUID) holders.getFirst()[0], (Boolean) holders.getFirst()[1]);
    }
    @Override public List<Target> targets(UUID only) {
        String sql = """
                SELECT r.account_id,r.username,c.display_name FROM test_account_registry r
                JOIN cloud_accounts c ON c.account_id=r.account_id AND c.issuer=? AND c.admitted AND c.enabled
                WHERE r.creation_state='active' AND (r.initialization='created' OR r.creator_id IS NOT NULL)
                """;
        var mapper = (org.springframework.jdbc.core.RowMapper<Target>) (row, n) ->
                new Target(row.getObject(1, UUID.class), row.getString(2), row.getString(3));
        return only == null ? jdbc.query(sql + " ORDER BY r.username", mapper, TestAccountRoster.TEST_ISSUER)
                : jdbc.query(sql + " AND r.account_id=?", mapper, TestAccountRoster.TEST_ISSUER, only);
    }
    @Override public <T> T transact(Function<Work, T> body) { return transactions.execute(status -> body.apply(new JdbcWork())); }

    private final class JdbcWork implements Work {
        @Override public void actor(String actor){jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor);}
        @Override public void seedLock(){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('test-account-seed',0))",rs->{});}
        @Override public void lockSubject(UUID id){jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",rs->{},id);}
        @Override public boolean createSubject(UUID id){return jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",id)==1;}
        @Override public RegistryRow registry(UUID id){return jdbc.query("SELECT * FROM test_account_registry WHERE account_id=?",(r,n)->row(r),id).stream().findFirst().orElse(null);}
        @Override public boolean registerSeed(UUID id,String username,String initialization){
            return jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES (?,?,?,'seed') ON CONFLICT DO NOTHING",id,username,initialization)==1;
        }
        @Override public void registerRoster(UUID id,String username,UUID creator){
            jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,creator_id,last_changed_by) VALUES (?,?,'scenario',?,?)",id,username,creator,creator.toString());
        }
        @Override public void registerCreated(UUID id,String username,UUID creator){
            jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,creator_id,last_changed_by) VALUES (?,?,'created',?,?)",id,username,creator,creator.toString());
        }
        @Override public boolean rosterRow(UUID id){return !jdbc.queryForList("SELECT 1 FROM test_account_registry WHERE account_id=? AND creator_id IS NOT NULL AND initialization<>'created'",Integer.class,id).isEmpty();}
        @Override public boolean deletedRosterRow(UUID id){
            return !jdbc.queryForList("SELECT 1 FROM test_account_registry WHERE account_id=? AND creation_state='deleted' AND creator_id IS NOT NULL AND initialization<>'created' FOR UPDATE",Integer.class,id).isEmpty();
        }
        @Override public String resetReceipt(UUID id,UUID key){
            return jdbc.queryForList("SELECT actor_id::text || ':' || preset FROM test_account_resets WHERE account_id=? AND mutation_key=?",String.class,id,key).stream().findFirst().orElse(null);
        }
        @Override public Long activeRevision(UUID id){
            return jdbc.queryForList("SELECT revision FROM test_account_registry WHERE account_id=? AND creation_state='active' FOR UPDATE",Long.class,id).stream().findFirst().orElse(null);
        }
        @Override public void resetRegistry(UUID id,String preset,UUID actor){
            jdbc.update("UPDATE test_account_registry SET initialization=?,revision=revision+1,last_changed_by=?,last_changed_at=clock_timestamp() WHERE account_id=?",preset,actor.toString(),id);
        }
        @Override public void restoreRegistry(UUID id,UUID actor){
            jdbc.update("UPDATE test_account_registry SET creation_state='active',initialization='scenario',revision=revision+1,last_changed_by=?,last_changed_at=clock_timestamp() WHERE account_id=?",actor.toString(),id);
        }
        @Override public void deleteRegistry(UUID id,UUID actor){
            jdbc.update("UPDATE test_account_registry SET creation_state='deleted',revision=revision+1,last_changed_by=?,last_changed_at=clock_timestamp() WHERE account_id=?",actor.toString(),id);
        }
        @Override public void insertReset(UUID id,UUID key,UUID actor,String preset){
            jdbc.update("INSERT INTO test_account_resets(account_id,mutation_key,actor_id,preset) VALUES (?,?,?,?)",id,key,actor,preset);
        }
        @Override public void supersedePurchases(UUID id){jdbc.update("UPDATE membership_purchases SET superseded_at=COALESCE(superseded_at,now()),updated_at=now() WHERE account_id=?",id);}
        @Override public void tombstoneReceipts(UUID id){jdbc.update("UPDATE mutation_receipts SET deleted=true,response=NULL WHERE account_id=?",id);}
        @Override public void deletePlans(UUID id){jdbc.update("DELETE FROM plans WHERE account_id=?",id);}
        @Override public void deleteGrants(UUID id){jdbc.update("DELETE FROM account_grants WHERE account_id=?",id);}
        @Override public void clearStaffAccess(UUID id){jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=?",id);}
        @Override public void grantTopics(UUID id,Collection<String> topics){for(String topic:topics)jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)",id,topic);}
        @Override public boolean hasAccountRecord(UUID id){return !jdbc.queryForList("SELECT 1 FROM cloud_accounts WHERE account_id=?",Integer.class,id).isEmpty();}
        @Override public void admitTestAccount(UUID id,String username,String displayName,UUID admittedBy){
            jdbc.update("""
                    INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,enabled,author_access,username,display_name,requested_at,admitted_at,admitted_by)
                    VALUES (?,?,?,true,true,false,?,?,clock_timestamp(),clock_timestamp(),?)
                    """,id,TestAccountRoster.TEST_ISSUER,id.toString(),username,displayName,admittedBy);
            // DLV-969 S36: its one identity, on the test issuer; a test account is never a link candidate.
            com.lookahead.domain.cloud.CloudIdentities.insert(jdbc,id,TestAccountRoster.TEST_ISSUER,id.toString(),username,null,"first_contact");
            jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES (?,?,?,?,?,'admit','[]'::jsonb)",
                    UUID.randomUUID(),id,TestAccountRoster.TEST_ISSUER,id.toString(),admittedBy);
        }
        @Override public Reservation reservation(UUID creator,UUID key){
            return jdbc.query("SELECT account_id,username,state FROM test_account_creations WHERE creator_id=? AND mutation_key=?",
                    (r,i)->new Reservation(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),creator,key).stream().findFirst().orElse(null);
        }
        @Override public int holdsAndSpend(UUID creator){
            return jdbc.queryForObject("""
                    SELECT count(*) FROM test_account_creations WHERE creator_id=?
                    AND (state IN ('active','deleted') OR (state='pending' AND created_at>clock_timestamp()-interval '15 minutes'))
                    """,Integer.class,creator);
        }
        @Override public int lifetimeSpend(UUID creator){
            return jdbc.queryForObject("SELECT count(*) FROM test_account_creations WHERE creator_id=? AND state IN ('active','deleted')",Integer.class,creator);
        }
        @Override public void reserve(UUID creator,UUID key,UUID account,String username){
            jdbc.update("INSERT INTO test_account_creations(creator_id,mutation_key,account_id,username,state) VALUES (?,?,?,?,'pending')",creator,key,account,username);
        }
        @Override public String creationState(UUID creator,UUID account){
            return jdbc.queryForList("SELECT state FROM test_account_creations WHERE creator_id=? AND account_id=? FOR UPDATE",String.class,creator,account).stream().findFirst().orElse(null);
        }
        @Override public void activateCreation(UUID creator,UUID account){jdbc.update("UPDATE test_account_creations SET state='active' WHERE creator_id=? AND account_id=?",creator,account);}
        @Override public void deleteCreation(UUID account){jdbc.update("UPDATE test_account_creations SET state='deleted' WHERE account_id=?",account);}
        @Override public void restoreCreations(UUID account){jdbc.update("UPDATE test_account_creations SET state='active' WHERE account_id=? AND state='deleted'",account);}
        @Override public RegistryRow registryLocked(UUID id){
            return jdbc.query("SELECT * FROM test_account_registry WHERE account_id=? FOR UPDATE",(r,n)->row(r),id).stream().findFirst().orElse(null);
        }
        @Override public void erase(UUID id,boolean localFixturePurge){new JdbcAccountDataErasure(jdbc).erase(id,localFixturePurge);}
    }
}
