package com.lookahead.domain.access;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.lookahead.domain.access.StaffAccessRepository.Assignment;

@Service
public class StaffAccessService {
    public record Change(UUID id, UUID accountId, String pendingEmail, Set<String> roles,
            Set<String> topicGrants, boolean fullCurriculum, long revision) {}
    private final StaffAccessRepository repository;
    private final LocalAuthorAccess owner;
    private final SnapshotValidator catalog;
    public StaffAccessService(StaffAccessRepository repository, LocalAuthorAccess owner, SnapshotValidator catalog) {
        this.repository=repository;this.owner=owner;this.catalog=catalog;
    }
    public StaffPermissions permissions(AccountPrincipal actor) {
        requireIdentity(actor);
        var assignment=repository.forAccount(actor.accountId());
        return StaffPermissions.forRoles(owner.allowed(actor),assignment==null?Set.of():assignment.roles());
    }
    public void requireOwner(AccountPrincipal actor) {
        requireIdentity(actor);
        if(!owner.allowed(actor))throw new AccountFailure(403,"OWNER_REQUIRED","Only the owner can manage access.");
    }
    public void requireAccessReader(AccountPrincipal actor) {
        var current = permissions(actor);
        if (!current.manageAccess() && !"co-author".equals(current.feedbackRole()))
            throw new AccountFailure(403, "ACCESS_READER_REQUIRED", "Owner or co-author required.");
    }
    public List<Assignment> list(AccountPrincipal actor) { requireAccessReader(actor);return repository.list(); }
    public Set<String> catalog(AccountPrincipal actor) { requireAccessReader(actor);return catalog.allTopicIds(); }

    @Transactional
    public Assignment save(AccountPrincipal actor, Change change) {
        requireOwner(actor);
        if(change==null||change.id()==null||change.roles()==null||!StaffPermissions.ROLES.containsAll(change.roles())
                ||change.topicGrants()==null||!catalog.allTopicIds().containsAll(change.topicGrants())||change.revision()<0)
            throw invalid();
        String email=change.pendingEmail()==null?null:normalizeEmail(change.pendingEmail());
        if((change.accountId()==null)==(email==null))throw invalid();
        if(actor.accountId().equals(change.accountId())||LocalAuthorAccess.ACCOUNT_ID.equals(change.accountId()))
            throw new AccountFailure(422,"OWNER_ACCESS_FIXED","Owner access cannot be changed on this page.");
        var old=repository.lock(change.id());
        if((old==null&&change.revision()!=0)||(old!=null&&old.revision()!=change.revision()))throw conflict();
        // A saved assignment cannot be moved to a different person.
        if(old!=null && (!java.util.Objects.equals(old.accountId(),change.accountId())||!java.util.Objects.equals(old.pendingEmail(),email)))throw invalid();
        var next=new Assignment(change.id(),change.accountId(),email,Set.copyOf(change.roles()),Set.copyOf(change.topicGrants()),change.fullCurriculum(),change.revision()+1);
        repository.mutationActor(actor.accountId());
        try { if(old==null)repository.insert(next);else repository.update(next); }
        catch(DuplicateKeyException duplicate){throw conflict();}
        repository.event(actor.accountId(),"assign",old,next);
        return next;
    }

    /** Only an authoritative provider-verified address may claim an owner-created invitation. */
    @Transactional
    public void claimVerified(AccountPrincipal actor, String verifiedEmail) {
        requireIdentity(actor);
        if(verifiedEmail==null||owner.allowed(actor))return;
        String email=normalizeEmail(verifiedEmail);
        var pending=repository.pending(email);
        if(pending==null)return;
        if(repository.forAccount(actor.accountId())!=null)throw conflict();
        var next=new Assignment(pending.id(),actor.accountId(),null,pending.roles(),pending.topicGrants(),pending.fullCurriculum(),pending.revision()+1);
        repository.mutationActor(actor.accountId());
        repository.update(next);repository.event(actor.accountId(),"claim",pending,next);
    }
    public static String normalizeEmail(String email) {
        String clean=email.strip().toLowerCase(Locale.ROOT);
        if(clean.length()>254||!clean.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}")
                ||clean.contains("..")||clean.startsWith(".")||clean.contains(".@"))throw invalid();
        return clean;
    }
    private static void requireIdentity(AccountPrincipal actor) { if(actor==null||!actor.isEnabled())throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue."); }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_ACCESS_ASSIGNMENT","Check the recipient, roles and content selection.");}
    private static AccountFailure conflict(){return new AccountFailure(409,"ACCESS_ASSIGNMENT_CONFLICT","Reload the current access assignment before saving.");}
}
