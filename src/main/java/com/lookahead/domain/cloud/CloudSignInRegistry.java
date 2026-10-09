package com.lookahead.domain.cloud;

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
    public record Admission(UUID accountId,UUID signInId,String challengeToken,Instant expiresAt) {
        @Override public String toString(){return "Admission[redacted]";}
    }
    public record ChallengeView(int limit,List<Entry> entries,Instant expiresAt) {}
    private record Account(UUID id,String username,String name,boolean author) {}
    private record SignIn(UUID id,String family,String binding,Instant recent,Instant created,Instant active,Instant revoked) {}
    private record Challenge(String family,String binding,Instant expires,Instant auth,boolean cancelled,UUID selected,UUID admitted) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public CloudSignInRegistry(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);}

    public Admission admit(CognitoIdentity identity,String proof) {
        String binding=binding(proof),family=family(identity);
        return locked(identity,account->{
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
            if(jdbc.queryForObject("SELECT count(*) FROM cloud_sign_in_challenges WHERE account_id=? AND created_at>?",Integer.class,account.id,ts(now.minus(Duration.ofMinutes(15))))>=5)throw failure(429,"SIGN_IN_RATE_LIMITED");
            String token=randomToken();Instant expires=now.plus(Duration.ofMinutes(5));
            jdbc.update("INSERT INTO cloud_sign_in_challenges(token_digest,account_id,family_digest,binding_digest,created_at,expires_at,auth_at) VALUES(?,?,?,?,?,?,?)",digest(token),account.id,family,binding,ts(now),ts(expires),ts(identity.authenticatedAt()));
            return new Admission(account.id,null,token,expires);
        });
    }
    public AccountPrincipal authenticate(CognitoIdentity identity,String proof) {
        return locked(identity,account->{current(account,identity,proof,true);return new AccountPrincipal(account.id,account.username,account.name,true,account.author);});
    }
    public Inventory inventory(CognitoIdentity identity,String proof){return locked(identity,a->{SignIn current=current(a,identity,proof,true);return new Inventory(2,entries(a.id,current.id));});}
    public ChallengeView challenge(CognitoIdentity identity,String proof,String token){return locked(identity,a->{Challenge c=challenge(a,identity,proof,token);expire(a.id,now());return new ChallengeView(2,entries(a.id,null),c.expires);});}
    public Admission replace(CognitoIdentity identity,String proof,String token,UUID selected){
        return locked(identity,a->{
            Challenge c=challenge(a,identity,proof,token);Instant now=now();expire(a.id,now());
            if(c.admitted!=null){if(!Objects.equals(c.selected,selected))throw invalid();current(a,identity,proof,false);return new Admission(a.id,c.admitted,null,null);}
            if(selected==null||!owned(a.id,selected))throw invalid();
            revokeRow(a.id,selected,now);
            if(count(a.id)>=2)throw invalid();
            // Duplicate challenges cannot create multiple active families or bindings.
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cloud_sign_ins WHERE family_digest=? OR (account_id=? AND binding_digest=? AND revoked_at IS NULL))",Boolean.class,c.family,a.id,c.binding)))throw invalid();
            UUID id=insert(a.id,c.family,c.binding,c.auth,now);
            jdbc.update("UPDATE cloud_sign_in_challenges SET selected_id=?,admitted_id=? WHERE token_digest=?",selected,id,digest(token));
            return new Admission(a.id,id,null,null);
        });
    }
    public void cancel(CognitoIdentity identity,String proof,String token){locked(identity,a->{Challenge c=challenge(a,identity,proof,token);if(c.admitted==null)jdbc.update("UPDATE cloud_sign_in_challenges SET cancelled=true WHERE token_digest=?",digest(token));return null;});}
    public boolean revoke(CognitoIdentity identity,String proof,UUID target){return locked(identity,a->{SignIn current=current(a,identity,proof,false);requireFresh(current.recent,now());if(!owned(a.id,target))throw invalid();revokeRow(a.id,target,now());return current.id.equals(target);});}
    public void revokeOthers(CognitoIdentity identity,String proof){locked(identity,a->{SignIn current=current(a,identity,proof,false);requireFresh(current.recent,now());jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=? AND id<>?",a.id,current.id);return null;});}
    public Entry rename(CognitoIdentity identity,String proof,String label){
        if(label==null||label.strip().length()>80||label.codePoints().anyMatch(c->Character.isISOControl(c)||c=='<'||c=='>'))throw failure(400,"INVALID_SIGN_IN_LABEL");
        return locked(identity,a->{SignIn current=current(a,identity,proof,false);jdbc.update("UPDATE cloud_sign_ins SET label=? WHERE id=?",label.strip(),current.id);return entries(a.id,current.id).stream().filter(Entry::current).findFirst().orElseThrow();});
    }
    public void terminate(CognitoIdentity identity,String proof){locked(identity,a->{String binding=binding(proof);jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=? AND family_digest=? AND binding_digest=?",a.id,family(identity),binding);return null;});}
    public Map<String,Object> profile(CognitoIdentity identity,String proof,String displayName){return locked(identity,a->{
        current(a,identity,proof,true);String name=a.name;
        if(displayName!=null){name=displayName.strip();if(name.isEmpty()||name.codePointCount(0,name.length())>160||name.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw failure(422,"INVALID_PROFILE");jdbc.update("UPDATE cloud_accounts SET display_name=? WHERE account_id=?",name,a.id);}
        return Map.of("accountId",a.id,"username",a.username,"displayName",name,"signInMethod","cognito");
    });}
    public void changeCredentials(CognitoIdentity identity,String proof,Runnable providerChange){
        // Commit a fail-closed intent before crossing the provider/database boundary.
        // A process crash or failed completion commit leaves all account access blocked.
        locked(identity,a->{current(a,identity,proof,false);jdbc.update("UPDATE cloud_accounts SET credential_change_pending=true WHERE account_id=?",a.id);return null;});
        try {providerChange.run();}
        catch(AccountFailure rejected){
            boolean knownRejection=Set.of("INVALID_CURRENT_PASSWORD","INVALID_PASSWORD").contains(rejected.code());
            finishCredentialChange(identity,!knownRejection);throw rejected;
        }
        catch(RuntimeException uncertain){finishCredentialChange(identity,true);throw uncertain;}
        finishCredentialChange(identity,true);
    }
    private void finishCredentialChange(CognitoIdentity identity,boolean revoke){locked(identity,true,a->{
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
        return locked(identity,false,action);
    }
    private <T>T locked(CognitoIdentity identity,boolean allowCredentialChange,Function<Account,T> action){
        return transaction.execute(status->{
            var rows=jdbc.query("SELECT * FROM cloud_accounts WHERE issuer=? AND subject=? FOR UPDATE",(r,i)->{
                if(!r.getBoolean("enabled")||!r.getBoolean("admitted"))throw denied();
                if(!allowCredentialChange&&r.getBoolean("credential_change_pending"))throw failure(503,"CREDENTIAL_CHANGE_PENDING");
                return new Account(r.getObject("account_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getBoolean("author_access"));
            },identity.issuer(),identity.subject());
            if(rows.size()!=1)throw failure(403,"ACCOUNT_ADMISSION_REQUIRED");return action.apply(rows.getFirst());
        });
    }
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
    private static AccountFailure invalid(){return failure(400,"SIGN_IN_CHALLENGE_INVALID");}
}
