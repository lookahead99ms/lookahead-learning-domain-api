package com.lookahead.domain.cloud;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.Events;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Control;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Identity;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.domain.persistence.dynamodb.ProtectedOwnerIdentityStore;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the sign-in registry on DynamoDB. The account's {@code CONTROL} revision plays the role of the PostgreSQL
 * row lock: every structural write (a new sign-in, a revocation, a challenge, a profile change) is one transaction
 * conditioned on the revision read at the start of the request and advancing it; a lost race is re-read and retried.
 * Idle (30 min) and absolute (7 days) expiry, challenge expiry (5 min) and the 5-per-15-minutes window are checked in
 * code at the exact boundary; nothing relies on TTL. Revoke-all advances {@code authGeneration} on the control item
 * (sessions carry the generation they were issued under) instead of touching every session item.
 */
public final class DynamoCloudSignInRegistry extends CloudSignInRegistry {
    static final Duration IDLE = Duration.ofMinutes(30), ABSOLUTE = Duration.ofDays(7), CHALLENGE = Duration.ofMinutes(5), WINDOW = Duration.ofMinutes(15);
    static final int ATTEMPTS = 3;
    /** A session's lastActiveAt is written at most this often: parallel calls of one page then write nothing and cannot conflict. */
    static final Duration TOUCH = Duration.ofMinutes(1);
    private record Account(UUID id, String username, String name, boolean author, String provider, Control control) {}
    record Session(UUID id, String family, String binding, Instant created, Instant active, Instant recent, Instant revoked, long generation, String label, String client) {}
    private record Challenge(String digest, String family, String binding, Instant created, Instant expires, Instant auth, boolean cancelled, UUID selected, UUID admitted, long generation) {}
    private record Linking(Control linked, long emailRevision) {}
    private final DynamoCloudAccounts accounts;
    private final DynamoDbClient client;
    private final ProtectedOwnerIdentityStore owner;
    private final String table;
    private com.lookahead.domain.access.StaffAccessService access;

    public DynamoCloudSignInRegistry(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    public DynamoCloudSignInRegistry(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.accounts = new DynamoCloudAccounts(client, tables, clock); this.client = client;
        this.owner = new ProtectedOwnerIdentityStore(client, tables); this.table = tables.userAccountDetailsTableName();
    }
    @Override public void configureStaffAccess(com.lookahead.domain.access.StaffAccessService access) { this.access = access; }

    @Override public Admission admit(CognitoIdentity identity, String proof) {
        String binding = binding(proof), family = family(identity);
        Admission admission = locked(identity, false, true, account -> merge(identity, admitted(identity, binding, family, account)));
        if (admission.signInId() != null) Events.success(Events.EVENT, "sign-in.admitted").addKeyValue("account.id", admission.accountId()).addKeyValue("target.id", admission.signInId())
            .addKeyValue("merge.available", admission.mergeAvailable())
            .log(admission.mergeAvailable() ? "Sign-in {} admitted for account {}; other accounts share its verified email, so the merge is offered" : "Sign-in {} admitted for account {}",
                admission.signInId(), admission.accountId());
        else Events.success(Events.EVENT, "sign-in.challenge").addKeyValue("account.id", admission.accountId())
            .log("Account {} already has 2 active sign-ins: a challenge to replace one was issued", admission.accountId());
        return admission;
    }

    private Admission admitted(CognitoIdentity identity, String binding, String family, Account a) {
        Instant now = now(); requireFresh(identity.authenticatedAt(), now);
        var known = get(client, table, familyPk(family), "BINDING");
        if (!known.isEmpty()) {
            UUID owner = DynamoItems.uuid(known, "accountId"), signIn = DynamoItems.uuid(known, "signInId");
            Session existing = owner.equals(a.id) ? session(a.id, signIn) : null;
            if (existing == null || !active(existing, a.control, now) || !existing.binding.equals(binding)) throw denied();
            return new Admission(a.id, existing.id, null, null);
        }
        var sessions = activeSessions(a, now);
        var old = sessions.stream().filter(s -> s.binding.equals(binding)).findFirst();
        if (old.isPresent()) {
            // Keep the original family tombstone: revoked credentials must never be admitted again.
            var tx = accounts.transaction(); accounts.fence(tx, a.control, true);
            revoke(tx, a.id, old.get().id, now);
            UUID id = insert(tx, a, family, binding, identity.authenticatedAt(), now); tx.commit();
            return new Admission(a.id, id, null, null);
        }
        if (sessions.size() < 2) {
            var tx = accounts.transaction(); accounts.fence(tx, a.control, true);
            UUID id = insert(tx, a, family, binding, identity.authenticatedAt(), now); tx.commit();
            return new Admission(a.id, id, null, null);
        }
        var challenges = challenges(a.id);
        // Do not issue a second challenge for the same token family; the caller retains its secret.
        if (challenges.stream().anyMatch(c -> c.family.equals(family) && c.binding.equals(binding) && !c.cancelled && c.expires.isAfter(now))) throw failure(409, "SIGN_IN_CHALLENGE_PENDING");
        var tx = accounts.transaction(); accounts.fence(tx, a.control, true);
        Instant windowStart = now.minus(WINDOW);
        for (Challenge stale : challenges) if (stale.expires.isBefore(windowStart)) tx.delete(table, key(account(a.id), challengeSk(stale.digest)), null);
        if (challenges.stream().filter(c -> c.created.isAfter(windowStart)).count() >= CHALLENGES_PER_WINDOW) {
            Events.limit("sign-in.challenge", "SIGN_IN_RATE_LIMITED", "sign-in-challenges", CHALLENGES_PER_WINDOW, "15m").addKeyValue("account.id", a.id)
                .log("Sign-in limit hit for account {}: at most {} sign-in challenges in 15 minutes; refused with 429", a.id, CHALLENGES_PER_WINDOW);
            throw failure(429, "SIGN_IN_RATE_LIMITED");
        }
        String token = randomToken(); Instant expires = now.plus(CHALLENGE);
        tx.insertOnly(table, item(account(a.id), challengeSk(digest(token)), attrs("family", s(family), "binding", s(binding), "createdAt", time(now),
            "expiresAt", time(expires), "authAt", time(identity.authenticatedAt()), "cancelled", bool(false), "authGeneration", n(a.control.authGeneration()))));
        tx.commit();
        return new Admission(a.id, null, token, expires);
    }
    private Admission merge(CognitoIdentity identity, Admission admission) {
        return new Admission(admission.accountId(), admission.signInId(), admission.challengeToken(), admission.expiresAt(), accounts.mergeable(admission.accountId(), email(identity)));
    }
    @Override public AccountPrincipal authenticate(CognitoIdentity identity, String proof) {
        return locked(identity, a -> { current(a, identity, proof, true); return new AccountPrincipal(a.id, a.username, a.name, true, a.author, identity.verifiedEmail()); });
    }
    @Override public Inventory inventory(CognitoIdentity identity, String proof) { return locked(identity, a -> { Session current = current(a, identity, proof, true); return new Inventory(2, entries(a, current.id)); }); }
    @Override public ChallengeView challenge(CognitoIdentity identity, String proof, String token) { return locked(identity, a -> { Challenge c = challenge(a, identity, proof, token); return new ChallengeView(2, entries(a, null), c.expires); }); }
    @Override public Admission replace(CognitoIdentity identity, String proof, String token, UUID selected) {
        return locked(identity, a -> {
            Challenge c = challenge(a, identity, proof, token); Instant now = now();
            if (c.admitted != null) { if (!Objects.equals(c.selected, selected)) throw invalid(); current(a, identity, proof, false); return merge(identity, new Admission(a.id, c.admitted, null, null)); }
            Session chosen = selected == null ? null : session(a.id, selected); if (chosen == null) throw invalid();
            var remaining = activeSessions(a, now).stream().filter(s -> !s.id.equals(selected)).toList();
            if (remaining.size() >= 2) throw invalid();
            // Duplicate challenges cannot create multiple active families or bindings.
            if (!get(client, table, familyPk(c.family), "BINDING").isEmpty() || remaining.stream().anyMatch(s -> s.binding.equals(c.binding))) throw invalid();
            var tx = accounts.transaction(); accounts.fence(tx, a.control, true);
            if (chosen.revoked == null) revoke(tx, a.id, selected, now);
            UUID id = insert(tx, a, c.family, c.binding, c.auth, now);
            tx.update(table, key(account(a.id), challengeSk(c.digest)), Map.of("selectedId", s(selected.toString()), "admittedId", s(id.toString())), null);
            tx.commit();
            Events.success(Events.EVENT, "sign-in.replaced").addKeyValue("account.id", a.id).addKeyValue("target.id", selected).log("Account {} replaced sign-in {} with new sign-in {}", a.id, selected, id);
            return merge(identity, new Admission(a.id, id, null, null));
        });
    }
    @Override public void cancel(CognitoIdentity identity, String proof, String token) { locked(identity, a -> { Challenge c = challenge(a, identity, proof, token);
        if (c.admitted == null) { var tx = accounts.transaction(); accounts.fence(tx, a.control, false); tx.update(table, key(account(a.id), challengeSk(c.digest)), Map.of("cancelled", bool(true)), null); tx.commit(); } return null; }); }
    @Override public boolean revoke(CognitoIdentity identity, String proof, UUID target) { return locked(identity, a -> { Session current = current(a, identity, proof, false); Instant now = now(); requireFresh(current.recent, now);
        Session ended = session(a.id, target); if (ended == null) throw invalid();
        // Already revoked: nothing to write (the SQL COALESCE), the answer is the same.
        if (ended.revoked == null) { var tx = accounts.transaction(); accounts.fence(tx, a.control, true); revoke(tx, a.id, target, now); tx.commit(); }
        Events.success(Events.EVENT, "sign-in.revoked").addKeyValue("account.id", a.id).addKeyValue("target.id", target).log("Account {} ended sign-in {}", a.id, target);
        return current.id.equals(target); }); }
    @Override public void revokeOthers(CognitoIdentity identity, String proof) { locked(identity, a -> { Session current = current(a, identity, proof, false); Instant now = now(); requireFresh(current.recent, now);
        var others = activeSessions(a, now).stream().filter(s -> !s.id.equals(current.id)).toList();
        // The generation advances (every other session is dead at once) and the current session is re-issued under it.
        long generation = a.control.authGeneration() + 1;
        var tx = accounts.transaction(); accounts.control(tx, a.control, Map.of("authGeneration", n(generation)), null);
        tx.update(table, key(account(a.id), sessionSk(current.id)), Map.of("authGeneration", n(generation)), "(\"revokedAt\" IS MISSING OR \"revokedAt\" IS NULL)");
        tx.commit();
        Events.success(Events.EVENT, "sign-in.revoked-others").addKeyValue("account.id", a.id).addKeyValue("count", others.size()).log("Account {} ended its other sign-ins ({} row(s))", a.id, others.size());
        return null; }); }
    @Override public Entry rename(CognitoIdentity identity, String proof, String label) {
        if (label == null || label.strip().length() > 80 || label.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>')) throw failure(400, "INVALID_SIGN_IN_LABEL");
        return locked(identity, a -> { Session current = current(a, identity, proof, false);
            var tx = accounts.transaction(); accounts.fence(tx, a.control, false); tx.update(table, key(account(a.id), sessionSk(current.id)), Map.of("label", s(label.strip())), null); tx.commit();
            return entries(a, current.id).stream().filter(Entry::current).findFirst().orElseThrow(); });
    }
    @Override public void terminate(CognitoIdentity identity, String proof) { locked(identity, a -> { String binding = binding(proof), family = family(identity); Instant now = now();
        var mine = sessions(a.id).stream().filter(s -> s.revoked == null && s.family.equals(family) && s.binding.equals(binding)).toList();
        if (!mine.isEmpty()) { var tx = accounts.transaction(); accounts.fence(tx, a.control, true); for (Session s : mine) revoke(tx, a.id, s.id, now); tx.commit(); }
        Events.success(Events.EVENT, "sign-in.signed-out").addKeyValue("account.id", a.id).log("Account {} signed out", a.id);
        return null; }); }
    @Override public Map<String, Object> profile(CognitoIdentity identity, String proof, String displayName) { return locked(identity, a -> {
        current(a, identity, proof, true); String name = a.name;
        if (displayName != null) {
            name = displayName.strip();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 160 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) throw failure(422, "INVALID_PROFILE");
            var tx = accounts.transaction(); accounts.control(tx, a.control, Map.of("displayName", s(name)), null); accounts.directory(tx, a.control, Map.of("displayName", s(name))); tx.commit();
        }
        return profileView(a, name);
    }); }
    @Override public Map<String, Object> preferences(CognitoIdentity identity, String proof, String theme) {
        if (theme == null || !Set.of("light", "dark", "system").contains(theme)) throw failure(422, "INVALID_PREFERENCES");
        return locked(identity, a -> {
            current(a, identity, proof, true);
            var tx = accounts.transaction(); accounts.fence(tx, a.control, false);
            if (get(client, table, account(a.id), "PREFERENCES").isEmpty()) tx.insertOnly(table, item(account(a.id), "PREFERENCES", attrs("theme", s(theme), "updatedAt", time(now()))));
            else tx.update(table, key(account(a.id), "PREFERENCES"), Map.of("theme", s(theme), "updatedAt", time(now())), null);
            tx.commit();
            return profileView(a, a.name);
        });
    }
    @Override public Map<String, Object> links(CognitoIdentity identity, String proof, Map<String, String> links) {
        if (links == null || !links.keySet().equals(Set.copyOf(ProfileLinkPolicy.KINDS))) throw ProfileLinkPolicy.invalidShape();
        var values = new LinkedHashMap<String, String>();
        for (String kind : ProfileLinkPolicy.KINDS) values.put(kind, ProfileLinkPolicy.validate(kind, links.get(kind)));
        return locked(identity, a -> {
            current(a, identity, proof, true);
            var map = new HashMap<String, AttributeValue>();
            values.forEach((kind, url) -> { if (url != null) map.put(kind, s(url)); });
            var stored = AttributeValue.builder().m(map).build();
            var tx = accounts.transaction(); accounts.fence(tx, a.control, false);
            if (get(client, table, account(a.id), "LINKS").isEmpty()) tx.insertOnly(table, item(account(a.id), "LINKS", attrs("links", stored)));
            else tx.update(table, key(account(a.id), "LINKS"), Map.of("links", stored), null);
            tx.commit();
            return profileView(a, a.name);
        });
    }
    private Map<String, Object> profileView(Account account, String name) {
        var preferences = get(client, table, account(account.id), "PREFERENCES");
        var links = new LinkedHashMap<String, String>();
        for (String kind : ProfileLinkPolicy.KINDS) links.put(kind, null);
        var stored = get(client, table, account(account.id), "LINKS").get("links");
        if (stored != null && stored.hasM()) stored.m().forEach((kind, url) -> { if (links.containsKey(kind)) links.put(kind, url.s()); });
        var methods = accounts.signInMethods(account.id, true);
        var view = new LinkedHashMap<String, Object>(Map.of("accountId", account.id, "username", account.username, "displayName", name,
            "signInMethod", account.provider, "signInMethods", methods, "themePreference", preferences.isEmpty() ? "system" : text(preferences, "theme"), "links", links));
        String shown = account.control.verifiedEmail() != null ? account.control.verifiedEmail() : methods.stream().map(m -> (String) m.get("email")).filter(Objects::nonNull).findFirst().orElse(null);
        if (shown != null) view.put("email", shown);
        return view;
    }
    @Override public void changeCredentials(CognitoIdentity identity, String proof, Runnable providerChange) {
        // Commit a fail-closed intent before crossing the provider/store boundary: a crash leaves all account access blocked.
        UUID account = locked(identity, a -> { current(a, identity, proof, false); var tx = accounts.transaction(); accounts.control(tx, a.control, Map.of("credentialChangePending", bool(true)), null); tx.commit(); return a.id; });
        try { providerChange.run(); }
        catch (AccountFailure rejected) {
            boolean knownRejection = Set.of("INVALID_CURRENT_PASSWORD", "INVALID_PASSWORD").contains(rejected.code());
            finishCredentialChange(identity, !knownRejection);
            (knownRejection ? Events.refused(Events.EVENT, "password.change", rejected.code()) : Events.failed(Events.EVENT, "password.change", rejected.code()))
                .addKeyValue("account.id", account)
                .log(knownRejection ? "Password change for account {} refused by the sign-in service: {}" : "Password change for account {} not confirmed by the sign-in service: {}; every sign-in of the account was ended", account, rejected.code());
            throw rejected;
        }
        catch (RuntimeException uncertain) { finishCredentialChange(identity, true); Events.failed(Events.EVENT, "password.change", "UNEXPECTED").addKeyValue("account.id", account).log("Password change for account {} failed: {}; every sign-in of the account was ended", account, Events.types(uncertain)); throw uncertain; }
        finishCredentialChange(identity, true);
        Events.success(Events.EVENT, "password.change").addKeyValue("account.id", account).log("Password changed for account {}; every sign-in of the account was ended", account);
    }
    private void finishCredentialChange(CognitoIdentity identity, boolean revoke) { locked(identity, true, false, a -> {
        var tx = accounts.transaction();
        var changes = new HashMap<String, AttributeValue>(Map.of("credentialChangePending", bool(false)));
        if (revoke) {
            changes.put("authGeneration", n(a.control.authGeneration() + 1));
            for (Challenge c : challenges(a.id)) if (!c.cancelled) tx.update(table, key(account(a.id), challengeSk(c.digest)), Map.of("cancelled", bool(true)), null);
        }
        accounts.control(tx, a.control, changes, null); tx.commit(); return null;
    }); }

    // ---- sessions ------------------------------------------------------------------------------------------------

    private Session current(Account a, CognitoIdentity identity, String proof, boolean touch) {
        Instant now = now(); String family = family(identity), binding = binding(proof);
        var rows = activeSessions(a, now).stream().filter(s -> s.family.equals(family) && s.binding.equals(binding)).toList();
        if (rows.size() != 1) throw denied(); Session result = rows.getFirst();
        // The touch is one conditional write on the session alone (no control fence: the control was read and checked above, and the
        // session dies with its generation anyway), and only once a minute, so a page's parallel calls do not contend on one item.
        if (touch && !result.active.plus(TOUCH).isAfter(now)) {
            var tx = accounts.transaction();
            tx.update(table, key(account(a.id), sessionSk(result.id)), Map.of("lastActiveAt", time(now)), "(\"revokedAt\" IS MISSING OR \"revokedAt\" IS NULL) AND \"authGeneration\"=?", n(a.control.authGeneration()));
            try { tx.commit(); } catch (DynamoWrites.Conflict raced) { /* revoked or re-issued meanwhile: the next call reads that */ }
        }
        return result;
    }
    private Challenge challenge(Account a, CognitoIdentity identity, String proof, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        var item = get(client, table, account(a.id), challengeSk(digest(token)));
        if (item.isEmpty()) throw invalid(); Challenge c = challenge(item);
        // A challenge issued under an earlier sign-in generation (Disable, Delete, a merge, "end other sign-ins") is dead with it, as the SQL cancels them.
        if (c.cancelled || c.generation != a.control.authGeneration() || !c.expires.isAfter(now()) || !c.family.equals(family(identity)) || !c.binding.equals(binding(proof))) throw invalid(); return c;
    }
    private List<Challenge> challenges(UUID owner) { return query(client, table, account(owner), "CHALLENGE#").stream().map(DynamoCloudSignInRegistry::challenge).toList(); }
    private static Challenge challenge(Map<String, AttributeValue> item) {
        return new Challenge(DynamoItems.string(item, "sk").substring("CHALLENGE#".length()), DynamoItems.string(item, "family"), DynamoItems.string(item, "binding"), DynamoItems.instant(item, "createdAt"),
            DynamoItems.instant(item, "expiresAt"), DynamoItems.instant(item, "authAt"), flag(item, "cancelled"), uuid(item, "selectedId"), uuid(item, "admittedId"), number(item, "authGeneration", 0));
    }
    List<Session> sessions(UUID owner) { return query(client, table, account(owner), "SESSION#").stream().map(DynamoCloudSignInRegistry::session).toList(); }
    private Session session(UUID owner, UUID id) { var item = get(client, table, account(owner), sessionSk(id)); return item.isEmpty() ? null : session(item); }
    private static Session session(Map<String, AttributeValue> item) {
        return new Session(DynamoItems.uuid(item, "signInId"), DynamoItems.string(item, "family"), DynamoItems.string(item, "binding"), DynamoItems.instant(item, "createdAt"),
            DynamoItems.instant(item, "lastActiveAt"), DynamoItems.instant(item, "recentAuthAt"), instant(item, "revokedAt"), number(item, "authGeneration", 0), text(item, "label"), text(item, "clientDescription"));
    }
    /** Not revoked, not idle for 30 minutes, not older than 7 days, issued under the current generation: the SQL expire() plus revocation. */
    static boolean active(Session s, Control control, Instant now) {
        return s.revoked == null && s.active.isAfter(now.minus(IDLE)) && s.created.isAfter(now.minus(ABSOLUTE)) && s.generation == control.authGeneration();
    }
    private List<Session> activeSessions(Account a, Instant now) {
        return sessions(a.id).stream().filter(s -> active(s, a.control, now)).sorted(Comparator.comparing(Session::created).thenComparing(s -> s.id.toString())).toList();
    }
    private List<Entry> entries(Account a, UUID current) {
        return activeSessions(a, now()).stream().map(s -> new Entry(s.id, Objects.equals(current, s.id), s.label, s.client, s.created, s.active)).toList();
    }
    private UUID insert(DynamoWrites tx, Account a, String family, String binding, Instant auth, Instant now) {
        UUID id = UUID.randomUUID();
        tx.insertOnly(table, item(account(a.id), sessionSk(id), attrs("signInId", s(id.toString()), "family", s(family), "binding", s(binding), "createdAt", time(now),
            "lastActiveAt", time(now), "recentAuthAt", time(auth), "authGeneration", n(a.control.authGeneration()))));
        tx.insertOnly(table, item(familyPk(family), "BINDING", attrs("accountId", s(a.id.toString()), "signInId", s(id.toString()), "binding", s(binding))));
        return id;
    }
    private void revoke(DynamoWrites tx, UUID owner, UUID id, Instant now) {
        tx.update(table, key(account(owner), sessionSk(id)), Map.of("revokedAt", time(now)), "(\"revokedAt\" IS MISSING OR \"revokedAt\" IS NULL)");
    }

    // ---- the account behind an identity ---------------------------------------------------------------------------

    private <T> T locked(CognitoIdentity identity, Function<Account, T> action) { return locked(identity, false, false, action); }
    /**
     * firstContact (sign-in admission only): an unknown identity is linked to the one account with its verified email, or
     * recorded as a new waiting account, and an unadmitted account is refreshed from the provider claims; all commit
     * before the 403 is thrown. A lost revision race is re-read and retried; the last conflict surfaces as it is.
     */
    private <T> T locked(CognitoIdentity identity, boolean allowCredentialChange, boolean firstContact, Function<Account, T> action) {
        DynamoWrites.Conflict last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try { return attempt(identity, allowCredentialChange, firstContact, action); }
            catch (DynamoWrites.Conflict conflict) { last = conflict; }
        }
        throw last;
    }
    private <T> T attempt(CognitoIdentity identity, boolean allowCredentialChange, boolean firstContact, Function<Account, T> action) {
        Instant now = now();
        var binding = accounts.binding(identity.issuer(), identity.subject());
        Control row; boolean linked = false; String provider;
        if (binding.isEmpty()) {
            if (!firstContact) throw admissionRequired();
            var linking = link(identity, now);
            provider = CloudIdentities.provider(identity.issuer(), identity.username());
            if (linking.linked == null) {
                UUID created = request(identity, now, linking.emailRevision); row = accounts.control(created);
                if (row == null) throw denied();
                if (!row.admitted()) throw waiting(created, "created");
                binding = accounts.binding(identity.issuer(), identity.subject());
            } else { linked = true; row = accounts.control(linking.linked.id()); if (row == null) throw denied(); }
        } else {
            UUID id = DynamoItems.uuid(binding, "accountId"); provider = DynamoItems.string(binding, "provider");
            row = accounts.control(id); if (row == null) throw denied();
        }
        // DLV-969: an account the owner disabled or deleted has no sign-in, even before the pool is told.
        if (!row.enabled() || !row.active()) {
            String reason = row.removed() ? "ACCOUNT_REMOVED" : "ACCOUNT_DISABLED";
            Events.refused(Events.EVENT, "sign-in.check", reason).addKeyValue("account.id", row.id()).log("Sign-in refused for account {}: {}", row.id(), reason.equals("ACCOUNT_REMOVED") ? "the owner deleted it" : "the owner disabled it");
            throw denied();
        }
        // DLV-969 S36: a sign-in just linked to a waiting account is "linked"; the owner still has to admit it.
        if (!row.admitted()) { if (!firstContact) throw denied(); if (!linked) refresh(identity, row, binding, now); throw waiting(row.id(), linked ? "linked" : "refreshed"); }
        if (firstContact && !linked) row = refreshEmail(identity, row, binding, now);
        // Owner identity (Protected Owner Identity, issuer/subject) decides author access; it is recorded on the control item at sign-in, never from a profile field.
        if (firstContact && !row.author() && ownerIdentity(identity)) { var tx = accounts.transaction(); accounts.control(tx, row, Map.of("author", bool(true)), null); accounts.directory(tx, row, Map.of("author", bool(true))); tx.commit(); row = accounts.control(row.id()); }
        if (!allowCredentialChange && row.credentialChangePending()) {
            Events.refused(Events.EVENT, "sign-in.check", "CREDENTIAL_CHANGE_PENDING").addKeyValue("account.id", row.id()).log("Request refused for account {}: a password change is not confirmed yet (503)", row.id());
            throw failure(503, "CREDENTIAL_CHANGE_PENDING");
        }
        return action.apply(new Account(row.id(), row.username(), row.displayName(), row.author(), provider, row));
    }
    private boolean ownerIdentity(CognitoIdentity identity) { return owner.matchesVerifiedIdentity(identity.issuer(), identity.subject()); }
    private AccountFailure waiting(UUID id, String action) {
        // Committed: the owner now sees the waiting account on People and access.
        Events.refused(Events.EVENT, "admission.waiting-" + action, "ACCOUNT_ADMISSION_REQUIRED").addKeyValue("account.id", id)
            .log("Sign-in refused for account {}: not admitted yet; waiting row {} (removed after {} h unless the owner admits it)", id, action, CloudAdmissionCleanup.WAITING_EXPIRY.toHours());
        return admissionRequired();
    }
    /**
     * DLV-969 S36 (owner decision 2026-11-04): an unknown identity with a provider-verified email joins the one eligible
     * account that already has that email. Zero or several candidates, no verified email, or a candidate disabled or
     * deleted meanwhile: nothing linked, and the caller records a new waiting account. The per-email sentinel, read
     * before the candidates and advanced in the same transaction, serialises a Google and a password first contact.
     */
    private Linking link(CognitoIdentity identity, Instant now) {
        String email = email(identity);
        if (email == null) return new Linking(null, 0);
        long emailRevision = accounts.emailRevision(email);
        var candidates = accounts.candidates(email);
        String provider = CloudIdentities.provider(identity.issuer(), identity.username());
        if (candidates.size() > 1) {
            int several = candidates.size();
            Events.success(Events.EVENT, "identity.link-skipped").addKeyValue("reason", "SEVERAL_SAME_EMAIL_ACCOUNTS").addKeyValue("provider", provider).addKeyValue("count", several)
                .log("New {} sign-in not linked: {} eligible accounts already share its verified email; recorded as a new waiting account", provider, several);
            return new Linking(null, emailRevision);
        }
        if (candidates.size() != 1) return new Linking(null, emailRevision);
        Control row = candidates.getFirst();
        var identityRecord = new Identity(UUID.randomUUID(), identity.issuer(), identity.subject(), identity.username(), provider, email, "linked", now);
        var tx = accounts.transaction();
        accounts.fence(tx, row, true);
        accounts.identity(tx, row.id(), identityRecord);
        accounts.emailCarrier(tx, email, row.id(), row.issuer(), List.of(identityRecord.identityId().toString()));
        accounts.emailSentinel(tx, email, emailRevision);
        var usernames = new ArrayList<>(accounts.usernames(row.id())); usernames.add(identity.username());
        accounts.directory(tx, row, Map.of("usernames", list(usernames.stream().map(CloudAccountItems::lower).toList())));
        accounts.admission(tx, row.id(), identity.issuer(), identity.subject(), null, "link", List.of(), now);
        tx.commit();
        Events.success(Events.EVENT, "identity.linked").addKeyValue("account.id", row.id()).addKeyValue("provider", provider).addKeyValue("admitted", row.admitted())
            .log("New {} sign-in linked to account {}: its verified email matched exactly one eligible account{}", provider, row.id(), row.admitted() ? "" : " (still waiting for the owner to admit it)");
        return new Linking(row, emailRevision);
    }
    private UUID request(CognitoIdentity identity, Instant now, long emailRevision) {
        UUID id = UUID.randomUUID(); String email = email(identity);
        String provider = CloudIdentities.provider(identity.issuer(), identity.username());
        // DLV-970: the owner identity (Protected Owner Identity) is admitted at its own first contact. On PostgreSQL the operator's
        // command-line admit did this; on DynamoDB nothing else could, and the protected table is the reviewed admission decision.
        boolean author = ownerIdentity(identity);
        var control = new Control(id, identity.issuer(), identity.subject(), identity.username(), identity.displayName(), email, author, true, author, false, false, "ACTIVE", 1, 0, now, author ? now : null);
        var identityRecord = new Identity(UUID.randomUUID(), identity.issuer(), identity.subject(), identity.username(), provider, email, "first_contact", now);
        var tx = accounts.transaction();
        tx.insertOnly(table, item(account(id), "META", attrs("revision", n(1), "lastPlanNumber", n(0), "createdAt", time(now))));
        tx.insertOnly(table, item(account(id), "CONTROL", attrs("issuer", s(identity.issuer()), "subject", s(identity.subject()), "username", s(identity.username()),
            "displayName", s(identity.displayName()), "verifiedEmail", s(email), "admitted", bool(author), "author", bool(author), "managedTest", bool(false), "enabled", bool(true),
            "credentialChangePending", bool(false), "accountState", s("ACTIVE"), "revision", n(1), "authGeneration", n(0), "requestedAt", time(now), "admittedAt", author ? time(now) : null)));
        accounts.identity(tx, id, identityRecord);
        if (email != null) { accounts.emailCarrier(tx, email, id, identity.issuer(), List.of(identityRecord.identityId().toString(), "account")); accounts.emailSentinel(tx, email, emailRevision); }
        if (!author) tx.insertOnly(table, item(WAITING, waitingSk(now, id), attrs("accountId", s(id.toString()), "requestedAt", time(now))));
        tx.insertOnly(table, accounts.directoryItem(control, List.of(identity.username())));
        accounts.admission(tx, id, identity.issuer(), identity.subject(), null, "request", List.of(), now);
        if (author) accounts.admission(tx, id, identity.issuer(), identity.subject(), null, "admit", List.of(), now);
        tx.commit();
        if (author) {
            Events.success(Events.EVENT, "admission.admitted-owner").addKeyValue("account.id", id)
                .log("Account {} admitted at first contact: its sign-in is the protected owner identity", id);
            return id;
        }
        // DLV-971 (owner, 2026-11-07): prepared access by email is a pass through the DEV gate: the invitation is claimed
        // for the new account and the account admitted at once, so the owner never sees it waiting.
        var prepared = access == null ? null : access.claimPrepared(id, email);
        if (prepared != null) {
            var roles = new TreeSet<>(prepared.roles());
            var admit = accounts.transaction(); accounts.admit(admit, control, null, List.copyOf(roles), now); admit.commit();
            Events.success(Events.EVENT, "admission.admitted-by-invitation").addKeyValue("account.id", id).addKeyValue("target.id", prepared.id()).addKeyValue("roles", roles)
                .log("Account {} admitted at first contact by prepared access {}: roles {}", id, prepared.id(), roles.isEmpty() ? "none (learner)" : roles);
        }
        return id;
    }
    /** The identity's own claims always; the account's profile only from its first identity. */
    private void refresh(CognitoIdentity identity, Control row, Map<String, AttributeValue> binding, Instant now) {
        String email = email(identity); boolean primary = row.issuer().equals(identity.issuer()) && row.subject().equals(identity.subject());
        var tx = accounts.transaction(); var carriers = new DynamoCloudAccounts.Carriers();
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
            if (!Objects.equals(text(binding, "username"), identity.username())) accounts.directory(tx, row, Map.of("usernames", list(usernamesWith(row, binding, identity.username()).stream().map(CloudAccountItems::lower).toList())));
        }
        accounts.carriers(tx, carriers, row.id(), row.issuer());
        tx.commit();
    }
    /** The identity's verified email, and the account's when it is the first identity; only a changed value is written. */
    private Control refreshEmail(CognitoIdentity identity, Control row, Map<String, AttributeValue> binding, Instant now) {
        String email = email(identity); boolean primary = row.issuer().equals(identity.issuer()) && row.subject().equals(identity.subject());
        boolean identityChanged = !Objects.equals(text(binding, "verifiedEmail"), email), accountChanged = primary && !Objects.equals(row.verifiedEmail(), email);
        if (!identityChanged && !accountChanged) return row;
        var tx = accounts.transaction(); var carriers = new DynamoCloudAccounts.Carriers();
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
    private void identityClaims(DynamoWrites tx, DynamoCloudAccounts.Carriers carriers, CognitoIdentity identity, Control row, Map<String, AttributeValue> binding, String username, String email) {
        UUID identityId = DynamoItems.uuid(binding, "identityId"); String old = text(binding, "verifiedEmail");
        var set = attrs("username", s(username), "verifiedEmail", email == null ? DynamoItems.nul() : s(email));
        tx.update(table, key(identityPk(identity.issuer(), identity.subject()), "BINDING"), set, null);
        tx.update(table, key(account(row.id()), identitySk(DynamoItems.instant(binding, "linkedAt"), identityId)), set, null);
        if (!Objects.equals(old, email)) { if (old != null) carriers.remove(old, identityId.toString()); if (email != null) carriers.add(email, identityId.toString()); }
    }
    private List<String> usernamesWith(Control row, Map<String, AttributeValue> binding, String username) {
        UUID identityId = DynamoItems.uuid(binding, "identityId");
        var names = new ArrayList<String>();
        for (Identity i : accounts.identities(row.id())) { if ("test".equals(i.provider())) continue; names.add(i.identityId().equals(identityId) ? username : i.username()); }
        return names;
    }
    private static String email(CognitoIdentity identity) { String email = identity.verifiedEmail(); return email == null || email.length() > 254 ? null : email; }
    private Instant now() { return accounts.now(); }
    private static void requireFresh(Instant auth, Instant now) { if (auth.isAfter(now.plusSeconds(60)) || !auth.plus(Duration.ofMinutes(5)).isAfter(now)) throw failure(403, "RECENT_AUTHENTICATION_REQUIRED"); }
    private static String binding(String proof) { if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) throw denied(); return CloudSignInRegistry.digest(proof); }
    private static String family(CognitoIdentity identity) { return CloudSignInRegistry.digest(identity.issuer() + "\n" + identity.subject() + "\n" + identity.family()); }
    private static String randomToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static AccountFailure failure(int status, String code) { return new AccountFailure(status, code, "Sign in again or contact the account administrator."); }
    private static AccountFailure denied() { return failure(401, "AUTHENTICATION_REQUIRED"); }
    private static AccountFailure admissionRequired() { return failure(403, "ACCOUNT_ADMISSION_REQUIRED"); }
    private static AccountFailure invalid() { return failure(400, "SIGN_IN_CHALLENGE_INVALID"); }
}
