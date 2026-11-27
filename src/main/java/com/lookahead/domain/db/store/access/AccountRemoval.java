package com.lookahead.domain.db.store.access;

import com.lookahead.domain.model.AccountRemovalModels.Target;
import java.util.UUID;

/**
 * DLV-970: the owner's Disable, Enable and Delete of a DEV sign-in account on one store: the checks (not an author
 * account, not a test account, not already deleted), the state change, ended sign-ins, erasure for Delete, and the
 * admission event when something changed. DynamoDB commits the state change with its terminal marker and erasure job in one fenced transaction and runs
 * the erasure after it, resumable by a retried Delete or the account-erasure sweep. The policy refusals use
 * {@code AccountRemovalService}'s codes. {@link #erase} is also what the account merge calls after its own commit.
 */
public interface AccountRemoval {
    /** author, admitted and state of the account for the directory flags; null when there is no such account. */
    Target options(UUID id);
    /**
     * The account as the store read it. The service decides on it (DLV-1016: the store refused and decided itself) and
     * hands the same reading back to the write, which is made at exactly that revision. Each value is read when asked for.
     */
    interface Subject {
        boolean author();
        /** A managed test account: by its own flag or by the test-account registry. */
        boolean managedTest();
        /** The account with its sign-in usernames, for the pool calls. */
        Target target();
        boolean removed();
        boolean disabled();
        boolean active();
    }
    /** Null when there is no such account. */
    Subject subject(UUID id);
    /**
     * The state change of an admitted account was not made, and not because another writer came first: the acting owner has no
     * control item ({@link Reason#OWNER_IDENTITY_UNKNOWN}, no cause), or the control transition refused it and the account is not
     * in the wanted state either ({@link Reason#ACCOUNT_CONTROL_REFUSED}; the cause is the control store's refusal). DLV-1016:
     * the store reports it; {@code AccountRemovalService} writes the line and answers 503.
     */
    final class NotChanged extends RuntimeException {
        public enum Reason { OWNER_IDENTITY_UNKNOWN, ACCOUNT_CONTROL_REFUSED }
        private final Reason reason; private final UUID target; private final String action;
        public NotChanged(Reason reason, UUID target, String action, RuntimeException cause) {
            super("Account " + target + " " + action + " not made: " + reason, cause);
            this.reason = reason; this.target = target; this.action = action;
        }
        public Reason reason() { return reason; }
        /** The account that was to change. */
        public UUID target() { return target; }
        /** The control action in lower case: {@code disable}, {@code enable} or {@code remove}. */
        public String action() { return action; }
    }
    /** Disables the account as read: an admitted one through the control transition, a waiting one in place. {@link NotChanged} (here, in enable and in remove) when an admitted account's change was not made. */
    void disable(UUID actor, Subject subject);
    void enable(UUID actor, Subject subject);
    /** Deletes the account as read: a waiting one outright, an admitted one through the queue, the control transition and the erasure. */
    void remove(UUID actor, Subject subject);
    /** For an account that is already deleted: finishes an erasure that was interrupted. */
    void finishErasure(UUID actor, UUID id);
    /** Roles, grants, plans, receipts and profile settings; purchase history stays for the audit. */
    void erase(UUID actor, UUID id);
}
