package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL row locks serialize admission, replacement, disable and revocation per account. */
public final class CloudSignInRegistry {
    public record Entry(UUID signInId,boolean current,String label,String clientDescription,Instant createdAt,Instant lastActiveAt) {}
    public record Inventory(int limit,List<Entry> entries) {}
    /**
     * mergeAvailable (DLV-969 S36): this sign-in's verified email has two or more accounts that can take part (not test
     * accounts, not deleted) and none of them is disabled, so the page may offer the merge email (CloudMergeService).
     */
    public record Admission(UUID accountId,UUID signInId,String challengeToken,Instant expiresAt,boolean mergeAvailable) {
        public Admission(UUID accountId,UUID signInId,String challengeToken,Instant expiresAt){this(accountId,signInId,challengeToken,expiresAt,false);}
        @Override public String toString(){return "Admission[redacted]";}
    }
    public record ChallengeView(int limit,List<Entry> entries,Instant expiresAt) {}
    /** provider: of the identity that made this call (DLV-969 S36: an account may have several). */
    private record Account(UUID id,String username,String name,boolean author,String provider) {}
    private record Row(Account account,boolean enabled,boolean admitted,boolean credentialChangePending,boolean primary) {}
    private record SignIn(UUID id,String family,String binding,Instant recent,Instant created,Instant active,Instant revoked) {}
    private record Challenge(String family,String binding,Instant expires,Instant auth,boolean cancelled,UUID selected,UUID admitted) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    /** DLV-971: prepared access by email (StaffAccessService) admits a first contact at once; null leaves every first contact waiting. */
    private com.lookahead.domain.access.StaffAccessService access;
    public CloudSignInRegistry(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);}
    public void configureStaffAccess(com.lookahead.domain.access.StaffAccessService access){this.access=access;}

    static final int CHALLENGES_PER_WINDOW=5;
    /** DLV-969: a sign-in without a cloud_accounts row records a waiting row (first contact) and still answers 403. */
    public Admission admit(CognitoIdentity identity,String proof) {
        String binding=binding(proof),family=family(identity);
        Admission admission=locked(identity,false,true,account->merge(identity,admitted(identity,binding,family,account)));
        if(admission.signInId()!=null)Events.success(Events.EVENT,"sign-in.admitted").addKeyValue("account.id",admission.accountId()).addKeyValue("target.id",admission.signInId())
            .addKeyValue("merge.available",admission.mergeAvailable())
            .log(admission.mergeAvailable()?"Sign-in {} admitted for account {}; other accounts share its verified email, so the merge is offered":"Sign-in {} admitted for account {}",
                admission.signInId(),admission.accountId());
        else Events.success(Events.EVENT,"sign-in.challenge").addKeyValue("account.id",admission.accountId())
            .log("Account {} already has 2 active sign-ins: a challenge to replace one was issued",admission.accountId());
        return admission;
    }
    private Admission admitted(CognitoIdentity identity,String binding,String family,Account account) {
        Instant now=now();requireFresh(identity.authenticatedAt(),now);expire(account.id,now);
        var families=jdbc.query("SELECT * FROM cloud_sign_ins WHERE family_digest=?",(r,i)->signIn(r),family);
        if(!families.isEmpty()) {
            SignIn existing=families.getFirst();
            if(existing.revoked!=null || !existing.binding.equals(binding) || !owned(account.id,existing.id))throw denied();
            return new Admission(account.id,existing.id,null,null);
        }
        var bindings=jdbc.query("SELECT * FROM cloud_sign_ins WHERE account_id=? AND binding_digest=? AND revoked_at IS NULL",(r,i)->signIn(r),account.id,binding);
        if(!bindings.isEmpty()) {
            SignIn old=bindings.getFirst();
            // Keep the original family tombstone: revoked credentials must never be admitted again.
            revokeRow(account.id,old.id,now);
            return new Admission(account.id,insert(account.id,family,binding,identity.authenticatedAt(),now),null,null);
        }
        if(count(account.id)<2)return new Admission(account.id,insert(account.id,family,binding,identity.authenticatedAt(),now),null,null);
        var pending=jdbc.query("SELECT token_digest FROM cloud_sign_in_challenges WHERE account_id=? AND family_digest=? AND binding_digest=? AND cancelled=false AND expires_at>?",(r,i)->r.getString(1),account.id,family,binding,ts(now));
        // Do not issue a second challenge for the same token family; the caller retains its secret.
        if(!pending.isEmpty())throw failure(409,"SIGN_IN_CHALLENGE_PENDING");
        jdbc.update("DELETE FROM cloud_sign_in_challenges WHERE account_id=? AND expires_at<?",account.id,ts(now.minus(Duration.ofMinutes(15))));
        if(jdbc.queryForObject("SELECT count(*) FROM cloud_sign_in_challenges WHERE account_id=? AND created_at>?",Integer.class,account.id,ts(now.minus(Duration.ofMinutes(15))))>=CHALLENGES_PER_WINDOW){
            Events.limit("sign-in.challenge","SIGN_IN_RATE_LIMITED","sign-in-challenges",CHALLENGES_PER_WINDOW,"15m").addKeyValue("account.id",account.id)
                .log("Sign-in limit hit for account {}: at most {} sign-in challenges in 15 minutes; refused with 429",account.id,CHALLENGES_PER_WINDOW);
            throw failure(429,"SIGN_IN_RATE_LIMITED");
        }
        String token=randomToken();Instant expires=now.plus(Duration.ofMinutes(5));
        jdbc.update("INSERT INTO cloud_sign_in_challenges(token_digest,account_id,family_digest,binding_digest,created_at,expires_at,auth_at) VALUES(?,?,?,?,?,?,?)",digest(token),account.id,family,binding,ts(now),ts(expires),ts(identity.authenticatedAt()));
        return new Admission(account.id,null,token,expires);
    }
    /** The same answer with mergeAvailable, decided inside the sign-in's transaction. */
    private Admission merge(CognitoIdentity identity,Admission admission){
        return new Admission(admission.accountId(),admission.signInId(),admission.challengeToken(),admission.expiresAt(),
            CloudIdentities.mergeable(jdbc,admission.accountId(),email(identity)));
    }
    public AccountPrincipal authenticate(CognitoIdentity identity,String proof) {
        return locked(identity,account->{current(account,identity,proof,true);return new AccountPrincipal(account.id,account.username,account.name,true,account.author,identity.verifiedEmail());});
    }
    public Inventory inventory(CognitoIdentity identity,String proof){return locked(identity,a->{SignIn current=current(a,identity,proof,true);return new Inventory(2,entries(a.id,current.id));});}
    public ChallengeView challenge(CognitoIdentity identity,String proof,String token){return locked(identity,a->{Challenge c=challenge(a,identity,proof,token);expire(a.id,now());return new ChallengeView(2,entries(a.id,null),c.expires);});}
    public Admission replace(CognitoIdentity identity,String proof,String token,UUID selected){
        return locked(identity,a->{
            Challenge c=challenge(a,identity,proof,token);Instant now=now();expire(a.id,now());
            if(c.admitted!=null){if(!Objects.equals(c.selected,selected))throw invalid();current(a,identity,proof,false);return merge(identity,new Admission(a.id,c.admitted,null,null));}
            if(selected==null||!owned(a.id,selected))throw invalid();
            revokeRow(a.id,selected,now);
            if(count(a.id)>=2)throw invalid();
            // Duplicate challenges cannot create multiple active families or bindings.
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cloud_sign_ins WHERE family_digest=? OR (account_id=? AND binding_digest=? AND revoked_at IS NULL))",Boolean.class,c.family,a.id,c.binding)))throw invalid();
            UUID id=insert(a.id,c.family,c.binding,c.auth,now);
            jdbc.update("UPDATE cloud_sign_in_challenges SET selected_id=?,admitted_id=? WHERE token_digest=?",selected,id,digest(token));
            Events.afterCommit(()->Events.success(Events.EVENT,"sign-in.replaced").addKeyValue("account.id",a.id).addKeyValue("target.id",selected)
                .log("Account {} replaced sign-in {} with new sign-in {}",a.id,selected,id));
            return merge(identity,new Admission(a.id,id,null,null));
        });
    }
    public void cancel(CognitoIdentity identity,String proof,String token){locked(identity,a->{Challenge c=challenge(a,identity,proof,token);if(c.admitted==null)jdbc.update("UPDATE cloud_sign_in_challenges SET cancelled=true WHERE token_digest=?",digest(token));return null;});}
    public boolean revoke(CognitoIdentity identity,String proof,UUID target){return locked(identity,a->{SignIn current=current(a,identity,proof,false);requireFresh(current.recent,now());if(!owned(a.id,target))throw invalid();revokeRow(a.id,target,now());
        Events.afterCommit(()->Events.success(Events.EVENT,"sign-in.revoked").addKeyValue("account.id",a.id).addKeyValue("target.id",target).log("Account {} ended sign-in {}",a.id,target));
        return current.id.equals(target);});}
    public void revokeOthers(CognitoIdentity identity,String proof){locked(identity,a->{SignIn current=current(a,identity,proof,false);requireFresh(current.recent,now());int ended=jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=? AND id<>?",a.id,current.id);
        Events.afterCommit(()->Events.success(Events.EVENT,"sign-in.revoked-others").addKeyValue("account.id",a.id).addKeyValue("count",ended).log("Account {} ended its other sign-ins ({} row(s))",a.id,ended));
        return null;});}
    public Entry rename(CognitoIdentity identity,String proof,String label){
        if(label==null||label.strip().length()>80||label.codePoints().anyMatch(c->Character.isISOControl(c)||c=='<'||c=='>'))throw failure(400,"INVALID_SIGN_IN_LABEL");
        return locked(identity,a->{SignIn current=current(a,identity,proof,false);jdbc.update("UPDATE cloud_sign_ins SET label=? WHERE id=?",label.strip(),current.id);return entries(a.id,current.id).stream().filter(Entry::current).findFirst().orElseThrow();});
    }
    public void terminate(CognitoIdentity identity,String proof){locked(identity,a->{String binding=binding(proof);jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=? AND family_digest=? AND binding_digest=?",a.id,family(identity),binding);
        Events.afterCommit(()->Events.success(Events.EVENT,"sign-in.signed-out").addKeyValue("account.id",a.id).log("Account {} signed out",a.id));
        return null;});}
    public Map<String,Object> profile(CognitoIdentity identity,String proof,String displayName){return locked(identity,a->{
        current(a,identity,proof,true);String name=a.name;
        if(displayName!=null){name=displayName.strip();if(name.isEmpty()||name.codePointCount(0,name.length())>160||name.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw failure(422,"INVALID_PROFILE");jdbc.update("UPDATE cloud_accounts SET display_name=? WHERE account_id=?",name,a.id);}
        return profileView(a,name);
    });}
    /** The existing account lock and current sign-in proof also protect preference writes. */
    public Map<String,Object> preferences(CognitoIdentity identity,String proof,String theme) {
        if(theme==null||!Set.of("light","dark","system").contains(theme))throw failure(422,"INVALID_PREFERENCES");
        return locked(identity,a->{
            current(a,identity,proof,true);
            jdbc.update("INSERT INTO cloud_account_preferences(account_id,theme) VALUES(?,?) ON CONFLICT(account_id) DO UPDATE SET theme=EXCLUDED.theme,updated_at=clock_timestamp()",a.id,theme);
            return profileView(a,a.name);
        });
    }
    public Map<String,Object> links(CognitoIdentity identity,String proof,Map<String,String> links) {
        if(links==null||!links.keySet().equals(Set.copyOf(ProfileLinkPolicy.KINDS)))throw ProfileLinkPolicy.invalidShape();
        var values=new java.util.LinkedHashMap<String,String>();
        for(String kind:ProfileLinkPolicy.KINDS)values.put(kind,ProfileLinkPolicy.validate(kind,links.get(kind)));
        return locked(identity,a->{
            current(a,identity,proof,true);
            jdbc.update("DELETE FROM cloud_account_profile_links WHERE account_id=?",a.id);
            values.forEach((kind,url)->{if(url!=null)jdbc.update("INSERT INTO cloud_account_profile_links(account_id,kind,url) VALUES(?,?,?)",a.id,kind,url);});
            return profileView(a,a.name);
        });
    }
    private Map<String,Object> profileView(Account account,String name) {
        var theme=jdbc.query("SELECT theme FROM cloud_account_preferences WHERE account_id=?",(row,index)->row.getString(1),account.id);
        var links=new java.util.LinkedHashMap<String,String>();
        for(String kind:ProfileLinkPolicy.KINDS)links.put(kind,null);
        jdbc.query("SELECT kind,url FROM cloud_account_profile_links WHERE account_id=?",(org.springframework.jdbc.core.RowCallbackHandler) row->links.put(row.getString(1),row.getString(2)),account.id);
        // DLV-969 S36 (owner decision 2026-11-04): Google ("Google_<id>" in Cognito) and email-and-password with the same
        // verified Gmail are one account. signInMethods lists every identity of the account, oldest first, each with its
        // verified email; signInMethod (kept for older pages) is the provider of the identity making this call.
        var methods=jdbc.query("SELECT provider,verified_email FROM cloud_identities WHERE account_id=? ORDER BY linked_at,id",(r,i)->{
            var method=new java.util.LinkedHashMap<String,Object>();method.put("provider",r.getString(1));
            if(r.getString(2)!=null)method.put("email",r.getString(2));return Collections.unmodifiableMap(method);},account.id);
        var email=jdbc.queryForList("SELECT verified_email FROM cloud_accounts WHERE account_id=? AND verified_email IS NOT NULL",String.class,account.id);
        var view=new java.util.LinkedHashMap<String,Object>(Map.of("accountId",account.id,"username",account.username,"displayName",name,
                "signInMethod",account.provider,"signInMethods",List.copyOf(methods),"themePreference",theme.isEmpty()?"system":theme.getFirst(),"links",links));
        String shown=!email.isEmpty()?email.getFirst():methods.stream().map(m->(String)m.get("email")).filter(Objects::nonNull).findFirst().orElse(null);
        if(shown!=null)view.put("email",shown);
        return view;
    }
    public void changeCredentials(CognitoIdentity identity,String proof,Runnable providerChange){
        // Commit a fail-closed intent before crossing the provider/database boundary.
        // A process crash or failed completion commit leaves all account access blocked.
        UUID account=locked(identity,a->{current(a,identity,proof,false);jdbc.update("UPDATE cloud_accounts SET credential_change_pending=true WHERE account_id=?",a.id);return a.id;});
        try {providerChange.run();}
        catch(AccountFailure rejected){
            boolean knownRejection=Set.of("INVALID_CURRENT_PASSWORD","INVALID_PASSWORD").contains(rejected.code());
            finishCredentialChange(identity,!knownRejection);
            (knownRejection?Events.refused(Events.EVENT,"password.change",rejected.code()):Events.failed(Events.EVENT,"password.change",rejected.code()))
                .addKeyValue("account.id",account)
                .log(knownRejection?"Password change for account {} refused by the sign-in service: {}":"Password change for account {} not confirmed by the sign-in service: {}; every sign-in of the account was ended",account,rejected.code());
            throw rejected;
        }
        catch(RuntimeException uncertain){finishCredentialChange(identity,true);Events.failed(Events.EVENT,"password.change","UNEXPECTED").addKeyValue("account.id",account).log("Password change for account {} failed: {}; every sign-in of the account was ended",account,Events.types(uncertain));throw uncertain;}
        finishCredentialChange(identity,true);
        Events.success(Events.EVENT,"password.change").addKeyValue("account.id",account).log("Password changed for account {}; every sign-in of the account was ended",account);
    }
    private void finishCredentialChange(CognitoIdentity identity,boolean revoke){locked(identity,true,false,a->{
        if(revoke){
            jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=?",a.id);
            jdbc.update("UPDATE cloud_sign_in_challenges SET cancelled=true WHERE account_id=?",a.id);
        }
        jdbc.update("UPDATE cloud_accounts SET credential_change_pending=false WHERE account_id=?",a.id);return null;
    });}
    private SignIn current(Account a,CognitoIdentity identity,String proof,boolean touch){
        Instant now=now();expire(a.id,now());
        var rows=jdbc.query("SELECT * FROM cloud_sign_ins WHERE account_id=? AND family_digest=? AND binding_digest=? AND revoked_at IS NULL",(r,i)->signIn(r),a.id,family(identity),binding(proof));
        if(rows.size()!=1)throw denied();SignIn result=rows.getFirst();
        if(touch)jdbc.update("UPDATE cloud_sign_ins SET last_active_at=? WHERE id=?",ts(now),result.id);return result;
    }
    private Challenge challenge(Account a,CognitoIdentity identity,String proof,String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw invalid();
        var rows=jdbc.query("SELECT * FROM cloud_sign_in_challenges WHERE account_id=? AND token_digest=?",(r,i)->new Challenge(r.getString("family_digest"),r.getString("binding_digest"),r.getTimestamp("expires_at").toInstant(),r.getTimestamp("auth_at").toInstant(),r.getBoolean("cancelled"),r.getObject("selected_id",UUID.class),r.getObject("admitted_id",UUID.class)),a.id,digest(token));
        if(rows.size()!=1)throw invalid();Challenge c=rows.getFirst();
        if(c.cancelled||!c.expires.isAfter(now())||!c.family.equals(family(identity))||!c.binding.equals(binding(proof)))throw invalid();return c;
    }
    private <T>T locked(CognitoIdentity identity,Function<Account,T> action){
        return locked(identity,false,false,action);
    }
    /**
     * firstContact (sign-in admission only): an unknown identity is linked to the one account with its verified email,
     * or recorded as a new waiting account, and an unadmitted row is refreshed from the provider claims; all commit
     * before the 403 is thrown. Every other path leaves the tables untouched (except the V19 identity row of an account
     * a release before V19 created).
     */
    private <T>T locked(CognitoIdentity identity,boolean allowCredentialChange,boolean firstContact,Function<Account,T> action){
        var waiting=new AccountFailure[1];var waitingId=new UUID[1];var waitingAction=new String[1];
        T result=transaction.execute(status->{
            // Two concurrent first contacts of one identity serialize here; the second sees the row the first created.
            if(firstContact)jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},identity.issuer()+"\n"+identity.subject());
            Row row=find(identity);boolean linked=false;
            if(row==null){
                if(!firstContact)throw admissionRequired();
                row=link(identity);
                if(row==null){
                    // DLV-971 (owner, 2026-11-07): prepared access by email admits the new account inside request(); only a
                    // row still unadmitted after that waits for the owner.
                    UUID created=request(identity);row=find(identity);
                    if(!row.admitted){waitingId[0]=created;waitingAction[0]="created";waiting[0]=admissionRequired();return null;}
                } else linked=true;
            }
            // DLV-969: an account the owner disabled or deleted (V18) has no sign-in, even before the pool is told.
            var states=jdbc.queryForList("SELECT state FROM cloud_account_states WHERE account_id=?",String.class,row.account.id);
            if(!row.enabled||!states.isEmpty()){
                String reason=states.isEmpty()?"ACCOUNT_DISABLED":"removed".equals(states.getFirst())?"ACCOUNT_REMOVED":"ACCOUNT_DISABLED";
                Events.refused(Events.EVENT,"sign-in.check",reason).addKeyValue("account.id",row.account.id)
                    .log("Sign-in refused for account {}: {}",row.account.id,reason.equals("ACCOUNT_REMOVED")?"the owner deleted it":"the owner disabled it");
                throw denied();
            }
            // DLV-969 S36: a sign-in just linked to a waiting account is "linked"; the owner still has to admit it.
            if(!row.admitted){if(!firstContact)throw denied();if(!linked)refresh(identity,row);waitingId[0]=row.account.id;waitingAction[0]=linked?"linked":"refreshed";waiting[0]=admissionRequired();return null;}
            // DLV-969 S36: every sign-in of an admitted account brings the provider's current verified email, so the
            // linking candidates, duplicate counts and merge offer follow the address the person has now.
            if(firstContact&&!linked)refreshEmail(identity,row);
            if(!allowCredentialChange&&row.credentialChangePending){
                Events.refused(Events.EVENT,"sign-in.check","CREDENTIAL_CHANGE_PENDING").addKeyValue("account.id",row.account.id)
                    .log("Request refused for account {}: a password change is not confirmed yet (503)",row.account.id);
                throw failure(503,"CREDENTIAL_CHANGE_PENDING");
            }
            return action.apply(row.account);
        });
        if(waiting[0]!=null){
            // Committed: the owner now sees the waiting row on People and access.
            Events.refused(Events.EVENT,"admission.waiting-"+waitingAction[0],"ACCOUNT_ADMISSION_REQUIRED").addKeyValue("account.id",waitingId[0])
                .log("Sign-in refused for account {}: not admitted yet; waiting row {} (removed after {} h unless the owner admits it)",
                    waitingId[0],waitingAction[0],CloudAdmissionCleanup.WAITING_EXPIRY.toHours());
            throw waiting[0];
        }
        return result;
    }
    /** DLV-969 S36: the account of a known identity, row-locked; an account from before V19 gets its identity row now. */
    private Row find(CognitoIdentity identity){
        var known=jdbc.query("SELECT account_id,provider FROM cloud_identities WHERE issuer=? AND subject=?",(r,i)->new Object[]{r.getObject(1,UUID.class),r.getString(2)},identity.issuer(),identity.subject());
        if(!known.isEmpty())return account((UUID)known.getFirst()[0],(String)known.getFirst()[1],identity);
        var legacy=jdbc.query("SELECT account_id,username,verified_email FROM cloud_accounts WHERE issuer=? AND subject=? FOR UPDATE",(r,i)->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getString(3)},identity.issuer(),identity.subject());
        if(legacy.isEmpty())return null;
        var first=legacy.getFirst();
        CloudIdentities.insert(jdbc,(UUID)first[0],identity.issuer(),identity.subject(),(String)first[1],(String)first[2],"backfill");
        return account((UUID)first[0],CloudIdentities.provider(identity.issuer(),(String)first[1]),identity);
    }
    private Row account(UUID id,String provider,CognitoIdentity identity){
        var rows=jdbc.query("SELECT * FROM cloud_accounts WHERE account_id=? FOR UPDATE",(r,i)->new Row(
            new Account(r.getObject("account_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getBoolean("author_access"),provider),
            r.getBoolean("enabled"),r.getBoolean("admitted"),r.getBoolean("credential_change_pending"),
            identity.issuer().equals(r.getString("issuer"))&&identity.subject().equals(r.getString("subject"))),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    /**
     * DLV-969 S36 (owner decision 2026-11-04): an unknown identity with a provider-verified email joins the one eligible
     * account that already has that email (V19 cloud_identities). Zero or several candidates, no verified email, or a
     * candidate disabled or deleted meanwhile: null, and the caller records a new waiting account. Never a guess.
     */
    private Row link(CognitoIdentity identity){
        String email=email(identity);
        if(email==null)return null;
        // A Google and a password first contact for one Gmail at the same moment serialize here: the second one links.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"email:"+CloudIdentities.normalizeEmail(email));
        var candidates=jdbc.queryForList(CloudIdentities.candidates(),UUID.class,email,email);
        String provider=CloudIdentities.provider(identity.issuer(),identity.username());
        if(candidates.size()>1){
            // Several accounts already share this email: the situation the person's own merge (S36.6) resolves.
            int several=candidates.size();
            Events.afterCommit(()->Events.success(Events.EVENT,"identity.link-skipped").addKeyValue("reason","SEVERAL_SAME_EMAIL_ACCOUNTS").addKeyValue("provider",provider).addKeyValue("count",several)
                .log("New {} sign-in not linked: {} eligible accounts already share its verified email; recorded as a new waiting account",provider,several));
            return null;
        }
        if(candidates.size()!=1)return null;
        Row row=account(candidates.getFirst(),provider,identity);
        // The row lock decides against a concurrent Disable or Delete: only a still-eligible account is linked.
        if(row==null||!row.enabled||!jdbc.queryForList("SELECT state FROM cloud_account_states WHERE account_id=?",String.class,row.account.id).isEmpty())return null;
        CloudIdentities.insert(jdbc,row.account.id,identity.issuer(),identity.subject(),identity.username(),email,"linked");
        jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,NULL,'link','[]'::jsonb)",UUID.randomUUID(),row.account.id,identity.issuer(),identity.subject());
        UUID linkedTo=row.account.id;boolean admitted=row.admitted;
        Events.afterCommit(()->Events.success(Events.EVENT,"identity.linked").addKeyValue("account.id",linkedTo).addKeyValue("provider",provider).addKeyValue("admitted",admitted)
            .log("New {} sign-in linked to account {}: its verified email matched exactly one eligible account{}",provider,linkedTo,admitted?"":" (still waiting for the owner to admit it)"));
        return row;
    }
    private UUID request(CognitoIdentity identity){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO platform_subjects(id) VALUES(?)",id);
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,enabled,author_access,requested_at,username,display_name,verified_email) VALUES(?,?,?,false,true,false,clock_timestamp(),?,?,?)",
            id,identity.issuer(),identity.subject(),identity.username(),identity.displayName(),email(identity));
        CloudIdentities.insert(jdbc,id,identity.issuer(),identity.subject(),identity.username(),email(identity),"first_contact");
        jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,NULL,'request','[]'::jsonb)",UUID.randomUUID(),id,identity.issuer(),identity.subject());
        // DLV-971 (owner, 2026-11-07): prepared access by email is a pass through the DEV gate. The invitation is claimed
        // for the new account and the account admitted in the same transaction, so the owner never sees it waiting.
        var prepared=access==null?null:access.claimPrepared(id,email(identity));
        if(prepared!=null){
            var roles=new TreeSet<>(prepared.roles());
            jdbc.update("UPDATE cloud_accounts SET admitted=true,admitted_at=clock_timestamp() WHERE account_id=?",id);
            jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,NULL,'admit',?::jsonb)",UUID.randomUUID(),id,identity.issuer(),identity.subject(),json(roles));
            Events.afterCommit(()->Events.success(Events.EVENT,"admission.admitted-by-invitation").addKeyValue("account.id",id)
                .addKeyValue("target.id",prepared.id()).addKeyValue("roles",roles)
                .log("Account {} admitted at first contact by prepared access {}: roles {}",id,prepared.id(),roles.isEmpty()?"none (learner)":roles));
        }
        return id;
    }
    /** Role names come from the fixed StaffPermissions set; escaped all the same. */
    private static String json(Collection<String> values){
        return values.stream().map(v->"\""+v.replace("\\","\\\\").replace("\"","\\\"")+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));
    }
    /** The identity's own claims always; the account's profile only from its first identity. */
    private void refresh(CognitoIdentity identity,Row row){
        jdbc.update("UPDATE cloud_identities SET username=?,verified_email=? WHERE issuer=? AND subject=?",identity.username(),email(identity),identity.issuer(),identity.subject());
        if(row.primary)jdbc.update("UPDATE cloud_accounts SET username=?,display_name=?,verified_email=? WHERE account_id=?",identity.username(),identity.displayName(),email(identity),row.account.id);
    }
    /** The identity's verified email, and the account's when it is the first identity; only a changed value is written. */
    private void refreshEmail(CognitoIdentity identity,Row row){
        String email=email(identity);
        jdbc.update("UPDATE cloud_identities SET verified_email=? WHERE issuer=? AND subject=? AND verified_email IS DISTINCT FROM ?",email,identity.issuer(),identity.subject(),email);
        if(row.primary)jdbc.update("UPDATE cloud_accounts SET verified_email=? WHERE account_id=? AND verified_email IS DISTINCT FROM ?",email,row.account.id,email);
    }
    private static String email(CognitoIdentity identity){String email=identity.verifiedEmail();return email==null||email.length()>254?null:email;}
    private List<Entry> entries(UUID owner,UUID current){return jdbc.query("SELECT * FROM cloud_sign_ins WHERE account_id=? AND revoked_at IS NULL ORDER BY created_at,id",(r,i)->new Entry(r.getObject("id",UUID.class),Objects.equals(current,r.getObject("id",UUID.class)),r.getString("label"),r.getString("client_description"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("last_active_at").toInstant()),owner);}
    private UUID insert(UUID owner,String family,String binding,Instant auth,Instant now){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO cloud_sign_ins(id,account_id,family_digest,binding_digest,created_at,last_active_at,recent_auth_at) VALUES(?,?,?,?,?,?,?)",id,owner,family,binding,ts(now),ts(now),ts(auth));return id;}
    private boolean owned(UUID owner,UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cloud_sign_ins WHERE account_id=? AND id=?)",Boolean.class,owner,id));}
    private int count(UUID owner){return jdbc.queryForObject("SELECT count(*) FROM cloud_sign_ins WHERE account_id=? AND revoked_at IS NULL",Integer.class,owner);}
    private void expire(UUID owner,Instant now){jdbc.update("UPDATE cloud_sign_ins SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL AND (last_active_at<=? OR created_at<=?)",ts(now),owner,ts(now.minus(Duration.ofMinutes(30))),ts(now.minus(Duration.ofDays(7))));}
    private void revokeRow(UUID owner,UUID id,Instant now){jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,?) WHERE account_id=? AND id=?",ts(now),owner,id);}
    private static SignIn signIn(java.sql.ResultSet r)throws java.sql.SQLException{return new SignIn(r.getObject("id",UUID.class),r.getString("family_digest"),r.getString("binding_digest"),r.getTimestamp("recent_auth_at").toInstant(),r.getTimestamp("created_at").toInstant(),r.getTimestamp("last_active_at").toInstant(),r.getTimestamp("revoked_at")==null?null:r.getTimestamp("revoked_at").toInstant());}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static Timestamp ts(Instant value){return Timestamp.from(value);}
    private static void requireFresh(Instant auth,Instant now){if(auth.isAfter(now.plusSeconds(60))||!auth.plus(Duration.ofMinutes(5)).isAfter(now))throw failure(403,"RECENT_AUTHENTICATION_REQUIRED");}
    private static String binding(String proof){if(proof==null||!proof.matches("[A-Za-z0-9_-]{43}"))throw denied();return digest(proof);}
    private static String family(CognitoIdentity identity){return digest(identity.issuer()+"\n"+identity.subject()+"\n"+identity.family());}
    public static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String randomToken(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static AccountFailure failure(int status,String code){return new AccountFailure(status,code,"Sign in again or contact the account administrator.");}
    private static AccountFailure denied(){return failure(401,"AUTHENTICATION_REQUIRED");}
    private static AccountFailure admissionRequired(){return failure(403,"ACCOUNT_ADMISSION_REQUIRED");}
    private static AccountFailure invalid(){return failure(400,"SIGN_IN_CHALLENGE_INVALID");}
}
