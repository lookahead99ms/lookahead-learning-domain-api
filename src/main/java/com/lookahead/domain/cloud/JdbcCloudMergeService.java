package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.AccountRemovalService;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DLV-970: the merge on PostgreSQL, the SQL as DLV-969 S36.6 wrote it. Each call is one transaction that first takes
 * the email's advisory lock (the one key S36 linking takes too), so requests, merges and first contacts of one email
 * serialise; the accounts are read again under their row locks because a Disable or Delete committed while the
 * locking statement waited would otherwise go unseen.
 */
public class JdbcCloudMergeService extends CloudMergeService {
    private record Link(String email,Instant expires) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcCloudMergeService(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountRemovalService removal,EmailSender email,String publicUrl){
        super(removal,email,publicUrl);
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);
    }

    @Override protected Requested requested(AccountPrincipal actor,String to){
        return transaction.execute(status->{
            lock(to);
            var pool=pool(to);
            if(pool.size()<2||!pool.contains(actor.accountId()))return new Requested(null,"NOTHING_TO_MERGE",pool.size());
            jdbc.update("DELETE FROM cloud_merge_tokens WHERE lower(email)=lower(?) AND created_at<clock_timestamp()-interval '1 day'",to);
            if(jdbc.queryForObject("SELECT count(*) FROM cloud_merge_tokens WHERE lower(email)=lower(?) AND created_at>clock_timestamp()-interval '1 hour'",Integer.class,to)>=REQUESTS_PER_HOUR)
                return new Requested(null,"MERGE_REQUEST_RATE_LIMITED",pool.size());
            var open=jdbc.query("SELECT token_digest,expires_at FROM cloud_merge_tokens WHERE lower(email)=lower(?) AND used_at IS NULL AND expires_at>clock_timestamp() FOR UPDATE",
                (r,i)->new Object[]{r.getString(1),r.getTimestamp(2)},to);
            for(var row:open)jdbc.update("UPDATE cloud_merge_tokens SET used_at=clock_timestamp() WHERE token_digest=?",row[0]);
            String token=randomToken();
            if(open.isEmpty())jdbc.update("INSERT INTO cloud_merge_tokens(token_digest,email,requested_by,expires_at) VALUES(?,?,?,clock_timestamp()+make_interval(mins => ?))",
                CloudSignInRegistry.digest(token),to,actor.accountId(),(int)LINK_LIFETIME.toMinutes());
            else jdbc.update("INSERT INTO cloud_merge_tokens(token_digest,email,requested_by,expires_at) VALUES(?,?,?,?)",CloudSignInRegistry.digest(token),to,actor.accountId(),open.getFirst()[1]);
            return new Requested(linkFor(token),null,pool.size());
        });
    }

    @Override protected Candidates candidatesChecked(AccountPrincipal actor,String token){
        return transaction.execute(status->{
            Link link=link(token,false);
            var pool=pool(link.email);
            if(!pool.contains(actor.accountId()))throw invalidToken();
            var members=members(pool,false);
            requireMergeable(members);
            return new Candidates(link.email,link.expires,sorted(members.stream().map(this::candidate).toList()));
        });
    }

    @Override protected Kept mergeChecked(AccountPrincipal actor,String token,UUID keep){
        return transaction.execute(status->{
            // The email lock first, then the link row: a request for the same email takes them in this order too.
            lock(link(token,false).email);
            Link link=link(token,true);
            var pool=pool(link.email);
            if(!pool.contains(actor.accountId()))throw invalidToken();
            var members=members(pool,true);
            // A first contact or a link that reached this email while the rows were being locked.
            if(!pool.equals(pool(link.email)))throw conflict();
            if(keep==null||!pool.contains(keep)){
                if(keep!=null&&Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM cloud_identities WHERE account_id=? AND lower(verified_email)=lower(?))
                        OR EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND lower(verified_email)=lower(?))
                    """,Boolean.class,keep,link.email,keep,link.email)))throw conflict();
                throw keepNotCandidate();
            }
            requireMergeable(members);
            requireKeepable(members,keep);
            Member kept=members.stream().filter(m->m.id().equals(keep)).findFirst().orElseThrow();
            CloudIdentities.insert(jdbc,keep,kept.issuer(),kept.subject(),kept.username(),kept.email(),"backfill");
            int removed=0;
            for(Member drop:members){
                if(drop.id().equals(keep))continue;
                // First the identities move, so a waiting row's delete below (ON DELETE CASCADE) cannot take them.
                CloudIdentities.insert(jdbc,keep,drop.issuer(),drop.subject(),drop.username(),drop.email(),"merge");
                int moved=jdbc.update("UPDATE cloud_identities SET account_id=?,link_source='merge' WHERE account_id=?",keep,drop.id());
                // The event has no foreign key (V17), so it stays after a waiting row is deleted, as 'expire' does.
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES (?,?,?,?,?,'merge',?::jsonb)",
                    UUID.randomUUID(),drop.id(),drop.issuer(),drop.subject(),actor.accountId(),"{\"into\":\""+keep+"\"}");
                removed++;
                if(!drop.admitted()){
                    // Never a V18 state row for a waiting account: its FK has no delete rule, and the cleanup of a
                    // release before this one deletes every expired waiting row without looking at states.
                    CloudAdmissionCleanup.deleteWaiting(jdbc,drop.id());
                    Events.afterCommit(()->removedLine(actor.accountId(),drop.id(),keep,false,moved));
                    continue;
                }
                removal.revokeSignIns(drop.id());
                removal.erase(actor.accountId(),drop.id());
                jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)",drop.id(),actor.accountId());
                Events.afterCommit(()->removedLine(actor.accountId(),drop.id(),keep,true,moved));
            }
            removal.revokeSignIns(keep);
            jdbc.update("UPDATE cloud_merge_tokens SET used_at=clock_timestamp() WHERE token_digest=?",CloudSignInRegistry.digest(token));
            int count=removed;
            Events.afterCommit(()->mergedLine(actor.accountId(),keep,count));
            return new Kept(keep);
        });
    }

    /** Accounts of {@code email} that can take part: not on the test issuer, not managed test accounts, not deleted. */
    private List<UUID> pool(String email){
        return jdbc.queryForList(CloudIdentities.sameEmail("?")
            +" AND NOT EXISTS(SELECT 1 FROM cloud_account_states s WHERE s.account_id=m.account_id AND s.state='removed')"
            +" AND NOT EXISTS(SELECT 1 FROM test_account_registry t WHERE t.account_id=m.account_id) ORDER BY m.account_id",UUID.class,email,email);
    }
    /**
     * In the pool's (database) account-id order, so two merges never wait on each other's rows. Under the locks, state
     * and enabled are read again in a statement of their own: the locking statement's join keeps the snapshot it
     * started with, so a Disable or Delete committed while it waited would otherwise go unseen.
     */
    private List<Member> members(List<UUID> pool,boolean lock){
        var members=new ArrayList<Member>();
        for(UUID id:pool)members.addAll(jdbc.query("""
            SELECT a.account_id,a.issuer,a.subject,a.username,a.verified_email,a.admitted,a.enabled,a.author_access,s.state
            FROM cloud_accounts a LEFT JOIN cloud_account_states s USING(account_id) WHERE a.account_id=?"""+(lock?" FOR UPDATE OF a":""),
            (r,i)->new Member(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBoolean(6),r.getBoolean(7),r.getBoolean(8),r.getString(9)),id));
        if(!lock)return members;
        var current=new ArrayList<Member>();
        for(Member m:members)current.addAll(jdbc.query("SELECT a.enabled,a.admitted,(SELECT s.state FROM cloud_account_states s WHERE s.account_id=a.account_id) FROM cloud_accounts a WHERE a.account_id=?",
            (r,i)->new Member(m.id(),m.issuer(),m.subject(),m.username(),m.email(),r.getBoolean(2),r.getBoolean(1),m.author(),r.getString(3)),m.id()));
        return current;
    }
    private Candidate candidate(Member member){
        var row=jdbc.queryForMap("SELECT display_name,admitted,requested_at,(SELECT max(created_at) FROM cloud_sign_ins s WHERE s.account_id=a.account_id) last FROM cloud_accounts a WHERE account_id=?",member.id());
        var methods=jdbc.query("SELECT provider,verified_email FROM cloud_identities WHERE account_id=? ORDER BY linked_at,id",(r,i)->method(r.getString(1),r.getString(2)),member.id());
        if(methods.isEmpty())methods=List.of(method(CloudIdentities.provider(member.issuer(),member.username()),member.email()));
        var plans=jdbc.query("""
            SELECT p.id,p.name,p.progress::text,v.snapshot::text FROM plans p
            LEFT JOIN plan_versions v ON v.account_id=p.account_id AND v.plan_id=p.id AND v.id=p.current_version
            WHERE p.account_id=? ORDER BY p.created_at,p.id""",(r,i)->new Plan(r.getObject(1,UUID.class),r.getString(2),percent(r.getString(4),r.getString(3))),member.id());
        // Active or scheduled, not ended by a Delete or merge, of a priced plan or bought at a price.
        boolean paid=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM membership_purchases p JOIN membership_plans l ON l.id=p.plan_id WHERE p.account_id=?
              AND p.status IN ('succeeded','scheduled') AND p.ends_at>now() AND p.superseded_at IS NULL AND (p.price_paid_cents>0 OR l.price_cents>0))""",Boolean.class,member.id()));
        return new Candidate(member.id(),(String)row.get("display_name"),List.copyOf(methods),(Boolean)row.get("admitted"),
            instant(row.get("requested_at")),instant(row.get("last")),List.copyOf(plans),paid);
    }
    private static Instant instant(Object value){return value instanceof java.sql.Timestamp t?t.toInstant():value instanceof java.time.OffsetDateTime o?o.toInstant():null;}
    private Link link(String token,boolean lock){
        var rows=jdbc.query("SELECT email,expires_at FROM cloud_merge_tokens WHERE token_digest=? AND used_at IS NULL AND expires_at>clock_timestamp()"+(lock?" FOR UPDATE":""),
            (r,i)->new Link(r.getString(1),r.getTimestamp(2).toInstant()),digest(token));
        if(rows.isEmpty())throw invalidToken();
        return rows.getFirst();
    }
    /** Requests, merges and first contacts of one email serialize here: the one key S36 linking takes too. */
    private void lock(String email){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"email:"+CloudIdentities.normalizeEmail(email));}
}
