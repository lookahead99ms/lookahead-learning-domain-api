package com.lookahead.domain.db.store.testaccounts;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;

/**
 * DLV-970: the test-account aggregate behind {@code TestAccountService}, {@code TestAccountCreationService}, {@code TestAccountDevRosterService},
 * {@code TestSessionService} and {@code TestAccountUpgradeMarkerService}: the registry, the reset ledger, creation reservations and the
 * reads of accounts, identities and staff roles they need. {@link TestAccountStore} commits each {@link #transact}
 * body under the account revisions it locked (the PostgreSQL store retired 2026-11-17).
 */
public interface TestAccount {
    record Reservation(UUID accountId, String username, String state) {}
    /**
     * The commit of a reset or restore found a purchase the body had not seen when it asked to supersede the account's
     * purchases ({@link Work#supersedePurchases}): a checkout that came in after the body decided. Nothing was written.
     * DLV-1016: the store reports it; {@code TestAccountService} answers 409 REVISION_CONFLICT.
     */
    final class PurchasesChanged extends RuntimeException {
        public PurchasesChanged() { super("A purchase appeared after the body asked to supersede the account's purchases"); }
    }

    // ---- reads outside a transaction --------------------------------------------------------------------------
    /** Lifetime spend: active and deleted creations of this creator. */
    int lifetimeSpend(UUID creator);
    /** One transaction. */
    <T> T transact(Function<Work, T> body);
    /**
     * {@link #transact} for a body that may run again from the start: only reads and this store's own writes, nothing
     * committed through another lane or outside the store. DynamoDB re-reads and re-runs it after a lost revision race (bounded), where a body with side effects keeps the
     * conflict as its 409.
     */
    default <T> T transactRetrying(Function<Work, T> body) { return transact(body); }

    /**
     * The statements of one test-account transaction: every job below on the one object the transaction hands out.
     * DLV-1016: it declares nothing of its own; a helper that needs one job takes that job's interface.
     */
    interface Work extends AccountRecord, Registration, RegistryState, ResetLedger, AccountData, Creations {}

    /** The account itself: its subject (the lock every writer takes, its creation) and its admitted account record. */
    interface AccountRecord {
        /** Serialises writers of the account; nothing happens when it has no subject yet. */
        void lockSubject(UUID id);
        /** True when the subject was created now. */
        boolean createSubject(UUID id);
        boolean hasAccountRecord(UUID id);
        void admitTestAccount(UUID id, String username, String displayName, UUID admittedBy);
    }

    /** A new registry row, one statement per origin: the Local seed, the DEV roster, a created test account. */
    interface Registration {
        /** True when the row was inserted now (Local seed). */
        boolean registerSeed(UUID id, String username, String initialization);
        void registerRoster(UUID id, String username, UUID creator);
        void registerCreated(UUID id, String username, UUID creator);
    }

    /** The registry row of an account as a reset, restore or delete decides on it, and the state change each one makes. */
    interface RegistryState {
        TestAccountRegistryRows.RegistryRow registry(UUID id);
        /** An owner-created roster row (creator set, not 'created'). */
        boolean rosterRow(UUID id);
        /** A deleted owner-created roster row, locked. */
        boolean deletedRosterRow(UUID id);
        /** The active row's revision, locked; null when none. */
        Long activeRevision(UUID id);
        void resetRegistry(UUID id, String preset, UUID actor);
        void restoreRegistry(UUID id, UUID actor);
        void deleteRegistry(UUID id, UUID actor);
        /** The registry row for a delete decision, locked. */
        TestAccountRegistryRows.RegistryRow registryLocked(UUID id);
    }

    /** The append-only ledger of resets: the receipt a retried key finds and the entry a reset, restore or roster admission adds. */
    interface ResetLedger {
        /** {@code actor:preset} of the reset this key made, or null. */
        String resetReceipt(UUID id, UUID key);
        void insertReset(UUID id, UUID key, UUID actor, String preset);
    }

    /** The account's other data: what a scenario rebuild clears and grants, and what a delete erases. */
    interface AccountData {
        void supersedePurchases(UUID id);
        void deletePlans(UUID id);
        void deleteGrants(UUID id);
        void grantTopics(UUID id, Collection<String> topics);
        void erase(UUID id);
    }

    /** Creation reservations and the spend they count towards the creator's lifetime limit. */
    interface Creations {
        Reservation reservation(UUID creator, UUID key);
        /** Lifetime spend plus pending holds younger than {@code hold}; how long a hold lasts is the caller's rule. */
        int holdsAndSpend(UUID creator, Duration hold);
        int lifetimeSpend(UUID creator);
        void reserve(UUID creator, UUID key, UUID account, String username);
        /** The creation's state, locked; null when the creator has none for this account. */
        String creationState(UUID creator, UUID account);
        void activateCreation(UUID creator, UUID account);
        void deleteCreation(UUID account);
        /** Restore: deleted creation rows of the account back to active. */
        void restoreCreations(UUID account);
    }
}
