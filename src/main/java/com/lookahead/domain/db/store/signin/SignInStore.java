package com.lookahead.domain.db.store.signin;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.access.ProtectedOwnerIdentityStore;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.signin.AccountAggregateStore.Control;
import com.lookahead.domain.db.store.signin.AccountAggregateStore.Identity;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.model.SignInModels.Account;
import com.lookahead.domain.model.SignInModels.Challenge;
import com.lookahead.domain.model.SignInModels.EmailAccount;
import com.lookahead.domain.model.SignInModels.FamilyBinding;
import com.lookahead.domain.model.SignInModels.Session;
import com.lookahead.domain.model.SignInModels.StoredProfile;
import com.lookahead.domain.util.SignInIdentityRules;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the sign-in store on DynamoDB. The account's {@code CONTROL} revision serialises its writers: every
 * structural write (a new sign-in, a revocation, a challenge, a profile change) is one transaction conditioned on the
 * revision read at the start of the attempt and advancing it; a lost race is re-read and retried. Nothing relies on
 * TTL. Ending every sign-in advances {@code authGeneration} on the control item (sign-ins carry the generation they
 * were issued under) instead of touching every sign-in item.
 * <p>DLV-1016: this class reads and writes; it decides nothing. SignInService's work runs inside {@link #unit} or
 * {@link #firstContact} and makes every sign-in decision (the limits, expiry, the refusals, linking and admission)
 * on what {@link SignIn.Work} hands it; the transactions, their conditions and the three attempts are here.
 */
public final class SignInStore implements SignIn {
    static final int ATTEMPTS = 3;
    private final AccountAggregateStore accounts;
    private final DynamoDbClient client;
    private final ProtectedOwnerIdentityStore owner;
    private final String table;

    public SignInStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public SignInStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.accounts = new AccountAggregateStore(client, tables, clock); this.client = client;
        this.owner = new ProtectedOwnerIdentityStore(client, tables); this.table = tables.userAccountDetailsTableName();
    }

    /** Every call after admission; a lost revision race is re-read and retried, the last conflict surfaces as it is. */
    @Override public <T> T unit(SignInIdentity identity, Function<Work, T> work) { return attempts(() -> work.apply(new Attempt(identity))); }
    /** Sign-in admission only; its own entry, so that no other call reaches the first-contact writes. */
    @Override public <T> T firstContact(SignInIdentity identity, Function<FirstContact, T> attempt) { return attempts(() -> attempt.apply(new Attempt(identity))); }
    private <T> T attempts(Supplier<T> attempt) {
        DynamoWrites.Conflict last = null;
        for (int round = 0; round < ATTEMPTS; round++) {
            try { return attempt.get(); }
            catch (DynamoWrites.Conflict conflict) { last = conflict; }
        }
        throw last;
    }

    /** Every stored sign-in of an account, ended ones included. */
    List<Session> sessions(UUID owner) { return query(client, table, AccountItems.account(owner), "SESSION#").stream().map(SignInStore::session).toList(); }

    /** One attempt: the identity's binding and its account's control item are read once, here, and every write is conditioned on that control. */
    private final class Attempt implements FirstContact {
        private final SignInIdentity identity;
        private final boolean known;
        private Map<String, AttributeValue> binding;
        private String provider;
        /** The account's control item as last read; the fence of every write. */
        private Control row;
        /** The controls of the last {@link #emailAccounts} read; {@link #link} is conditioned on the one it links to. */
        private List<Control> carriers = List.of();
        private Control created;

        Attempt(SignInIdentity identity) {
            this.identity = identity;
            binding = accounts.binding(identity.issuer(), identity.subject());
            known = !binding.isEmpty();
            if (known) {
                UUID id = DynamoItems.uuid(binding, "accountId"); provider = DynamoItems.string(binding, "provider");
                row = accounts.control(id);
            }
        }

        // ---- reads ---------------------------------------------------------------------------------------------------

        @Override public boolean known() { return known; }
        @Override public Account account() {
            return row == null ? null : new Account(row.id(), row.issuer(), row.subject(), row.username(), row.displayName(), row.verifiedEmail(), provider,
                row.admitted(), row.enabled(), row.author(), row.credentialChangePending(), row.state(), row.authGeneration());
        }
        @Override public Instant now() { return accounts.now(); }
        @Override public FamilyBinding familyBinding(String family) {
            var known = get(client, table, familyPk(family), "BINDING");
            return known.isEmpty() ? null : new FamilyBinding(DynamoItems.uuid(known, "accountId"), DynamoItems.uuid(known, "signInId"));
        }
        @Override public List<Session> sessions() { return SignInStore.this.sessions(row.id()); }
        @Override public Session session(UUID id) { var item = get(client, table, AccountItems.account(row.id()), sessionSk(id)); return item.isEmpty() ? null : SignInStore.session(item); }
        @Override public List<Challenge> challenges() { return query(client, table, AccountItems.account(row.id()), "CHALLENGE#").stream().map(SignInStore::challenge).toList(); }
        @Override public Challenge challenge(String digest) { var item = get(client, table, AccountItems.account(row.id()), challengeSk(digest)); return item.isEmpty() ? null : SignInStore.challenge(item); }
        @Override public List<EmailAccount> emailAccounts(String email) {
            carriers = accounts.emailControls(email);
            return carriers.stream().map(c -> new EmailAccount(c.id(), c.admitted(), c.enabled(), c.active(), c.managedTest())).toList();
        }
        @Override public StoredProfile profile() {
            var preferences = get(client, table, AccountItems.account(row.id()), "PREFERENCES");
            var links = new LinkedHashMap<String, String>();
            var stored = get(client, table, AccountItems.account(row.id()), "LINKS").get("links");
            if (stored != null && stored.hasM()) stored.m().forEach((kind, url) -> links.put(kind, url.s()));
            var methods = accounts.signInMethods(row.id(), true);
            // A stored preferences item always carries its theme; one without it fails here as it failed when the answer was built here.
            return new StoredProfile(preferences.isEmpty() ? null : Objects.requireNonNull(text(preferences, "theme")), links, methods);
        }
        @Override public boolean ownerIdentity() { return owner.matchesVerifiedIdentity(identity.issuer(), identity.subject()); }
        @Override public long emailRevision(String email) { return accounts.emailRevision(email); }
        @Override public Account follow(UUID id) {
            if (!known) provider = SignInIdentityRules.provider(identity.issuer(), identity.username());
            row = accounts.control(id); return account();
        }
        @Override public void rebind() { binding = accounts.binding(identity.issuer(), identity.subject()); }
        @Override public Account reload() { row = accounts.control(row.id()); return account(); }

        // ---- writes: one transaction each ------------------------------------------------------------------------------

        @Override public void touch(UUID session, Instant now) {
            // One conditional write on the session alone (no control fence: the control was read and checked by the work, and the
            // session dies with its generation anyway), so a page's parallel calls do not contend on one item.
            var tx = accounts.transaction();
            tx.update(table, key(AccountItems.account(row.id()), sessionSk(session)), Map.of("lastActiveAt", time(now)), "(\"revokedAt\" IS MISSING OR \"revokedAt\" IS NULL) AND \"authGeneration\"=?", n(row.authGeneration()));
            try { tx.commit(); } catch (DynamoWrites.Conflict raced) { /* revoked or re-issued meanwhile: the next call reads that */ }
        }
        @Override public UUID addSession(String family, String binding, Instant authenticatedAt, Instant now) {
            var tx = accounts.transaction(); accounts.fence(tx, row, true);
            UUID id = insert(tx, family, binding, authenticatedAt, now); tx.commit();
            return id;
        }
        @Override public UUID reissueSession(UUID old, String family, String binding, Instant authenticatedAt, Instant now) {
            // Keep the original family tombstone: revoked credentials must never be admitted again.
            var tx = accounts.transaction(); accounts.fence(tx, row, true);
            revoke(tx, old, now);
            UUID id = insert(tx, family, binding, authenticatedAt, now); tx.commit();
            return id;
        }
        @Override public void issueChallenge(List<String> stale, String digest, String family, String binding, Instant authenticatedAt, Instant now, Instant expires) {
            var tx = accounts.transaction(); accounts.fence(tx, row, true);
            for (String old : stale) tx.delete(table, key(AccountItems.account(row.id()), challengeSk(old)), null);
            tx.insertOnly(table, item(AccountItems.account(row.id()), challengeSk(digest), attrs("family", s(family), "binding", s(binding), "createdAt", time(now),
                "expiresAt", time(expires), "authAt", time(authenticatedAt), "cancelled", bool(false), "authGeneration", n(row.authGeneration()))));
            tx.commit();
        }
        @Override public UUID replaceSession(UUID selected, boolean endSelected, Challenge c, Instant now) {
            var tx = accounts.transaction(); accounts.fence(tx, row, true);
            if (endSelected) revoke(tx, selected, now);
            UUID id = insert(tx, c.family(), c.binding(), c.auth(), now);
            tx.update(table, key(AccountItems.account(row.id()), challengeSk(c.digest())), Map.of("selectedId", s(selected.toString()), "admittedId", s(id.toString())), null);
            tx.commit();
            return id;
        }
        @Override public void cancelChallenge(String digest) {
            var tx = accounts.transaction(); accounts.fence(tx, row, false); tx.update(table, key(AccountItems.account(row.id()), challengeSk(digest)), Map.of("cancelled", bool(true)), null); tx.commit();
        }
        @Override public void endSessions(List<UUID> sessions, Instant now) {
            var tx = accounts.transaction(); accounts.fence(tx, row, true); for (UUID session : sessions) revoke(tx, session, now); tx.commit();
        }
        @Override public void endOtherSessions(UUID current) {
            // The generation advances (every other session is dead at once) and the current session is re-issued under it.
            long generation = row.authGeneration() + 1;
            var tx = accounts.transaction(); accounts.control(tx, row, Map.of("authGeneration", n(generation)), null);
            tx.update(table, key(AccountItems.account(row.id()), sessionSk(current)), Map.of("authGeneration", n(generation)), "(\"revokedAt\" IS MISSING OR \"revokedAt\" IS NULL)");
            tx.commit();
        }
        @Override public void labelSession(UUID session, String label) {
            var tx = accounts.transaction(); accounts.fence(tx, row, false); tx.update(table, key(AccountItems.account(row.id()), sessionSk(session)), Map.of("label", s(label)), null); tx.commit();
        }
        @Override public void saveDisplayName(String name) {
            var tx = accounts.transaction(); accounts.control(tx, row, Map.of("displayName", s(name)), null); accounts.directory(tx, row, Map.of("displayName", s(name))); tx.commit();
        }
        @Override public void saveTheme(String theme) {
            var tx = accounts.transaction(); accounts.fence(tx, row, false);
            if (get(client, table, AccountItems.account(row.id()), "PREFERENCES").isEmpty()) tx.insertOnly(table, item(AccountItems.account(row.id()), "PREFERENCES", attrs("theme", s(theme), "updatedAt", time(now()))));
            else tx.update(table, key(AccountItems.account(row.id()), "PREFERENCES"), Map.of("theme", s(theme), "updatedAt", time(now())), null);
            tx.commit();
        }
        @Override public void saveLinks(Map<String, String> values) {
            var map = new HashMap<String, AttributeValue>();
            values.forEach((kind, url) -> { if (url != null) map.put(kind, s(url)); });
            var stored = AttributeValue.builder().m(map).build();
            var tx = accounts.transaction(); accounts.fence(tx, row, false);
            if (get(client, table, AccountItems.account(row.id()), "LINKS").isEmpty()) tx.insertOnly(table, item(AccountItems.account(row.id()), "LINKS", attrs("links", stored)));
            else tx.update(table, key(AccountItems.account(row.id()), "LINKS"), Map.of("links", stored), null);
            tx.commit();
        }
        @Override public void markCredentialChange() {
            var tx = accounts.transaction(); accounts.control(tx, row, Map.of("credentialChangePending", bool(true)), null); tx.commit();
        }
        @Override public void settleCredentialChange(boolean endSignIns, List<String> open) {
            var tx = accounts.transaction();
            var changes = new HashMap<String, AttributeValue>(Map.of("credentialChangePending", bool(false)));
            if (endSignIns) {
                changes.put("authGeneration", n(row.authGeneration() + 1));
                for (String digest : open) tx.update(table, key(AccountItems.account(row.id()), challengeSk(digest)), Map.of("cancelled", bool(true)), null);
            }
            accounts.control(tx, row, changes, null); tx.commit();
        }
        private UUID insert(DynamoWrites tx, String family, String binding, Instant auth, Instant now) {
            UUID id = UUID.randomUUID();
            tx.insertOnly(table, item(AccountItems.account(row.id()), sessionSk(id), attrs("signInId", s(id.toString()), "family", s(family), "binding", s(binding), "createdAt", time(now),
                "lastActiveAt", time(now), "recentAuthAt", time(auth), "authGeneration", n(row.authGeneration()))));
            tx.insertOnly(table, item(familyPk(family), "BINDING", attrs("accountId", s(row.id().toString()), "signInId", s(id.toString()), "binding", s(binding))));
            return id;
        }
        private void revoke(DynamoWrites tx, UUID id, Instant now) {
            tx.update(table, key(AccountItems.account(row.id()), sessionSk(id)), Map.of("revokedAt", time(now)), "(\"revokedAt\" IS MISSING OR \"revokedAt\" IS NULL)");
        }

        // ---- first contact ---------------------------------------------------------------------------------------------

        /** The per-email sentinel, read by the work before the candidates and advanced in this transaction, serialises a Google and a password first contact. */
        @Override public void link(UUID candidate, String email, long emailRevision, Instant now) {
            Control row = carriers.stream().filter(c -> c.id().equals(candidate)).findFirst().orElseThrow();
            String provider = SignInIdentityRules.provider(identity.issuer(), identity.username());
            var identityRecord = new Identity(UUID.randomUUID(), identity.issuer(), identity.subject(), identity.username(), provider, email, "linked", now);
            var tx = accounts.transaction();
            accounts.fence(tx, row, true);
            accounts.identity(tx, row.id(), identityRecord);
            accounts.emailCarrier(tx, email, row.id(), row.issuer(), List.of(identityRecord.identityId().toString()));
            accounts.emailSentinel(tx, email, emailRevision);
            var usernames = new ArrayList<>(accounts.usernames(row.id())); usernames.add(identity.username());
            accounts.directory(tx, row, Map.of("usernames", list(usernames.stream().map(AccountItems::lower).toList())));
            accounts.admission(tx, row.id(), identity.issuer(), identity.subject(), null, "link", List.of(), now);
            tx.commit();
        }
        @Override public UUID create(String email, long emailRevision, boolean admitted, boolean author, Instant now) {
            UUID id = UUID.randomUUID();
            String provider = SignInIdentityRules.provider(identity.issuer(), identity.username());
            var control = new Control(id, identity.issuer(), identity.subject(), identity.username(), identity.displayName(), email, admitted, true, author, false, false, "ACTIVE", 1, 0, now, admitted ? now : null);
            var identityRecord = new Identity(UUID.randomUUID(), identity.issuer(), identity.subject(), identity.username(), provider, email, "first_contact", now);
            var tx = accounts.transaction();
            tx.insertOnly(table, item(AccountItems.account(id), "META", attrs("revision", n(1), "lastPlanNumber", n(0), "createdAt", time(now))));
            tx.insertOnly(table, item(AccountItems.account(id), "CONTROL", attrs("issuer", s(identity.issuer()), "subject", s(identity.subject()), "username", s(identity.username()),
                "displayName", s(identity.displayName()), "verifiedEmail", s(email), "admitted", bool(admitted), "author", bool(author), "managedTest", bool(false), "enabled", bool(true),
                "credentialChangePending", bool(false), "accountState", s("ACTIVE"), "revision", n(1), "authGeneration", n(0), "requestedAt", time(now), "admittedAt", admitted ? time(now) : null)));
            accounts.identity(tx, id, identityRecord);
            if (email != null) { accounts.emailCarrier(tx, email, id, identity.issuer(), List.of(identityRecord.identityId().toString(), "account")); accounts.emailSentinel(tx, email, emailRevision); }
            if (!admitted) tx.insertOnly(table, item(WAITING, waitingSk(now, id), attrs("accountId", s(id.toString()), "requestedAt", time(now))));
            tx.insertOnly(table, accounts.directoryItem(control, List.of(identity.username())));
            accounts.admission(tx, id, identity.issuer(), identity.subject(), null, "request", List.of(), now);
            if (admitted) accounts.admission(tx, id, identity.issuer(), identity.subject(), null, "admit", List.of(), now);
            tx.commit();
            created = control;
            return id;
        }
        @Override public void admitCreated(List<String> roles, Instant now) {
            var admit = accounts.transaction(); accounts.admit(admit, created, null, roles, now); admit.commit();
        }
        /** The same writes as the owner's admission of a waiting account, author set in the same statement. */
        @Override public void admitWaitingOwner(Instant now) {
            var tx = accounts.transaction();
            accounts.control(tx, row, attrs("admitted", bool(true), "author", bool(true), "admittedAt", time(now), "admittedBy", DynamoItems.nul()),
                "\"admitted\"=? AND \"accountState\"=?", bool(false), s("ACTIVE"));
            if (row.requestedAt() != null) tx.delete(table, key(WAITING, waitingSk(row.requestedAt(), row.id())), null);
            accounts.directory(tx, row, Map.of("admitted", bool(true), "author", bool(true)));
            accounts.admission(tx, row.id(), row.issuer(), row.subject(), null, "admit", List.of(), now);
            tx.commit();
        }
        @Override public void refreshClaims(String email) { refresh(identity, row, binding, email); }
        @Override public void refreshEmail(String email) { row = SignInStore.this.refreshEmail(identity, row, binding, email); }
        @Override public void makeAuthor() {
            var tx = accounts.transaction(); accounts.control(tx, row, Map.of("author", bool(true)), null); accounts.directory(tx, row, Map.of("author", bool(true))); tx.commit(); row = accounts.control(row.id());
        }
    }

    // ---- stored shapes ---------------------------------------------------------------------------------------------

    private static Challenge challenge(Map<String, AttributeValue> item) {
        return new Challenge(DynamoItems.string(item, "sk").substring("CHALLENGE#".length()), DynamoItems.string(item, "family"), DynamoItems.string(item, "binding"), DynamoItems.instant(item, "createdAt"),
            DynamoItems.instant(item, "expiresAt"), DynamoItems.instant(item, "authAt"), flag(item, "cancelled"), uuid(item, "selectedId"), uuid(item, "admittedId"), number(item, "authGeneration", 0));
    }
    private static Session session(Map<String, AttributeValue> item) {
        return new Session(DynamoItems.uuid(item, "signInId"), DynamoItems.string(item, "family"), DynamoItems.string(item, "binding"), DynamoItems.instant(item, "createdAt"),
            DynamoItems.instant(item, "lastActiveAt"), DynamoItems.instant(item, "recentAuthAt"), instant(item, "revokedAt"), number(item, "authGeneration", 0), text(item, "label"), text(item, "clientDescription"));
    }
    // ---- the provider's claims on an identity and its account ------------------------------------------------------

    /** The identity's own claims always; the account's profile only from its first identity. */
    private void refresh(SignInIdentity identity, Control row, Map<String, AttributeValue> binding, String email) {
        boolean primary = row.issuer().equals(identity.issuer()) && row.subject().equals(identity.subject());
        var tx = accounts.transaction(); var carriers = new AccountAggregateStore.Carriers();
        identityClaims(tx, carriers, identity, row, binding, identity.username(), email);
        if (primary) {
            var usernames = usernamesWith(row, binding, identity.username());
            var next = new Control(row.id(), row.issuer(), row.subject(), identity.username(), identity.displayName(), email, row.admitted(), row.enabled(), row.author(), row.managedTest(),
                row.credentialChangePending(), row.state(), row.revision(), row.authGeneration(), row.requestedAt(), row.admittedAt());
            // The directory key lower-cases the username: a case-only change updates the item in place, any other moves it.
            if (!lower(row.username()).equals(lower(identity.username()))) { tx.delete(table, key(DIRECTORY, personSk(row.username(), row.id())), null); tx.insertOnly(table, accounts.directoryItem(next, usernames)); }
            else tx.update(table, key(DIRECTORY, personSk(row.username(), row.id())), Map.of("username", s(identity.username()), "displayName", s(identity.displayName()), "verifiedEmail", email == null ? DynamoItems.nul() : s(email),
                "usernames", accounts.directoryItem(next, usernames).get("usernames")), null);
            if (!Objects.equals(row.verifiedEmail(), email)) { if (row.verifiedEmail() != null) carriers.remove(row.verifiedEmail(), "account"); if (email != null) carriers.add(email, "account"); }
            accounts.control(tx, row, attrs("username", s(identity.username()), "displayName", s(identity.displayName()), "verifiedEmail", email == null ? DynamoItems.nul() : s(email)), null);
        } else {
            accounts.fence(tx, row, true);
            if (!Objects.equals(text(binding, "username"), identity.username())) accounts.directory(tx, row, Map.of("usernames", list(usernamesWith(row, binding, identity.username()).stream().map(AccountItems::lower).toList())));
        }
        accounts.carriers(tx, carriers, row.id(), row.issuer());
        tx.commit();
    }
    /** The identity's verified email, and the account's when it is the first identity; only a changed value is written. */
    private Control refreshEmail(SignInIdentity identity, Control row, Map<String, AttributeValue> binding, String email) {
        boolean primary = row.issuer().equals(identity.issuer()) && row.subject().equals(identity.subject());
        boolean identityChanged = !Objects.equals(text(binding, "verifiedEmail"), email), accountChanged = primary && !Objects.equals(row.verifiedEmail(), email);
        if (!identityChanged && !accountChanged) return row;
        var tx = accounts.transaction(); var carriers = new AccountAggregateStore.Carriers();
        if (identityChanged) identityClaims(tx, carriers, identity, row, binding, text(binding, "username"), email);
        if (accountChanged) {
            if (row.verifiedEmail() != null) carriers.remove(row.verifiedEmail(), "account"); if (email != null) carriers.add(email, "account");
            accounts.directory(tx, row, Map.of("verifiedEmail", email == null ? DynamoItems.nul() : s(email)));
        }
        accounts.carriers(tx, carriers, row.id(), row.issuer());
        accounts.control(tx, row, accountChanged ? attrs("verifiedEmail", email == null ? DynamoItems.nul() : s(email)) : Map.of(), null);
        tx.commit();
        return accounts.control(row.id());
    }
    private void identityClaims(DynamoWrites tx, AccountAggregateStore.Carriers carriers, SignInIdentity identity, Control row, Map<String, AttributeValue> binding, String username, String email) {
        UUID identityId = DynamoItems.uuid(binding, "identityId"); String old = text(binding, "verifiedEmail");
        var set = attrs("username", s(username), "verifiedEmail", email == null ? DynamoItems.nul() : s(email));
        tx.update(table, key(identityPk(identity.issuer(), identity.subject()), "BINDING"), set, null);
        tx.update(table, key(AccountItems.account(row.id()), identitySk(DynamoItems.instant(binding, "linkedAt"), identityId)), set, null);
        if (!Objects.equals(old, email)) { if (old != null) carriers.remove(old, identityId.toString()); if (email != null) carriers.add(email, identityId.toString()); }
    }
    private List<String> usernamesWith(Control row, Map<String, AttributeValue> binding, String username) {
        UUID identityId = DynamoItems.uuid(binding, "identityId");
        var names = new ArrayList<String>();
        for (Identity i : accounts.identities(row.id())) { if ("test".equals(i.provider())) continue; names.add(i.identityId().equals(identityId) ? username : i.username()); }
        return names;
    }
}
