package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.StaffAccessRepository;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A pending reservation precedes Identity; only a completed Domain record may be activated.
 * Cloud mode (DLV-969 S35, DEV): no identity provider and no password. The completing transaction writes the admitted
 * cloud_accounts row on the test issuer and its 'admit' event, then the chosen scenario; staff use the account through
 * a test session. Delete keeps the admitted row (V17); the mutation key and the lifetime allowance behave as in Local.
 */
@Service
public class CreatedTestAccounts {
    /**
     * {@code scenario}: DLV-969 S35, a {@link TestAccountTemplates} id the account starts from (none: a learner without
     * access, as before). {@code roster}: the owner's DEV roster ({@link DevTestRoster}) through the same route.
     * {@code password}: Local only.
     */
    public record Create(UUID key, String password, String scenario, boolean roster) {
        public Create(UUID key, String password) { this(key, password, null, false); }
        @Override public String toString() { return "Create[redacted]"; }
    }
    public record Created(UUID accountId, String username, boolean active) {}
    /** {@code roster}: the DEV roster's progress, for the owner only; {@code scenarios}: what this creator may choose. */
    public record Allowance(int limit, int used, int remaining, DevTestRoster.Status roster, List<TestAccountTemplates.Template> scenarios) {
        public Allowance(int limit, int used, int remaining) { this(limit, used, remaining, null, null); }
        Allowance with(DevTestRoster.Status status) { return new Allowance(limit, used, remaining, status, scenarios); }
    }
    /** DEV test accounts have no sign-in of their own (DLV-969 S35): nothing to provision, activate or disable. */
    private static final TestIdentity NO_SIGN_IN = new TestIdentity() {
        @Override public String provision(UUID accountId, String username, String password) { return accountId.toString(); }
        @Override public void activate(UUID accountId, String username) {}
        @Override public void disable(UUID accountId, String username) {}
    };
    private record Reservation(UUID accountId, String username, String state) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TestAccountPolicy policy;
    private final StaffAccessRepository staff;
    private final LocalAuthorAccess owner;
    private final TestIdentityOperator local;
    private TestAccountsService accounts;

    public CreatedTestAccounts(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, TestAccountPolicy policy,
            StaffAccessRepository staff, LocalAuthorAccess owner, TestIdentityOperator local) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(transactionManager);
        this.policy=policy; this.staff=staff; this.owner=owner; this.local=local;
    }
    /** Writes the scenarios and the DEV admitted row (DLV-969 S35). */
    @org.springframework.beans.factory.annotation.Autowired
    public void configureAccounts(TestAccountsService accounts) { this.accounts=accounts; }
    private TestIdentity identity() { return policy.cloud() ? NO_SIGN_IN : local; }
    /** The creator's role for scenario limits; a dual-role actor gets the lower one, as for the allowance. */
    private String role(AccountPrincipal actor) {
        if (owner.allowed(actor)) return "owner";
        var assignment=staff.forAccount(actor.accountId());
        Set<String> roles=assignment==null?Set.of():assignment.roles();
        return roles.contains("admin") ? "admin" : roles.contains("co-author") ? "co-author" : "";
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
        String role=role(actor);
        var scenarios=TestAccountTemplates.all().stream().filter(t->TestAccountTemplates.allows(role,Set.copyOf(t.roles()))).toList();
        return new Allowance(cap,used,cap==Integer.MAX_VALUE?Integer.MAX_VALUE:Math.max(0,cap-used),null,scenarios);
    }
    public Created create(AccountPrincipal actor, Create request) {
        boolean cloud=policy.cloud();
        if (request==null || request.key()==null || !cloud && (request.password()==null
                || request.password().codePointCount(0,request.password().length())<15
                || request.password().getBytes(StandardCharsets.UTF_8).length>72))
            throw new AccountFailure(422,"INVALID_TEST_ACCOUNT",cloud?"Provide a request key.":"Provide a request key and a password of 15 to 72 UTF-8 bytes.");
        String scenario=request.scenario();
        if (scenario!=null) {
            if (!TestAccountTemplates.known(scenario)) throw new AccountFailure(422,"UNKNOWN_SCENARIO","Choose one of the listed scenarios.");
            limit(actor);
            if (!TestAccountTemplates.allows(role(actor),TestAccountTemplates.roles(scenario)))
                throw new AccountFailure(403,"SCENARIO_FORBIDDEN","Your role cannot create a test account with this role.");
        }
        var identity=identity();
        var reservation=transactions.execute(status -> reserve(actor,request.key()));
        if (reservation==null) throw new IllegalStateException("Reservation did not complete");
        if (!"active".equals(reservation.state())) {
            String subject=identity.provision(reservation.accountId(),reservation.username(),cloud?null:request.password());
            transactions.executeWithoutResult(status -> complete(actor,reservation,subject,scenario));
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
        // DEV (DLV-969 S35): an address on the owner's domain, like the roster; it never signs in. 29 characters fits
        // the varchar(32) username columns.
        String username=policy.cloud()?"test-"+hex.substring(0,10)+"@"+TestAccountRoster.CLOUD_DOMAIN:"test-"+hex.substring(0,24);
        jdbc.update("INSERT INTO test_account_creations(creator_id,mutation_key,account_id,username,state) VALUES (?,?,?,?,'pending')",
                actor.accountId(),key,id,username);
        return new Reservation(id,username,"pending");
    }
    private void complete(AccountPrincipal actor, Reservation reserved, String subject, String scenario) {
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
        // DEV: admitted by its creator on the test issuer, so every account feature finds its row (DLV-969 S35).
        if (policy.cloud()) accounts.admitTestAccount(reserved.accountId(),reserved.username(),reserved.username(),actor.accountId());
        if (scenario!=null) accounts.applyCreated(reserved.accountId(),scenario,actor.accountId());
        jdbc.update("UPDATE test_account_creations SET state='active' WHERE creator_id=? AND account_id=?",actor.accountId(),reserved.accountId());
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
        if (policy.cloud()) {
            if (TestAccountRoster.isRoster(id))
                throw new AccountFailure(403,"ROSTER_ACCOUNT_PERMANENT","Roster accounts are never deleted. Reset puts one back to its scenario.");
            if (!TestAccountDeletionPolicy.allowsInDev(isOwner,actor.accountId(),id,(UUID)targets.getFirst()[1]))
                throw new AccountFailure(403,"DELETE_FORBIDDEN","Only the owner or the person who created this test account can delete it.");
            return new Reservation(id,username,(String)targets.getFirst()[2]);
        }
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
        // Cloud: a deleted test account leaves every test-session list and check at once. The admitted row stays (V17).
        jdbc.update("UPDATE test_account_creations SET state='deleted' WHERE account_id=?",id);
        jdbc.update("UPDATE test_account_registry SET creation_state='deleted',revision=revision+1,last_changed_by=?,last_changed_at=clock_timestamp() WHERE account_id=?",
                actor.accountId().toString(),id);
    }
}
