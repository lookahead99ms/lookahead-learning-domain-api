package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.AccountRemovalService;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.util.PlanCardMetadata;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-969 S36.6 (owner decisions 2026-11-04): accounts that already share a verified email become one. The person asks
 * from a signed-in session; one single-use link (30 minutes, stored as a SHA-256 digest only, V20 cloud_merge_tokens)
 * goes to that session's own verified email; signed in as one of the accounts they choose the one to keep. The others
 * are deleted (their sign-ins move to the kept account first): an admitted one exactly as the owner's Delete deletes,
 * a waiting one exactly as the 48-hour cleanup removes it. Test accounts, deleted accounts and other emails never take
 * part. The sign-in's mergeAvailable offer itself tells the caller that other accounts share their own verified email
 * (and only theirs); the request, which always answers 202, reveals nothing more than that.
 * <p>Log lines (DLV-969, {@link Events}): {@code merge.request} (sent, or why nothing was sent), {@code merge.candidates}
 * and {@code merge.merge} refusals with their code, {@code account.merge} once the merge commits, one
 * {@code account.merge-removed} per account that went; account IDs and counts only, never the email or the link.
 */
public class CloudMergeService {
    public static final Duration LINK_LIFETIME=Duration.ofMinutes(30);
    public static final int REQUESTS_PER_HOUR=3;
    public static final String SUBJECT="Confirm it's you to tidy up your Look Ahead account";
    public static final String WARNING="The other account's study plans and progress are deleted and this cannot be undone.";
    public record Plan(UUID id,String title,int progressPercent) {}
    /** paidMembership: an active or scheduled paid purchase, which the merge ends (history kept) for an account not kept. */
    public record Candidate(UUID accountId,String displayName,List<Map<String,Object>> signInMethods,boolean admitted,
        Instant createdAt,Instant lastSignInAt,List<Plan> studyPlans,boolean paidMembership) {}
    public record Candidates(String email,Instant expiresAt,List<Candidate> candidates) {}
    public record Kept(UUID keptAccountId) {}
    private record Link(String email,Instant expires) {}
    private record Member(UUID id,String issuer,String subject,String username,String email,boolean admitted,boolean enabled,boolean author,String state) {}
    /** The request's outcome inside its transaction: the link to send, or the reason code for sending nothing. */
    private record Requested(String link,String skipped,int accounts) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AccountRemovalService removal;
    private final EmailSender email;
    private final String publicUrl;

    public CloudMergeService(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountRemovalService removal,EmailSender email,String publicUrl){
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);this.removal=removal;this.email=email;
        this.publicUrl=publicUrl.endsWith("/")?publicUrl.substring(0,publicUrl.length()-1):publicUrl;
    }

    /**
     * Sends the link when the session's own verified email has two or more accounts that can take part, at most three
     * per address per hour. A request while a link is outstanding replaces it (the token is kept only as a digest, so
     * the same token cannot be sent twice) with the same expiry. Every outcome looks the same to the caller.
     */
    public void request(AccountPrincipal actor){
        String to=actor.verifiedEmail();
        if(to==null||to.isBlank()||to.length()>254){
            Events.refused(Events.EVENT,"merge.request","NO_VERIFIED_EMAIL").addKeyValue("account.id",actor.accountId())
                .log("Merge link not sent for account {}: this sign-in has no verified email to send it to (the caller still gets 202)",actor.accountId());
            return;
        }
        Requested requested=transaction.execute(status->{
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
            return new Requested(publicUrl+"/account/merge?token="+token,null,pool.size());
        });
        if(requested.link()==null){
            if("MERGE_REQUEST_RATE_LIMITED".equals(requested.skipped()))
                Events.limit("merge.request","MERGE_REQUEST_RATE_LIMITED","merge-links",REQUESTS_PER_HOUR,"1h").addKeyValue("account.id",actor.accountId())
                    .log("Merge link not sent for account {}: already {} links for this email in the last hour (the caller still gets 202)",actor.accountId(),REQUESTS_PER_HOUR);
            else Events.refused(Events.EVENT,"merge.request",requested.skipped()).addKeyValue("account.id",actor.accountId()).addKeyValue("count",requested.accounts())
                .log("Merge link not sent for account {}: {} account(s) carry this email, nothing to merge (the caller still gets 202)",actor.accountId(),requested.accounts());
            return;
        }
        // After the commit: an email outage costs one of the three sends, never the caller's answer.
        try{
            email.send(to,SUBJECT,body(requested.link()));
            Events.success(Events.EVENT,"merge.request").addKeyValue("account.id",actor.accountId()).addKeyValue("count",requested.accounts())
                .log("Merge link sent for account {}: {} accounts share its verified email; the link is single use and valid for {} minutes",actor.accountId(),requested.accounts(),LINK_LIFETIME.toMinutes());
        }
        catch(RuntimeException failure){
            Events.failed(Events.EVENT,"merge.request","EMAIL_NOT_SENT").addKeyValue("account.id",actor.accountId()).addKeyValue("error.type",Events.types(failure))
                .log("Merge link for account {} was created but the email was not sent ({}); it counts as one of the {} sends per hour and the caller still gets 202",
                    actor.accountId(),Events.types(failure),REQUESTS_PER_HOUR);
        }
    }
    static String body(String link){
        return "Hello,\n\nSomeone asked to sort out the Look Ahead accounts that use this email address. If it was you, open this link within "
            +LINK_LIFETIME.toMinutes()+" minutes:\n\n"+link+"\n\nYou will sign in and choose which account to keep. "+WARNING
            +"\n\nIf it wasn't you, ignore this email. Nothing changes.\n\nLook Ahead\n";
    }

    /** The accounts of the link's email with their sign-in methods and study plans; the caller must be one of them. */
    public Candidates candidates(AccountPrincipal actor,String token){
        try{
            return transaction.execute(status->{
                Link link=link(token,false);
                var pool=pool(link.email);
                if(!pool.contains(actor.accountId()))throw invalidToken();
                var members=members(pool,false);
                requireMergeable(members);
                var candidates=members.stream().map(this::candidate)
                    .sorted(Comparator.comparing(Candidate::createdAt,Comparator.nullsLast(Comparator.naturalOrder()))).toList();
                return new Candidates(link.email,link.expires,candidates);
            });
        }catch(AccountFailure refused){throw refusal("merge.candidates",actor,refused);}
    }

    /** One WARN line per refused merge step: the stable code and the caller, never the token or the email. */
    private static AccountFailure refusal(String action,AccountPrincipal actor,AccountFailure refused){
        Events.refused(Events.EVENT,action,refused.code()).addKeyValue("account.id",actor.accountId())
            .log("{} refused for account {}: {}",action.equals("merge.candidates")?"Merge link opened":"Merge",actor.accountId(),refused.code());
        return refused;
    }

    /**
     * One transaction: the accounts locked and checked again, every identity of the others moved to the kept account,
     * every sign-in of all of them ended, the others' data erased by the Delete path and each marked deleted with a
     * {@code merge} event, the link used. No sign-in pool call: the others' Cognito users now belong to the kept account.
     */
    public Kept merge(AccountPrincipal actor,String token,UUID keep){
        try{return mergeChecked(actor,token,keep);}
        catch(AccountFailure refused){throw refusal("merge.merge",actor,refused);}
    }
    private Kept mergeChecked(AccountPrincipal actor,String token,UUID keep){
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
            Member kept=members.stream().filter(m->m.id.equals(keep)).findFirst().orElseThrow();
            // The kept account must be able to sign in, and an author account is never the one deleted.
            if(!kept.admitted||members.stream().anyMatch(m->m.author&&!m.id.equals(keep)))throw keepNotCandidate();
            CloudIdentities.insert(jdbc,keep,kept.issuer,kept.subject,kept.username,kept.email,"backfill");
            int removed=0;
            for(Member drop:members){
                if(drop.id.equals(keep))continue;
                // First the identities move, so a waiting row's delete below (ON DELETE CASCADE) cannot take them.
                CloudIdentities.insert(jdbc,keep,drop.issuer,drop.subject,drop.username,drop.email,"merge");
                int moved=jdbc.update("UPDATE cloud_identities SET account_id=?,link_source='merge' WHERE account_id=?",keep,drop.id);
                // The event has no foreign key (V17), so it stays after a waiting row is deleted, as 'expire' does.
                jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES (?,?,?,?,?,'merge',?::jsonb)",
                    UUID.randomUUID(),drop.id,drop.issuer,drop.subject,actor.accountId(),"{\"into\":\""+keep+"\"}");
                removed++;
                if(!drop.admitted){
                    // Never a V18 state row for a waiting account: its FK has no delete rule, and the cleanup of a
                    // release before this one deletes every expired waiting row without looking at states.
                    CloudAdmissionCleanup.deleteWaiting(jdbc,drop.id);
                    Events.afterCommit(()->Events.success(Events.EVENT,"account.merge-removed").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",drop.id)
                        .addKeyValue("into",keep).addKeyValue("admitted",false).addKeyValue("count",moved)
                        .log("Waiting account {} merged into {}: {} sign-in(s) moved to the kept account, the waiting row removed as the 48 h cleanup removes one",drop.id,keep,moved));
                    continue;
                }
                removal.revokeSignIns(drop.id);
                removal.erase(actor.accountId(),drop.id);
                jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_by) VALUES (?,'removed',?)",drop.id,actor.accountId());
                Events.afterCommit(()->Events.success(Events.EVENT,"account.merge-removed").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",drop.id)
                    .addKeyValue("into",keep).addKeyValue("admitted",true).addKeyValue("count",moved)
                    .log("Account {} merged into {}: {} sign-in(s) moved to the kept account, its learning data erased and the account marked deleted; no sign-in pool call, the pool users now belong to the kept account",drop.id,keep,moved));
            }
            removal.revokeSignIns(keep);
            jdbc.update("UPDATE cloud_merge_tokens SET used_at=clock_timestamp() WHERE token_digest=?",CloudSignInRegistry.digest(token));
            int count=removed;
            Events.afterCommit(()->Events.success(Events.EVENT,"account.merge").addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",keep).addKeyValue("count",count)
                .log("Accounts merged by account {}: kept {}, {} other account(s) deleted into it; every sign-in of all of them ended and the link used up",actor.accountId(),keep,count));
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
            (r,i)->new Member(m.id,m.issuer,m.subject,m.username,m.email,r.getBoolean(2),r.getBoolean(1),m.author,r.getString(3)),m.id));
        return current;
    }
    /** An account the owner or operator disabled is neither kept nor deleted here; fewer than two leaves nothing to merge. */
    private static void requireMergeable(List<Member> members){
        if(members.size()<2||members.stream().anyMatch(m->!m.enabled||m.state!=null))throw conflict();
    }
    private Candidate candidate(Member member){
        var row=jdbc.queryForMap("SELECT display_name,admitted,requested_at,(SELECT max(created_at) FROM cloud_sign_ins s WHERE s.account_id=a.account_id) last FROM cloud_accounts a WHERE account_id=?",member.id);
        var methods=jdbc.query("SELECT provider,verified_email FROM cloud_identities WHERE account_id=? ORDER BY linked_at,id",(r,i)->method(r.getString(1),r.getString(2)),member.id);
        if(methods.isEmpty())methods=List.of(method(CloudIdentities.provider(member.issuer,member.username),member.email));
        var plans=jdbc.query("""
            SELECT p.id,p.name,p.progress::text,v.snapshot::text FROM plans p
            LEFT JOIN plan_versions v ON v.account_id=p.account_id AND v.plan_id=p.id AND v.id=p.current_version
            WHERE p.account_id=? ORDER BY p.created_at,p.id""",(r,i)->new Plan(r.getObject(1,UUID.class),r.getString(2),percent(r.getString(4),r.getString(3))),member.id);
        // Active or scheduled, not ended by a Delete or merge, of a priced plan or bought at a price.
        boolean paid=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM membership_purchases p JOIN membership_plans l ON l.id=p.plan_id WHERE p.account_id=?
              AND p.status IN ('succeeded','scheduled') AND p.ends_at>now() AND p.superseded_at IS NULL AND (p.price_paid_cents>0 OR l.price_cents>0))""",Boolean.class,member.id));
        return new Candidate(member.id,(String)row.get("display_name"),List.copyOf(methods),(Boolean)row.get("admitted"),
            instant(row.get("requested_at")),instant(row.get("last")),List.copyOf(plans),paid);
    }
    private static Map<String,Object> method(String provider,String email){
        var method=new LinkedHashMap<String,Object>();method.put("provider",provider);if(email!=null)method.put("email",email);return Collections.unmodifiableMap(method);
    }
    /** Completed scheduled sessions of the current version, as on the plan card; 0 when the plan has none or no card facts. */
    static int percent(String snapshot,String progress){
        if(snapshot==null||progress==null)return 0;
        try{
            var card=PlanCardMetadata.from(JSON.readTree(snapshot),JSON.readTree(progress),JSON);
            int total=card.path("totalSessionCount").asInt(0);
            return total<=0?0:Math.min(100,card.path("completedSessionCount").asInt(0)*100/total);
        }catch(RuntimeException unreadable){return 0;}
    }
    private static Instant instant(Object value){return value instanceof java.sql.Timestamp t?t.toInstant():value instanceof java.time.OffsetDateTime o?o.toInstant():null;}
    private Link link(String token,boolean lock){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw invalidToken();
        var rows=jdbc.query("SELECT email,expires_at FROM cloud_merge_tokens WHERE token_digest=? AND used_at IS NULL AND expires_at>clock_timestamp()"+(lock?" FOR UPDATE":""),
            (r,i)->new Link(r.getString(1),r.getTimestamp(2).toInstant()),CloudSignInRegistry.digest(token));
        if(rows.isEmpty())throw invalidToken();
        return rows.getFirst();
    }
    /** Requests, merges and first contacts of one email serialize here: the one key S36 linking takes too. */
    private void lock(String email){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"email:"+CloudIdentities.normalizeEmail(email));}
    private static String randomToken(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static AccountFailure invalidToken(){return new AccountFailure(404,"MERGE_TOKEN_INVALID","This link is not valid any more. Ask for a new email.");}
    static AccountFailure keepNotCandidate(){return new AccountFailure(422,"MERGE_KEEP_NOT_CANDIDATE","Choose one of the accounts shown.");}
    static AccountFailure conflict(){return new AccountFailure(409,"MERGE_CONFLICT","One of these accounts changed. Open the page again or ask the administrator.");}
}
