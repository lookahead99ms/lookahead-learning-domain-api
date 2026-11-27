package com.lookahead.domain.service.access;

import com.lookahead.domain.dto.DevAccessAdmission;
import com.lookahead.domain.dto.StaffAccessChange;
import com.lookahead.domain.model.DevAccessModels.*;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.db.store.access.DevAccess;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.util.SignInRules;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.util.CanonicalUuid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** DLV-969: the owner admits accounts that signed in but have no access yet. Roles go through the staff assignment. */
@Service
public class DevAccessService {
    public record WaitingList(List<Waiting> waiting,long expiryHours) {}
    public record Admitted(UUID accountId,String username,String displayName,Instant admittedAt,List<String> roles) {}
    private final DevAccess store;
    private final StaffAccessService access;
    private final StaffAccess staff;
    /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
    @Autowired
    public DevAccessService(DevAccess store,StaffAccessService access,StaffAccess staff){this.store=store;this.access=access;this.staff=staff;}

    public WaitingList waiting(AccountPrincipal actor) {
        access.requireOwner(actor);
        // DLV-969 S36: a waiting row whose Gmail two or more accounts share was not linked; the owner sees how many.
        var rows=store.waiting();
        return new WaitingList(rows,SignInRules.WAITING_EXPIRY.toHours());
    }

    public Admitted admit(AccountPrincipal actor,DevAccessAdmission request) {
        try {
            var admitted=admitChecked(actor,request);
            EventLog.afterCommit(()->EventLog.success(EventLog.EVENT,"admission.admitted").addKeyValue("account.id",actor.accountId())
                .addKeyValue("target.id",admitted.accountId()).addKeyValue("roles",admitted.roles())
                .log("Owner admitted account {} with roles {}",admitted.accountId(),admitted.roles().isEmpty()?"none (learner)":admitted.roles()));
            return admitted;
        } catch(AccountFailure refused) {
            EventLog.refused(EventLog.EVENT,"admission.admit",refused.code()).addKeyValue("account.id",actor==null?null:actor.accountId())
                .log("Admission refused: {}",refused.code());
            throw refused;
        }
    }
    private Admitted admitChecked(AccountPrincipal actor,DevAccessAdmission request) {
        access.requireOwner(actor);
        if(request==null||request.roles()==null||!StaffPermissions.ROLES.containsAll(request.roles()))throw invalid();
        UUID accountId=accountId(request.accountId());
        var roles=List.copyOf(new TreeSet<>(request.roles()));
        Row row=store.lock(accountId);
        if(row==null)throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","No account with that id is waiting.");
        if(row.admitted())throw new AccountFailure(409,"ACCOUNT_ALREADY_ADMITTED","This account was already admitted.");
        // DLV-969 S36: never admit an account that was disabled or deleted (read under the row lock, on its own).
        if(row.state()!=null)throw new AccountFailure(409,"ACCOUNT_REMOVED","This account was disabled or deleted and cannot be admitted.");
        // DLV-1016: the store reads and writes; refusing a vanished account and assigning the roles in between is this service's part.
        var prepared=store.prepare(row);
        if(prepared==null)throw new AccountFailure(404,"ACCOUNT_NOT_FOUND","No account with that id is waiting.");
        // Roles first: a staff assignment on a still-waiting account is harmless, an admitted account without its roles is not.
        if(!roles.isEmpty()){
            var existing=staff.forAccount(accountId);
            access.save(actor,existing==null
                ? new StaffAccessChange(UUID.randomUUID(),accountId,null,Set.copyOf(roles),Set.of(),false,0)
                : new StaffAccessChange(existing.id(),accountId,null,Set.copyOf(roles),existing.topicGrants(),existing.fullCurriculum(),existing.revision()));
        }
        Instant admittedAt=store.admit(actor.accountId(),prepared,roles);
        return new Admitted(accountId,row.username(),row.displayName(),admittedAt,roles);
    }
    private static UUID accountId(String value) {
        UUID id=CanonicalUuid.parse(value);
        if(id==null)throw invalid();
        return id;
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_ADMISSION","Check the account id and roles.");}
}
