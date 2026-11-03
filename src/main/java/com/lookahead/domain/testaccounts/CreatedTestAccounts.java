package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.StaffAccessRepository;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A pending reservation precedes Identity; only a completed Domain record may be activated.
 * Cloud mode (DLV-969, DEV): the Cognito operator provisions the user and the completing transaction also
 * writes the admitted cloud_accounts row and its 'admit' event, so the Waiting list and the audit stay
 * consistent. Delete revokes the account's cloud sign-ins and keeps the admitted row (see
 * {@link CognitoTestIdentityOperator}); the mutation key and the lifetime allowance behave as in Local.
 */
@Service
public class CreatedTestAccounts {
    public record Create(UUID key, String password) {
        @Override public String toString() { return "Create[redacted]"; }
    }
    public record Created(UUID accountId, String username, boolean active) {}
    public record Allowance(int limit, int used, int remaining) {}
    private record Reservation(UUID accountId, String username, String state) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TestAccountPolicy policy;
    private final StaffAccessRepository staff;
    private final LocalAuthorAccess owner;
    private final TestIdentityOperator local;
    private final ObjectProvider<CognitoTestIdentityOperator> cloud;

    public CreatedTestAccounts(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, TestAccountPolicy policy,
            StaffAccessRepository staff, LocalAuthorAccess owner, TestIdentityOperator local,
            ObjectProvider<CognitoTestIdentityOperator> cloud) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(transactionManager);
        this.policy=policy; this.staff=staff; this.owner=owner; this.local=local; this.cloud=cloud;
    }
    private TestIdentity identity() {
        if (!policy.cloud()) return local;
        var operator=cloud.getIfAvailable();
        if (operator==null) throw TestIdentityOperator.unavailable();
        return operator;
    }
    private int limit(AccountPrincipal actor) {
        policy.requireEnabled();
        if (actor == null || !actor.isEnabled()) throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue.");
        if (owner.allowed(actor)) return Integer.MAX_VALUE;
        var assignment=staff.forAccount(actor.accountId());
        Set<String> roles=assignment==null?Set.of():assignment.roles();
        // A dual-role actor gets the lower limit by design.
        if (roles.contains("admin")) return 10;
        if (roles.contains("co-author")) return 20;
        throw new AccountFailure(403,"TEST_ACCOUNT_CREATION_FORBIDDEN","Only owner, co-author or admin may create learner test accounts.");
    }
    public Allowance allowance(AccountPrincipal actor) {
        int cap=limit(actor);
        int used=jdbc.queryForObject("SELECT count(*) FROM test_account_creations WHERE creator_id=? AND state IN ('active','deleted')",Integer.class,actor.accountId());
        return new Allowance(cap,used,cap==Integer.MAX_VALUE?Integer.MAX_VALUE:Math.max(0,cap-used));
    }
    public Created create(AccountPrincipal actor, Create request) {
        if (request==null || request.key()==null || request.password()==null
                || request.password().codePointCount(0,request.password().length())<15
                || request.password().getBytes(StandardCharsets.UTF_8).length>72)
            throw new AccountFailure(422,"INVALID_TEST_ACCOUNT","Provide a request key and a password of 15 to 72 UTF-8 bytes.");
        var identity=identity(); // An unwired cloud mode fails before any reservation holds capacity.
        var reservation=transactions.execute(status -> reserve(actor,request.key()));
        if (reservation==null) throw new IllegalStateException("Reservation did not complete");
        if (!"active".equals(reservation.state())) {
            String subject=identity.provision(reservation.accountId(),reservation.username(),request.password());
            transactions.executeWithoutResult(status -> complete(actor,reservation,subject));
        }
        identity.activate(reservation.accountId(),reservation.username());
        return new Created(reservation.accountId(),reservation.username(),true);
    }
    private Reservation reserve(AccountPrincipal actor, UUID key) {
        int cap=limit(actor);
        jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",rs->{},actor.accountId());
        var prior=jdbc.query("SELECT account_id,username,state FROM test_account_creations WHERE creator_id=? AND mutation_key=?",
                (r,i)->new Reservation(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),actor.accountId(),key);
        if (!prior.isEmpty()) {
            if ("deleted".equals(prior.getFirst().state())) throw new AccountFailure(409,"TEST_ACCOUNT_DELETED","This request already created an account that was deleted.");
            return prior.getFirst();
        }
        // A timed-out reservation may hold capacity briefly, but never becomes a lifetime spend.
        int used=jdbc.queryForObject("""
                SELECT count(*) FROM test_account_creations WHERE creator_id=?
                AND (state IN ('active','deleted') OR (state='pending' AND created_at>clock_timestamp()-interval '15 minutes'))
                """,Integer.class,actor.accountId());
        if (used>=cap) throw new AccountFailure(409,"TEST_ACCOUNT_LIMIT","Lifetime test-account creation limit reached.");
        UUID id=UUID.nameUUIDFromBytes(("lookahead-created-test:"+actor.accountId()+":"+key).getBytes(StandardCharsets.UTF_8));
        String hex=id.toString().replace("-","");
        // Cloud (DEV): the Gateway's sign-in form takes an email, so the pool username is one on the reserved .test
        // domain (no mail is ever sent); 30 characters fits the varchar(32) username columns.
        String username=policy.cloud()?"test-"+hex.substring(0,10)+"@lookahead.test":"test-"+hex.substring(0,24);
        jdbc.update("INSERT INTO test_account_creations(creator_id,mutation_key,account_id,username,state) VALUES (?,?,?,?,'pending')",
                actor.accountId(),key,id,username);
        return new Reservation(id,username,"pending");
    }
    private void complete(AccountPrincipal actor, Reservation reserved, String subject) {
        limit(actor);
        jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",rs->{},actor.accountId());
        var states=jdbc.queryForList("SELECT state FROM test_account_creations WHERE creator_id=? AND account_id=? FOR UPDATE",
                String.class,actor.accountId(),reserved.accountId());
        if (states.isEmpty() || "deleted".equals(states.getFirst())) throw new AccountFailure(409,"TEST_ACCOUNT_DELETED","The account is no longer available.");
        if ("active".equals(states.getFirst())) return;
        int cap=limit(actor);
        int spent=jdbc.queryForObject("SELECT count(*) FROM test_account_creations WHERE creator_id=? AND state IN ('active','deleted')",
                Integer.class,actor.accountId());
        if (spent>=cap) throw new AccountFailure(409,"TEST_ACCOUNT_LIMIT","Lifetime test-account creation limit reached.");
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT DO NOTHING",reserved.accountId());
        jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,creator_id,last_changed_by) VALUES (?,?,'created',?,?)",
                reserved.accountId(),reserved.username(),actor.accountId(),actor.accountId().toString());
        if (policy.cloud()) admit(actor,reserved,subject);
        jdbc.update("UPDATE test_account_creations SET state='active' WHERE creator_id=? AND account_id=?",actor.accountId(),reserved.accountId());
    }
    /** The created learner is admitted by its creator; the row is what sign-in admission looks up by issuer and subject. */
    private void admit(AccountPrincipal actor, Reservation reserved, String subject) {
        if (subject==null || subject.isBlank()) throw TestIdentityOperator.unavailable();
        String issuer=cloud.getObject().issuer();
        jdbc.update("""
                INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,enabled,author_access,username,display_name,requested_at,admitted_at,admitted_by)
                VALUES (?,?,?,true,true,false,?,?,clock_timestamp(),clock_timestamp(),?)
                """,reserved.accountId(),issuer,subject,reserved.username(),reserved.username(),actor.accountId());
        jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles) VALUES (?,?,?,?,?,'admit','[]'::jsonb)",
                UUID.randomUUID(),reserved.accountId(),issuer,subject,actor.accountId());
    }
    public void delete(AccountPrincipal actor, UUID id) {
        var target=transactions.execute(status -> authorizedTarget(actor,id));
        if (target==null) throw new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found.");
        identity().disable(id,target.username());
        transactions.executeWithoutResult(status -> erase(actor,id));
    }
    private Reservation authorizedTarget(AccountPrincipal actor, UUID id) {
        policy.requireEnabled();
        if (id==null) throw new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found.");
        if (actor==null || !actor.isEnabled()) throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue.");
        boolean isOwner=owner.allowed(actor);
        var assignment=staff.forAccount(actor.accountId());
        Set<String> roles=assignment==null?Set.of():assignment.roles();
        boolean coauthor=roles.contains("co-author");
        boolean admin=roles.contains("admin");
        if (!isOwner&&!coauthor&&!admin) throw new AccountFailure(403,"DELETE_FORBIDDEN","Staff access required.");
        var targets=jdbc.query("SELECT username,creator_id,creation_state FROM test_account_registry WHERE account_id=? FOR UPDATE",
                (r,i)->new Object[]{r.getString(1),r.getObject(2,UUID.class),r.getString(3)},id);
        if (targets.isEmpty()) throw new AccountFailure(404,"TEST_ACCOUNT_NOT_FOUND","Managed test account not found.");
        String username=(String)targets.getFirst()[0];
        if (LocalAuthorAccess.ACCOUNT_ID.equals(id))
            throw new AccountFailure(403,"PROTECTED_TEST_ACCOUNT","The Local author account cannot be deleted.");
        var targetAssignment=staff.forAccount(id);
        Set<String> targetRoles=targetAssignment==null?Set.of():targetAssignment.roles();
        String actorRole=isOwner?"owner":coauthor?"co-author":"admin";
        if (!TestAccountDeletionPolicy.allows(actorRole,actor.accountId(),id,targetRoles))
            throw new AccountFailure(403,"DELETE_FORBIDDEN","Your role cannot delete this test account.");
        return new Reservation(id,username,(String)targets.getFirst()[2]);
    }
    private void erase(AccountPrincipal actor, UUID id) {
        var target=authorizedTarget(actor,id);
        if ("deleted".equals(target.state())) return;
        jdbc.query("SELECT set_config('lookahead.actor_id',?,true)",rs->{},actor.accountId().toString());
        // Audit purge is a Local fixture privilege only; cloud mode never passes it (AccountDataErasure contract).
        new AccountDataErasure(jdbc).erase(id, policy.local());
        // Cloud: the Cognito user is gone; existing sessions end here. The admitted row stays (V17).
        if (policy.cloud()) jdbc.update("UPDATE cloud_sign_ins SET revoked_at=clock_timestamp() WHERE account_id=? AND revoked_at IS NULL",id);
        jdbc.update("UPDATE test_account_creations SET state='deleted' WHERE account_id=?",id);
        jdbc.update("UPDATE test_account_registry SET creation_state='deleted',revision=revision+1,last_changed_by=?,last_changed_at=clock_timestamp() WHERE account_id=?",
                actor.accountId().toString(),id);
    }
}
