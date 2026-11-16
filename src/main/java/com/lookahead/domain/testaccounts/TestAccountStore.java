package com.lookahead.domain.testaccounts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * DLV-970: the test-account aggregate behind {@link TestAccountsService}, {@link CreatedTestAccounts}, {@link DevTestRoster},
 * {@link DevTestSessions} and {@link UpgradeMarkers}: the registry, the reset ledger, creation reservations and the
 * reads of accounts, identities and staff roles they need. {@link JdbcTestAccountStore} runs the SQL of those classes
 * unchanged; {@link DynamoTestAccountStore} commits each {@link #transact} body under the account revisions it locked.
 */
public interface TestAccountStore {
    record RegistryRow(UUID accountId, String username, String initialization, String creationState, UUID creatorId,
            String lastChangedBy, Instant lastChangedAt, long revision) {}
    /** One owner-created roster row of the DEV roster status. */
    record RosterStatus(UUID accountId, String creationState, boolean hasPlan, boolean changed) {}
    record Reservation(UUID accountId, String username, String state) {}
    record Holder(UUID accountId, boolean authorAccess) {}
    record Target(UUID accountId, String username, String displayName) {}

    // ---- reads outside a transaction --------------------------------------------------------------------------
    /** Active registry rows by username; {@code cloud}: only created accounts and the owner-created roster. */
    List<RegistryRow> activeRegistry(boolean cloud);
    Set<UUID> registeredIds();
    /** Deleted owner-created roster rows. */
    Set<UUID> deletedRosterIds();
    List<RosterStatus> rosterStatus();
    List<String> staffRoles(UUID account);
    String displayName(UUID account);
    /** Lifetime spend: active and deleted creations of this creator. */
    int lifetimeSpend(UUID creator);
    UUID accountOf(String issuer, String subject);
    Holder holder(String issuer, String subject);
    /** Active DEV test accounts admitted on the test issuer, by username; {@code only} narrows to one id. */
    List<Target> targets(UUID only);
    /** One transaction (PostgreSQL: joins the caller's when one is open). */
    <T> T transact(Function<Work, T> body);
    /**
     * {@link #transact} for a body that may run again from the start: only reads and this store's own writes, nothing
     * committed through another lane or outside the store. PostgreSQL waits on its row locks and runs it once;
     * DynamoDB re-reads and re-runs it after a lost revision race (bounded), where a body with side effects keeps the
     * conflict as its 409.
     */
    default <T> T transactRetrying(Function<Work, T> body) { return transact(body); }

    /** The statements of one test-account transaction, in the order the services issue them. */
    interface Work {
        void actor(String actor);
        void seedLock();
        /** Serialises writers of the account; nothing happens when it has no subject yet. */
        void lockSubject(UUID id);
        /** True when the subject was created now. */
        boolean createSubject(UUID id);
        RegistryRow registry(UUID id);
        /** True when the row was inserted now (Local seed). */
        boolean registerSeed(UUID id, String username, String initialization);
        void registerRoster(UUID id, String username, UUID creator);
        void registerCreated(UUID id, String username, UUID creator);
        /** An owner-created roster row (creator set, not 'created'). */
        boolean rosterRow(UUID id);
        /** A deleted owner-created roster row, locked. */
        boolean deletedRosterRow(UUID id);
        /** {@code actor:preset} of the reset this key made, or null. */
        String resetReceipt(UUID id, UUID key);
        /** The active row's revision, locked; null when none. */
        Long activeRevision(UUID id);
        void resetRegistry(UUID id, String preset, UUID actor);
        void restoreRegistry(UUID id, UUID actor);
        void deleteRegistry(UUID id, UUID actor);
        void insertReset(UUID id, UUID key, UUID actor, String preset);
        // the account's other data the scenario rebuild touches
        void supersedePurchases(UUID id);
        void tombstoneReceipts(UUID id);
        void deletePlans(UUID id);
        void deleteGrants(UUID id);
        void clearStaffAccess(UUID id);
        void grantTopics(UUID id, Collection<String> topics);
        boolean hasAccountRecord(UUID id);
        void admitTestAccount(UUID id, String username, String displayName, UUID admittedBy);
        // creation reservations
        Reservation reservation(UUID creator, UUID key);
        /** Lifetime spend plus pending holds younger than 15 minutes. */
        int holdsAndSpend(UUID creator);
        int lifetimeSpend(UUID creator);
        void reserve(UUID creator, UUID key, UUID account, String username);
        /** The creation's state, locked; null when the creator has none for this account. */
        String creationState(UUID creator, UUID account);
        void activateCreation(UUID creator, UUID account);
        void deleteCreation(UUID account);
        /** Restore: deleted creation rows of the account back to active. */
        void restoreCreations(UUID account);
        /** The registry row for a delete decision, locked. */
        RegistryRow registryLocked(UUID id);
        void erase(UUID id, boolean localFixturePurge);
    }
}
