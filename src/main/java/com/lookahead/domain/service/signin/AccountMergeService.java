package com.lookahead.domain.service.signin;

import static com.lookahead.domain.util.AccountMergeRules.*;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.model.AccountMergeModels.*;
import com.lookahead.domain.client.EmailClient;
import com.lookahead.domain.db.store.signin.AccountMerge;
import com.lookahead.domain.db.store.signin.AccountMerge.CandidateFacts;
import com.lookahead.domain.db.store.signin.AccountMerge.Carrier;
import com.lookahead.domain.db.store.signin.AccountMerge.Link;
import com.lookahead.domain.db.store.signin.AccountMerge.LinkChange;
import com.lookahead.domain.util.RandomToken;
import com.lookahead.domain.util.SignInIdentityRules;
import java.time.Instant;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.db.store.access.AccountRemoval;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.*;

/**
 * DLV-969 S36.6 (owner decisions 2026-11-04): accounts that already share a verified email become one. The person asks
 * from a signed-in session; one single-use link (30 minutes, stored as a SHA-256 digest only, V20 cloud_merge_tokens)
 * goes to that session's own verified email; signed in as one of the accounts they choose the one to keep. The others
 * are deleted (their sign-ins move to the kept account first): an admitted one exactly as the owner's Delete deletes,
 * a waiting one exactly as the 48-hour cleanup removes it. Test accounts, deleted accounts and other emails never take
 * part. The sign-in's mergeAvailable offer itself tells the caller that other accounts share their own verified email
 * (and only theirs); the request, which always answers 202, reveals nothing more than that.
 * <p>Log lines (DLV-969, {@link EventLog}): {@code merge.request} (sent, or why nothing was sent), {@code merge.candidates}
 * and {@code merge.merge} refusals with their code, {@code account.merge} once the merge commits, one
 * {@code account.merge-removed} per account that went; account IDs and counts only, never the email or the link.
 * <p>DLV-970: the policy, the email and the log lines live here. DLV-1016: so does every check of the three units.
 * Each unit asks {@link AccountMerge} for its reads in turn, checks between them (the rules are in
 * {@link com.lookahead.domain.util.AccountMergeRules}) and ends in one commit of the store, fenced on the email's
 * sentinel and the accounts as read. A merge that loses the race answers MERGE_CONFLICT; a request reads again, up to
 * three attempts.
 */
public class AccountMergeService {
    public static final String SUBJECT="Confirm it's you to tidy up your Look Ahead account";
    public static final String WARNING="The other account's study plans and progress are deleted and this cannot be undone.";
    private final AccountMerge store;
    // DLV-1016: the store itself; the merge is authorised here, and the removal service no longer offers an unchecked erase.
    private final AccountRemoval removal;
    private final EmailClient email;
    private final String publicUrl;

    public AccountMergeService(AccountMerge store,AccountRemoval removal,EmailClient email,String publicUrl){
        this.store=store;this.removal=removal;this.email=email;
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
            EventLog.refused(EventLog.EVENT,"merge.request","NO_VERIFIED_EMAIL").addKeyValue("account.id",actor.accountId())
                .log("Merge link not sent for account {}: this sign-in has no verified email to send it to (the caller still gets 202)",actor.accountId());
            return;
        }
        Requested requested=requestLink(actor,to);
        if(requested.token()==null){
            if("MERGE_REQUEST_RATE_LIMITED".equals(requested.skipped()))
                EventLog.limit("merge.request","MERGE_REQUEST_RATE_LIMITED","merge-links",REQUESTS_PER_HOUR,"1h").addKeyValue("account.id",actor.accountId())
                    .log("Merge link not sent for account {}: already {} links for this email in the last hour (the caller still gets 202)",actor.accountId(),REQUESTS_PER_HOUR);
            else if("MERGE_REQUEST_CONFLICT".equals(requested.skipped()))
                EventLog.refused(EventLog.EVENT,"merge.request","MERGE_REQUEST_CONFLICT").addKeyValue("account.id",actor.accountId()).addKeyValue("count",requested.accounts())
                    .log("Merge link not sent for account {}: the accounts of this email changed while the link was being made; a new request makes one (the caller still gets 202)",actor.accountId());
            else EventLog.refused(EventLog.EVENT,"merge.request",requested.skipped()).addKeyValue("account.id",actor.accountId()).addKeyValue("count",requested.accounts())
                .log("Merge link not sent for account {}: {} account(s) carry this email, nothing to merge (the caller still gets 202)",actor.accountId(),requested.accounts());
            return;
        }
        // After the commit: an email outage costs one of the three sends, never the caller's answer.
        try{
            email.send(to,SUBJECT,body(linkFor(requested.token())));
            EventLog.success(EventLog.EVENT,"merge.request").addKeyValue("account.id",actor.accountId()).addKeyValue("count",requested.accounts())
                .log("Merge link sent for account {}: {} accounts share its verified email; the link is single use and valid for {} minutes",actor.accountId(),requested.accounts(),LINK_LIFETIME.toMinutes());
        }
        catch(RuntimeException failure){
            EventLog.failed(EventLog.EVENT,"merge.request","EMAIL_NOT_SENT").addKeyValue("account.id",actor.accountId()).addKeyValue("error.type",EventLog.types(failure))
                .log("Merge link for account {} was created but the email was not sent ({}); it counts as one of the {} sends per hour and the caller still gets 202",
                    actor.accountId(),EventLog.types(failure),REQUESTS_PER_HOUR);
        }
    }
    /**
     * The request's unit, under the email's sentinel: who can take part, links older than a day deleted, the hourly limit,
     * every outstanding link used up and the new one recorded (its expiry that of a replaced link, else 30 minutes).
     * The token of the link to send, or the reason nothing was made: NOTHING_TO_MERGE, MERGE_REQUEST_RATE_LIMITED, MERGE_REQUEST_CONFLICT.
     */
    private Requested requestLink(AccountPrincipal actor,String to){
        int size=0;
        for(int attempt=0;attempt<REQUEST_ATTEMPTS;attempt++){
            long emailRevision=store.emailRevision(to);
            var pool=pool(to);size=pool.size();
            if(pool.size()<2||!pool.contains(actor.accountId()))return new Requested(null,"NOTHING_TO_MERGE",pool.size());
            Instant now=store.now();
            var earlier=new ArrayList<LinkChange>();
            int recent=0;Instant sharedExpiry=null;
            for(Link link:store.links(to)){
                if(link.createdAt().isBefore(now.minus(LINK_RETENTION))){earlier.add(new LinkChange(link,true));continue;}
                if(link.createdAt().isAfter(now.minus(REQUEST_WINDOW)))recent++;
                if(!link.used()&&link.expiresAt().isAfter(now)){
                    // Replaced: the earlier link stops working, the new one keeps its expiry.
                    earlier.add(new LinkChange(link,false));
                    if(sharedExpiry==null)sharedExpiry=link.expiresAt();
                }
            }
            if(recent>=REQUESTS_PER_HOUR)return new Requested(null,"MERGE_REQUEST_RATE_LIMITED",pool.size());
            String token=RandomToken.next(),digest=Sha256.hex(token);
            Instant expires=sharedExpiry!=null?sharedExpiry:now.plus(LINK_LIFETIME);
            if(store.recordLink(to,emailRevision,actor.accountId(),earlier,digest,now,expires))return new Requested(token,null,pool.size());
            // A request, link or merge for this email landed first: read again.
        }
        return new Requested(null,"MERGE_REQUEST_CONFLICT",size);
    }
    /** Accounts of {@code email} that can take part, by id: not on the test issuer and not deleted (the store's read), and not test accounts. */
    private List<UUID> pool(String email){
        return store.carriers(email).stream().filter(carrier->!carrier.testAccount()).map(Carrier::id).sorted().toList();
    }
    /** The live link of a token: present, unused, not expired. */
    private Link liveLink(String token){
        Link link=store.link(digest(token));
        if(link==null||link.used()||!link.expiresAt().isAfter(store.now()))throw invalidToken();
        return link;
    }
    public static String body(String link){
        return "Hello,\n\nSomeone asked to sort out the Look Ahead accounts that use this email address. If it was you, open this link within "
            +LINK_LIFETIME.toMinutes()+" minutes:\n\n"+link+"\n\nYou will sign in and choose which account to keep. "+WARNING
            +"\n\nIf it wasn't you, ignore this email. Nothing changes.\n\nLook Ahead\n";
    }
    /** The link for a fresh token. */
    private String linkFor(String token){return publicUrl+"/account/merge?token="+token;}

    /** The accounts of the link's email with their sign-in methods and study plans; the caller must be one of them. */
    public Candidates candidates(AccountPrincipal actor,String token){
        try{return candidatesChecked(actor,token);}
        catch(AccountFailure refused){throw refusal("merge.candidates",actor,refused);}
    }
    private Candidates candidatesChecked(AccountPrincipal actor,String token){
        Link link=liveLink(token);
        var pool=pool(link.email());
        if(!pool.contains(actor.accountId()))throw invalidToken();
        var snapshot=store.read(pool);
        var members=snapshot.members();
        requireMergeable(members);
        var candidates=new ArrayList<Candidate>();
        for(Member member:members)candidates.add(candidate(member,store.candidate(snapshot,member.id())));
        return new Candidates(link.email(),link.expiresAt(),sorted(candidates));
    }
    /** One account on the merge page: an account without identity items shows its first sign-in; each plan shows its progress. */
    private static Candidate candidate(Member member,CandidateFacts facts){
        var methods=facts.signInMethods();
        if(methods.isEmpty())methods=List.of(method(SignInIdentityRules.provider(member.issuer(),member.username()),member.email()));
        var studyPlans=facts.plans().stream().map(card->new Plan(card.id(),card.name(),percent(card.snapshot(),card.progress()))).toList();
        return new Candidate(member.id(),facts.displayName(),List.copyOf(methods),member.admitted(),facts.requestedAt(),facts.lastSignInAt(),studyPlans,facts.paidMembership());
    }

    /** One WARN line per refused merge step: the stable code and the caller, never the token or the email. */
    private static AccountFailure refusal(String action,AccountPrincipal actor,AccountFailure refused){
        EventLog.refused(EventLog.EVENT,action,refused.code()).addKeyValue("account.id",actor.accountId())
            .log("{} refused for account {}: {}",action.equals("merge.candidates")?"Merge link opened":"Merge",actor.accountId(),refused.code());
        return refused;
    }

    /**
     * One unit: the accounts read and checked again, every identity of the others moved to the kept account,
     * every sign-in of all of them ended, the others' data erased by the Delete path and each marked deleted with a
     * {@code merge} event, the link used. No sign-in pool call: the others' Cognito users now belong to the kept account.
     */
    public Kept merge(AccountPrincipal actor,String token,UUID keep){
        try{return mergeChecked(actor,token,keep);}
        catch(AccountFailure refused){throw refusal("merge.merge",actor,refused);}
    }
    private Kept mergeChecked(AccountPrincipal actor,String token,UUID keep){
        Link link=liveLink(token);
        long emailRevision=store.emailRevision(link.email());
        var pool=pool(link.email());
        if(!pool.contains(actor.accountId()))throw invalidToken();
        var snapshot=store.read(pool);
        var members=snapshot.members();
        if(members.size()!=pool.size())throw conflict();
        if(keep==null||!pool.contains(keep)){
            if(keep!=null&&store.carries(link.email(),keep))throw conflict();
            throw keepNotCandidate();
        }
        requireMergeable(members);
        requireKeepable(members,keep);
        Merged merged=store.merge(snapshot,link,emailRevision,actor.accountId(),keep);
        // Another request, link, merge, first contact or the owner's Disable or Delete was first: nothing was written.
        if(merged==null)throw conflict();
        // Durable now: the erasure of each deleted account's data, resumable by the sweep if it stops here.
        for(Removed gone:merged.removed()){
            if(!gone.admitted())continue;
            UUID id=gone.id();
            try{removal.erase(actor.accountId(),id);}
            catch(RuntimeException failure){
                EventLog.failed(EventLog.EVENT,"account.erasure","UNEXPECTED").addKeyValue("target.id",id).addKeyValue("error.type",EventLog.types(failure))
                    .log("Erasure of merged account {} did not complete ({}); the account-erasure sweep tries again",id,EventLog.types(failure));
            }
        }
        for(Removed gone:merged.removed())removedLine(actor.accountId(),gone.id(),keep,gone.admitted(),gone.moved());
        mergedLine(actor.accountId(),keep,merged.removed().size());
        return new Kept(keep);
    }

    /** The DLV-969 line for one account that went, written once the merge is durable. */
    private static void removedLine(UUID actor,UUID drop,UUID keep,boolean admitted,int moved){
        var line=EventLog.success(EventLog.EVENT,"account.merge-removed").addKeyValue("account.id",actor).addKeyValue("target.id",drop)
            .addKeyValue("into",keep).addKeyValue("admitted",admitted).addKeyValue("count",moved);
        if(admitted)line.log("Account {} merged into {}: {} sign-in(s) moved to the kept account, its learning data erased and the account marked deleted; no sign-in pool call, the pool users now belong to the kept account",drop,keep,moved);
        else line.log("Waiting account {} merged into {}: {} sign-in(s) moved to the kept account, the waiting row removed as the 48 h cleanup removes one",drop,keep,moved);
    }
    /** The DLV-969 line for the merge itself, after every {@link #removedLine}. */
    private static void mergedLine(UUID actor,UUID keep,int count){
        EventLog.success(EventLog.EVENT,"account.merge").addKeyValue("account.id",actor).addKeyValue("target.id",keep).addKeyValue("count",count)
            .log("Accounts merged by account {}: kept {}, {} other account(s) deleted into it; every sign-in of all of them ended and the link used up",actor,keep,count);
    }

}
