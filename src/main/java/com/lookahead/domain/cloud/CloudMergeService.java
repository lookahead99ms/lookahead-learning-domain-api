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
import tools.jackson.databind.JsonNode;
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
 * <p>DLV-970: the policy, the email and the log lines live here; the store work is one unit per call in
 * {@link DynamoCloudMergeService} (one fenced DynamoDB transaction under the email's sentinel).
 */
public abstract class CloudMergeService {
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
    /** One account of the email's pool as the store read it; {@code state} is the V18 state (null, disabled, removed). */
    protected record Member(UUID id,String issuer,String subject,String username,String email,boolean admitted,boolean enabled,boolean author,String state) {}
    /** The request's outcome inside its unit: the link to send, or the reason code for sending nothing. */
    protected record Requested(String link,String skipped,int accounts) {}
    protected static final ObjectMapper JSON=new ObjectMapper();
    protected final AccountRemovalService removal;
    protected final EmailSender email;
    protected final String publicUrl;

    protected CloudMergeService(AccountRemovalService removal,EmailSender email,String publicUrl){
        this.removal=removal;this.email=email;
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
        Requested requested=requested(actor,to);
        if(requested.link()==null){
            if("MERGE_REQUEST_RATE_LIMITED".equals(requested.skipped()))
                Events.limit("merge.request","MERGE_REQUEST_RATE_LIMITED","merge-links",REQUESTS_PER_HOUR,"1h").addKeyValue("account.id",actor.accountId())
                    .log("Merge link not sent for account {}: already {} links for this email in the last hour (the caller still gets 202)",actor.accountId(),REQUESTS_PER_HOUR);
            else if("MERGE_REQUEST_CONFLICT".equals(requested.skipped()))
                Events.refused(Events.EVENT,"merge.request","MERGE_REQUEST_CONFLICT").addKeyValue("account.id",actor.accountId()).addKeyValue("count",requested.accounts())
                    .log("Merge link not sent for account {}: the accounts of this email changed while the link was being made; a new request makes one (the caller still gets 202)",actor.accountId());
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
    /**
     * The request's unit on one store, under the email's lock: the pool checked, day-old links cleared, the hourly limit,
     * every outstanding link used up and the new one recorded (its expiry that of a replaced link, else 30 minutes).
     * The link to send, or the reason nothing was made: NOTHING_TO_MERGE, MERGE_REQUEST_RATE_LIMITED, MERGE_REQUEST_CONFLICT.
     */
    protected abstract Requested requested(AccountPrincipal actor,String to);
    static String body(String link){
        return "Hello,\n\nSomeone asked to sort out the Look Ahead accounts that use this email address. If it was you, open this link within "
            +LINK_LIFETIME.toMinutes()+" minutes:\n\n"+link+"\n\nYou will sign in and choose which account to keep. "+WARNING
            +"\n\nIf it wasn't you, ignore this email. Nothing changes.\n\nLook Ahead\n";
    }
    /** The link for a fresh token. */
    protected String linkFor(String token){return publicUrl+"/account/merge?token="+token;}

    /** The accounts of the link's email with their sign-in methods and study plans; the caller must be one of them. */
    public Candidates candidates(AccountPrincipal actor,String token){
        try{return candidatesChecked(actor,token);}
        catch(AccountFailure refused){throw refusal("merge.candidates",actor,refused);}
    }
    protected abstract Candidates candidatesChecked(AccountPrincipal actor,String token);

    /** One WARN line per refused merge step: the stable code and the caller, never the token or the email. */
    private static AccountFailure refusal(String action,AccountPrincipal actor,AccountFailure refused){
        Events.refused(Events.EVENT,action,refused.code()).addKeyValue("account.id",actor.accountId())
            .log("{} refused for account {}: {}",action.equals("merge.candidates")?"Merge link opened":"Merge",actor.accountId(),refused.code());
        return refused;
    }

    /**
     * One unit: the accounts locked and checked again, every identity of the others moved to the kept account,
     * every sign-in of all of them ended, the others' data erased by the Delete path and each marked deleted with a
     * {@code merge} event, the link used. No sign-in pool call: the others' Cognito users now belong to the kept account.
     */
    public Kept merge(AccountPrincipal actor,String token,UUID keep){
        try{return mergeChecked(actor,token,keep);}
        catch(AccountFailure refused){throw refusal("merge.merge",actor,refused);}
    }
    protected abstract Kept mergeChecked(AccountPrincipal actor,String token,UUID keep);

    /** The stored form of a link's token; a token of another shape is no link at all. */
    protected static String digest(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw invalidToken();
        return CloudSignInRegistry.digest(token);
    }
    /** An account the owner or operator disabled is neither kept nor deleted here; fewer than two leaves nothing to merge. */
    protected static void requireMergeable(List<Member> members){
        if(members.size()<2||members.stream().anyMatch(m->!m.enabled||m.state!=null))throw conflict();
    }
    /** The kept account must be able to sign in, and an author account is never the one deleted. */
    protected static void requireKeepable(List<Member> members,UUID keep){
        Member kept=members.stream().filter(m->m.id.equals(keep)).findFirst().orElseThrow(CloudMergeService::keepNotCandidate);
        if(!kept.admitted||members.stream().anyMatch(m->m.author&&!m.id.equals(keep)))throw keepNotCandidate();
    }
    protected static List<Candidate> sorted(List<Candidate> candidates){
        return candidates.stream().sorted(Comparator.comparing(Candidate::createdAt,Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
    protected static Map<String,Object> method(String provider,String email){
        var method=new LinkedHashMap<String,Object>();method.put("provider",provider);if(email!=null)method.put("email",email);return Collections.unmodifiableMap(method);
    }
    /** The DLV-969 line for one account that went, written once the merge is durable. */
    protected static void removedLine(UUID actor,UUID drop,UUID keep,boolean admitted,int moved){
        var line=Events.success(Events.EVENT,"account.merge-removed").addKeyValue("account.id",actor).addKeyValue("target.id",drop)
            .addKeyValue("into",keep).addKeyValue("admitted",admitted).addKeyValue("count",moved);
        if(admitted)line.log("Account {} merged into {}: {} sign-in(s) moved to the kept account, its learning data erased and the account marked deleted; no sign-in pool call, the pool users now belong to the kept account",drop,keep,moved);
        else line.log("Waiting account {} merged into {}: {} sign-in(s) moved to the kept account, the waiting row removed as the 48 h cleanup removes one",drop,keep,moved);
    }
    /** The DLV-969 line for the merge itself, after every {@link #removedLine}. */
    protected static void mergedLine(UUID actor,UUID keep,int count){
        Events.success(Events.EVENT,"account.merge").addKeyValue("account.id",actor).addKeyValue("target.id",keep).addKeyValue("count",count)
            .log("Accounts merged by account {}: kept {}, {} other account(s) deleted into it; every sign-in of all of them ended and the link used up",actor,keep,count);
    }
    /** Completed scheduled sessions of the current version, as on the plan card; 0 when the plan has none or no card facts. */
    static int percent(String snapshot,String progress){
        if(snapshot==null||progress==null)return 0;
        try{return percent(JSON.readTree(snapshot),JSON.readTree(progress));}
        catch(RuntimeException unreadable){unreadable(unreadable);return 0;}
    }
    static int percent(JsonNode snapshot,JsonNode progress){
        if(snapshot==null||progress==null||snapshot.isNull()||progress.isNull())return 0;
        try{
            var card=PlanCardMetadata.from(snapshot,progress,JSON);
            int total=card.path("totalSessionCount").asInt(0);
            return total<=0?0:Math.min(100,card.path("completedSessionCount").asInt(0)*100/total);
        }catch(RuntimeException unreadable){unreadable(unreadable);return 0;}
    }
    private static void unreadable(RuntimeException unreadable){
        // DLV-969 (owner, 2026-11-04): the merge page would show 0% with no reason.
        String types=Events.types(unreadable);
        if(PERCENT.due(types))Events.failed(Events.EVENT,"merge.candidates","PLAN_CARD_UNREADABLE").addKeyValue("error.type",types)
            .log("A study plan's progress could not be read for the merge page ({}); it shows 0%",types);
    }
    private static final com.lookahead.domain.Throttle PERCENT=new com.lookahead.domain.Throttle();
    protected static String randomToken(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static AccountFailure invalidToken(){return new AccountFailure(404,"MERGE_TOKEN_INVALID","This link is not valid any more. Ask for a new email.");}
    static AccountFailure keepNotCandidate(){return new AccountFailure(422,"MERGE_KEEP_NOT_CANDIDATE","Choose one of the accounts shown.");}
    static AccountFailure conflict(){return new AccountFailure(409,"MERGE_CONFLICT","One of these accounts changed. Open the page again or ask the administrator.");}
}
