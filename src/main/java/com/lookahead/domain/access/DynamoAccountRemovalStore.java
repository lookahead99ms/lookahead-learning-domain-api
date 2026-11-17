package com.lookahead.domain.access;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.Events;
import com.lookahead.domain.access.AccountRemovalService.Changed;
import com.lookahead.domain.access.AccountRemovalService.Target;
import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Control;
import com.lookahead.domain.persistence.dynamodb.DynamoAccountControlStore;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: Disable, Enable and Delete on DynamoDB. An admitted account goes through the
 * {@link DynamoAccountControlStore} library (revision- and generation-fenced {@code CONTROL} transition, durable
 * command receipt, insert-only admission event, terminal {@code REMOVED} marker and the erasure job); a waiting
 * account (DLV-971 Reject), which that library refuses, is handled here with the same item shapes: Disable/Enable
 * change its state under the control revision, Delete removes it the way the 48-hour cleanup does. Sign-ins end
 * through {@code authGeneration}; erasure runs after the state change and is resumable ({@link DynamoAccountErasure}).
 */
@Repository
public class DynamoAccountRemovalStore implements AccountRemovalStore {
    private final DynamoCloudAccounts accounts;
    private final DynamoAccountControlStore controls;
    private final DynamoAccountErasure erasure;
    private final ObjectProvider<TestAccountRegistryLookup> testAccounts;

    @Autowired
    public DynamoAccountRemovalStore(DynamoDbClient client, DynamoTableNames tables, StaffAccessRepository staff, ObjectProvider<TestAccountRegistryLookup> testAccounts,
            ObjectProvider<LearningDataErasure> plans, ObjectProvider<MembershipErasure> memberships) {
        this(client, tables, staff, testAccounts, plans, memberships, Clock.systemUTC());
    }
    public DynamoAccountRemovalStore(DynamoDbClient client, DynamoTableNames tables, StaffAccessRepository staff, ObjectProvider<TestAccountRegistryLookup> testAccounts,
            ObjectProvider<LearningDataErasure> plans, ObjectProvider<MembershipErasure> memberships, Clock clock) {
        this.accounts = new DynamoCloudAccounts(client, tables, clock);
        this.controls = new DynamoAccountControlStore(client, tables);
        this.erasure = new DynamoAccountErasure(accounts, staff, plans, memberships);
        this.testAccounts = testAccounts;
    }
    public DynamoAccountErasure erasure() { return erasure; }

    @Override public Target options(UUID id) {
        var control = accounts.control(id);
        return control == null ? null : new Target(control.issuer(), control.subject(), control.username(), control.admitted(), control.author(), control.sqlState(), List.of());
    }

    @Override public Changed disable(UUID actor, UUID id) {
        var c = checked(id); var target = target(c);
        if (c.removed()) throw AccountRemovalService.removed();
        if (c.disabled()) return new Changed(target, false);
        if (c.admitted()) transition(actor, c, DynamoAccountControlStore.Action.DISABLE, "DISABLED");
        else waitingState(actor, c, "DISABLED", c.authGeneration() + 1, "disable");
        return new Changed(target, true);
    }
    @Override public Changed enable(UUID actor, UUID id) {
        var c = checked(id); var target = target(c);
        if (c.removed()) throw AccountRemovalService.removed();
        if (c.active()) return new Changed(target, false);
        if (c.admitted()) transition(actor, c, DynamoAccountControlStore.Action.ENABLE, "ACTIVE");
        else waitingState(actor, c, "ACTIVE", c.authGeneration(), "enable");
        return new Changed(target, true);
    }
    @Override public Changed remove(UUID actor, UUID id) {
        var c = checked(id); var target = target(c);
        if (c.removed()) {
            // Already deleted: a retry finishes an interrupted erasure, then repeats only the pool calls.
            if (erasure.pending(id)) erasure.erase(actor, id);
            return new Changed(target, false);
        }
        if (!c.admitted()) {
            // DLV-971: a waiting account has no learning data and no permanent record to keep; it goes the way an
            // expired request goes, so the same person signing in again appears as a new request.
            var tx = accounts.transaction();
            accounts.admission(tx, id, c.issuer(), c.subject(), actor, "remove", List.of(), accounts.now());
            accounts.deleteWaiting(tx, c, accounts.identities(id));
            tx.commit();
            return new Changed(target, true);
        }
        erasure.queue(actor, id);
        transition(actor, c, DynamoAccountControlStore.Action.REMOVE, "REMOVED");
        erasure.erase(actor, id);
        return new Changed(target, true);
    }
    @Override public void revokeSignIns(UUID id) {
        var c = accounts.control(id);
        if (c == null) return;
        var tx = accounts.transaction();
        accounts.control(tx, c, Map.of("authGeneration", n(c.authGeneration() + 1)), null);
        tx.commit();
    }
    @Override public void erase(UUID actor, UUID id) { erasure.erase(actor, id); }

    /** The checks the SQL made under the row lock: the account exists, is no author account and no managed test account. */
    private Control checked(UUID id) {
        var c = accounts.control(id);
        if (c == null) throw AccountRemovalService.notSignIn();
        if (c.author()) throw new AccountFailure(403, "OWNER_ACCOUNT_PROTECTED", "An author account cannot be changed here.");
        var registry = testAccounts.getIfAvailable();
        if (c.managedTest() || (registry != null && registry.isTestAccount(id))) throw new AccountFailure(403, "TEST_ACCOUNT_ROUTE_REQUIRED", "Use the Test accounts page for managed accounts.");
        return c;
    }
    /** The account's own username stands in only while its first identity has no binding anywhere (an account from before V19); once a merge moved that binding elsewhere, only the identities decide. */
    private Target target(Control c) {
        var usernames = accounts.usernames(c.id());
        boolean ownMissing = usernames.isEmpty() && accounts.binding(c.issuer(), c.subject()).isEmpty();
        return new Target(c.issuer(), c.subject(), c.username(), c.admitted(), c.author(), c.sqlState(), ownMissing ? List.of(c.username()) : usernames);
    }
    /** An admitted account changes state through the control library, acting as the owner's own identity. */
    private void transition(UUID actor, Control target, DynamoAccountControlStore.Action action, String desired) {
        var owner = accounts.control(actor);
        if (owner == null) throw unavailable("OWNER_IDENTITY_UNKNOWN");
        var command = new DynamoAccountControlStore.Command(new DynamoAccountControlStore.Owner(actor, owner.issuer(), owner.subject()), target.id(), UUID.randomUUID(),
                target.revision(), action, accounts.now());
        try { controls.transition(command); }
        catch (DynamoWrites.Conflict raced) {
            // A revision race: the state may already be the desired one (another Disable), else the caller reloads and retries (409).
            var now = accounts.control(target.id());
            if (now != null && desired.equals(now.state())) return;
            throw raced;
        }
        catch (IllegalStateException refused) {
            // A lost revision race that still reached the desired state counts as done; anything else is reported, not guessed.
            var now = accounts.control(target.id());
            if (now != null && desired.equals(now.state())) return;
            Events.failed(Events.EVENT, "account." + action.name().toLowerCase(java.util.Locale.ROOT), "ACCOUNT_CONTROL_REFUSED").addKeyValue("target.id", target.id())
                .addKeyValue("error.type", Events.types(refused)).log("Account {} {} refused by the control store ({})", target.id(), action.name().toLowerCase(java.util.Locale.ROOT), refused.getMessage());
            throw unavailable("ACCOUNT_CONTROL_REFUSED");
        }
    }
    /** A waiting account (the control library refuses unadmitted accounts): the same control fields, event and directory flag. */
    private void waitingState(UUID actor, Control c, String state, long generation, String action) {
        Instant now = accounts.now();
        var tx = accounts.transaction();
        accounts.control(tx, c, attrs("accountState", s(state), "authGeneration", n(generation), "changedAt", time(now), "changedBy", s(actor.toString())), "\"admitted\"=?", bool(false));
        accounts.directory(tx, c, Map.of("accountState", s(state)));
        accounts.admission(tx, c.id(), c.issuer(), c.subject(), actor, action, List.of(), now);
        try { tx.commit(); } catch (DynamoWrites.Conflict raced) { var now2 = accounts.control(c.id()); if (now2 == null || !state.equals(now2.state())) throw raced; }
    }
    private static AccountFailure unavailable(String code) { return new AccountFailure(503, code, "The account state could not be changed. Retry the same action."); }
}
