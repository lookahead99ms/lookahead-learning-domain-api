package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.domain.cloud.CloudIdentities;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** DLV-969: the owner admits accounts that signed in but have no access yet. Roles go through the staff assignment. */
@Service
public class AccountAdmissionService {
    /**
     * sameEmailAccounts (DLV-969 S36, owner-only like the email): other accounts, not deleted or test, with the same
     * verified email. signInMethods: each sign-in of the account, oldest first, as {provider, email?}.
     */
    public record Waiting(UUID accountId,String username,String displayName,String email,Instant requestedAt,Instant expiresAt,int sameEmailAccounts,
            List<java.util.Map<String,Object>> signInMethods) {
        public Waiting(UUID accountId,String username,String displayName,String email,Instant requestedAt,Instant expiresAt){this(accountId,username,displayName,email,requestedAt,expiresAt,0);}
        public Waiting(UUID accountId,String username,String displayName,String email,Instant requestedAt,Instant expiresAt,int sameEmailAccounts){
            this(accountId,username,displayName,email,requestedAt,expiresAt,sameEmailAccounts,List.of());
        }
    }
    public record WaitingList(List<Waiting> waiting,long expiryHours) {}
    public record Request(String accountId,List<String> roles) {}
    public record Admitted(UUID accountId,String username,String displayName,Instant admittedAt,List<String> roles) {}
    private record Row(UUID accountId,String username,String displayName,String issuer,String subject,boolean admitted) {}
    private final JdbcTemplate jdbc;
    private final StaffAccessService access;
    private final StaffAccessRepository staff;
    private final ObjectMapper mapper;
    public AccountAdmissionService(JdbcTemplate jdbc,StaffAccessService access,StaffAccessRepository staff,ObjectMapper mapper){this.jdbc=jdbc;this.access=access;this.staff=staff;this.mapper=mapper;}

    public WaitingList waiting(AccountPrincipal actor) {
        access.requireOwner(actor);
        // DLV-969 S36: a waiting row whose Gmail two or more accounts share was not linked; the owner sees how many.
        var rows=jdbc.query("SELECT w.account_id,w.username,w.display_name,w.verified_email,w.requested_at,"+CloudIdentities.sameEmailAccounts("w.verified_email","w.account_id")
            +" AS same_email_accounts FROM cloud_accounts w WHERE NOT admitted ORDER BY requested_at,account_id",(r,i)->{
            Instant requested=r.getTimestamp("requested_at").toInstant();
            return new Waiting(r.getObject("account_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getString("verified_email"),requested,requested.plus(CloudAdmissionCleanup.WAITING_EXPIRY),
                r.getInt("same_email_accounts"));
        });
        rows=rows.stream().map(w->new Waiting(w.accountId(),w.username(),w.displayName(),w.email(),w.requestedAt(),w.expiresAt(),w.sameEmailAccounts(),
            CloudIdentities.signInMethods(jdbc,w.accountId()))).toList();
        return new WaitingList(rows,CloudAdmissionCleanup.WAITING_EXPIRY.toHours());
    }

    @Transactional
    public Admitted admit(AccountPrincipal actor,Request request) {
        try {
            var admitted=admitChecked(actor,request);
            Events.afterCommit(()->Events.success(Events.EVENT,"admission.admitted").addKeyValue("account.id",actor.accountId())
                .addKeyValue("target.id",admitted.accountId()).addKeyValue("roles",admitted.roles())
                .log("Owner admitted account {} with roles {}",admitted.accountId(),admitted.roles().isEmpty()?"none (learner)":admitted.roles()));
            return admitted;
        } catch(AccountFailure refused) {
            Events.refused(Events.EVENT,"admission.admit",refused.code()).addKeyValue("account.id",actor==null?null:actor.accountId())
                .log("Admission refused: {}",refused.code());
            throw refused;
        }
    }
    private Admitted admitChecked(AccountPrincipal actor,Request request) {
        access.requireOwner(actor);
        if(request==null||request.roles()==null||!StaffPermissions.ROLES.containsAll(request.roles()))throw invalid();
        UUID accountId=accountId(request.accountId());
        var roles=List.copyOf(new TreeSet<>(request.roles()));
        var rows=jdbc.query("SELECT account_id,username,display_name,issuer,subject,admitted FROM cloud_accounts WHERE account_id=? FOR UPDATE",
            (r,i)->new Row(r.getObject("account_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getString("issuer"),r.getString("subject"),r.getBoolean("admitted")),accountId);
        if(rows.isEmpty())throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","No account with that id is waiting.");
        Row row=rows.getFirst();
        if(row.admitted)throw new AccountFailure(409,"ACCOUNT_ALREADY_ADMITTED","This account was already admitted.");
        // DLV-969 S36: never admit an account that was disabled or deleted (read under the row lock, on its own).
        var state=jdbc.queryForList("SELECT state FROM cloud_account_states WHERE account_id=?",String.class,accountId);
        if(state!=null&&!state.isEmpty())throw new AccountFailure(409,"ACCOUNT_REMOVED","This account was disabled or deleted and cannot be admitted.");
        Instant admittedAt=jdbc.queryForObject("UPDATE cloud_accounts SET admitted=true,admitted_at=clock_timestamp(),admitted_by=? WHERE account_id=? RETURNING admitted_at",Timestamp.class,actor.accountId(),accountId).toInstant();
        if(!roles.isEmpty()){
            var existing=staff.forAccount(accountId);
            access.save(actor,existing==null
                ? new StaffAccessService.Change(UUID.randomUUID(),accountId,null,Set.copyOf(roles),Set.of(),false,0)
                : new StaffAccessService.Change(existing.id(),accountId,null,Set.copyOf(roles),existing.topicGrants(),existing.fullCurriculum(),existing.revision()));
        }
        jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES(?,?,?,?,?,'admit',?::jsonb)",
            UUID.randomUUID(),accountId,row.issuer,row.subject,actor.accountId(),mapper.writeValueAsString(roles));
        return new Admitted(accountId,row.username,row.displayName,admittedAt,roles);
    }
    private static UUID accountId(String value) {
        try {if(value==null)throw invalid();UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}
        catch(IllegalArgumentException malformed){throw invalid();}
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_ADMISSION","Check the account id and roles.");}
}
