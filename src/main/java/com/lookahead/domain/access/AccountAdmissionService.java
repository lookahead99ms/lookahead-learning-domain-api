package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.CloudAdmissionCleanup;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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
    /** The account to admit as read for update; {@code state} is the V18 state ("disabled"/"removed") or null. */
    public record Row(UUID accountId,String username,String displayName,String issuer,String subject,boolean admitted,String state) {}
    private final AdmissionStore store;
    private final StaffAccessService access;
    private final StaffAccessRepository staff;
    public AccountAdmissionService(JdbcTemplate jdbc,StaffAccessService access,StaffAccessRepository staff,ObjectMapper mapper){this(new JdbcAdmissionStore(jdbc,mapper,null),access,staff);}
    /** DLV-970: the store (PostgreSQL or DynamoDB) is selected by {@code app.persistence.store}. */
    @org.springframework.beans.factory.annotation.Autowired
    public AccountAdmissionService(AdmissionStore store,StaffAccessService access,StaffAccessRepository staff){this.store=store;this.access=access;this.staff=staff;}

    public WaitingList waiting(AccountPrincipal actor) {
        access.requireOwner(actor);
        // DLV-969 S36: a waiting row whose Gmail two or more accounts share was not linked; the owner sees how many.
        var rows=store.waiting();
        return new WaitingList(rows,CloudAdmissionCleanup.WAITING_EXPIRY.toHours());
    }

    public Admitted admit(AccountPrincipal actor,Request request) {
        try {
            var admitted=store.transaction(()->admitChecked(actor,request));
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
        Row row=store.lock(accountId);
        if(row==null)throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","No account with that id is waiting.");
        if(row.admitted())throw new AccountFailure(409,"ACCOUNT_ALREADY_ADMITTED","This account was already admitted.");
        // DLV-969 S36: never admit an account that was disabled or deleted (read under the row lock, on its own).
        if(row.state()!=null)throw new AccountFailure(409,"ACCOUNT_REMOVED","This account was disabled or deleted and cannot be admitted.");
        Instant admittedAt=store.admit(actor.accountId(),row,roles,()->{
            if(!roles.isEmpty()){
                var existing=staff.forAccount(accountId);
                access.save(actor,existing==null
                    ? new StaffAccessService.Change(UUID.randomUUID(),accountId,null,Set.copyOf(roles),Set.of(),false,0)
                    : new StaffAccessService.Change(existing.id(),accountId,null,Set.copyOf(roles),existing.topicGrants(),existing.fullCurriculum(),existing.revision()));
            }
        });
        return new Admitted(accountId,row.username,row.displayName,admittedAt,roles);
    }
    private static UUID accountId(String value) {
        try {if(value==null)throw invalid();UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}
        catch(IllegalArgumentException malformed){throw invalid();}
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_ADMISSION","Check the account id and roles.");}
}
