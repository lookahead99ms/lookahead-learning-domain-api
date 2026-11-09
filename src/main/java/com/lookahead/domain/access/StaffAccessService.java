package com.lookahead.domain.access;

import com.lookahead.domain.Events;
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
        if(!owner.allowed(actor)){
            Events.refused(Events.EVENT,"access.owner-check","OWNER_REQUIRED").addKeyValue("account.id",actor.accountId())
                .log("Owner-only action refused for account {}: not the owner",actor.accountId());
            throw new AccountFailure(403,"OWNER_REQUIRED","Only the owner can manage access.");
        }
    }
    public void requireAccessReader(AccountPrincipal actor) {
        var current = permissions(actor);
        if (!current.manageAccess() && !"co-author".equals(current.feedbackRole()))
            throw new AccountFailure(403, "ACCESS_READER_REQUIRED", "Owner or co-author required.");
    }
    /**
     * DLV-969 (owner, 2026-11-04): the People rows the reader may see, by the one hierarchy
     * ({@link com.lookahead.domain.testaccounts.TestAccountTemplates#allows}): the owner sees every row, a co-author
     * never the owner's, an admin only admin and learner rows. Who may open the list is unchanged.
     */
    public List<Assignment> list(AccountPrincipal actor) {
        requireAccessReader(actor);
        var rows=repository.list();
        String role=com.lookahead.domain.testaccounts.TestAccountTemplates.role(permissions(actor));
        if("owner".equals(role))return rows;
        var owners=repository.owners();
        return rows.stream().filter(row->com.lookahead.domain.testaccounts.TestAccountTemplates.allows(role,
            row.accountId()!=null&&(owners.contains(row.accountId())||LocalAuthorAccess.ACCOUNT_ID.equals(row.accountId()))?Set.of("owner"):row.roles())).toList();
    }
    public Set<String> catalog(AccountPrincipal actor) { requireAccessReader(actor);return catalog.allTopicIds(); }

    @Transactional
    public Assignment save(AccountPrincipal actor, Change change) {
        requireOwner(actor);
        try { return saveChecked(actor, change); }
        catch(AccountFailure refused) {
            Events.refused(Events.EVENT,"access.save",refused.code()).addKeyValue("account.id",actor.accountId())
                .addKeyValue("target.id",change==null?null:change.accountId())
                .log("Access change refused for {}: {}",change==null||change.accountId()==null?"a pending invitation":"account "+change.accountId(),refused.code());
            throw refused;
        }
    }
    private Assignment saveChecked(AccountPrincipal actor, Change change) {
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
        Set<String> before=old==null?Set.of():old.roles();
        Events.afterCommit(()->Events.success(Events.EVENT,next.accountId()==null?"access.invitation-saved":"access.saved")
            .addKeyValue("account.id",actor.accountId()).addKeyValue("target.id",next.accountId()==null?next.id():next.accountId())
            .addKeyValue("roles",new java.util.TreeSet<>(next.roles())).addKeyValue("roles.before",new java.util.TreeSet<>(before))
            .log("Owner saved access for {}: roles {} -> {}, {} topic grant(s), full curriculum {}",
                next.accountId()==null?"pending invitation "+next.id():"account "+next.accountId(),
                new java.util.TreeSet<>(before),new java.util.TreeSet<>(next.roles()),next.topicGrants().size(),next.fullCurriculum()?"on":"off"));
        return next;
    }

    /** Only an authoritative provider-verified address may claim an owner-created invitation. */
    @Transactional
    public void claimVerified(AccountPrincipal actor, String verifiedEmail) {
        requireIdentity(actor);
        if(verifiedEmail==null||owner.allowed(actor))return;
        claim(actor.accountId(),normalizeEmail(verifiedEmail),true);
    }
    /**
     * DLV-971 (owner, 2026-11-07): prepared access by email is a pass through the DEV gate. The sign-in registry calls
     * this inside its first-contact transaction for the account it has just recorded; the claimed assignment, or null
     * when nothing is prepared for that address, decides whether the account is admitted at once. Never a conflict: a
     * brand-new account holds nothing, and an older one that does is simply left alone.
     */
    public Assignment claimPrepared(UUID accountId, String verifiedEmail) {
        if(accountId==null||verifiedEmail==null)return null;
        return claim(accountId,normalizeEmail(verifiedEmail),false);
    }
    private Assignment claim(UUID accountId, String email, boolean conflictWhenAssigned) {
        var pending=repository.pending(email);
        if(pending==null)return null;
        if(repository.forAccount(accountId)!=null){if(conflictWhenAssigned)throw conflict();return null;}
        var next=new Assignment(pending.id(),accountId,null,pending.roles(),pending.topicGrants(),pending.fullCurriculum(),pending.revision()+1);
        repository.mutationActor(accountId);
        repository.update(next);repository.event(accountId,"claim",pending,next);
        Events.afterCommit(()->Events.success(Events.EVENT,"access.invitation-claimed").addKeyValue("account.id",accountId)
            .addKeyValue("target.id",pending.id()).addKeyValue("roles",new java.util.TreeSet<>(next.roles()))
            .log("Account {} claimed pending access invitation {}: roles {}",accountId,pending.id(),new java.util.TreeSet<>(next.roles())));
        return next;
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
