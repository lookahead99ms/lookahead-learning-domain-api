package com.lookahead.domain.db.store.signin;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.model.SignInIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the account's own items on DynamoDB (the control item, its identities, the admission events, the email and
 * directory projections), shared by the sign-in, DEV access, cleanup, removal, merge and directory stores. Reads are
 * strongly consistent; write fragments are appended to one {@link DynamoWrites} transaction so every projection and
 * sentinel commits with its source. No decision is made here: callers keep validation, eligibility and the refusals.
 * The by-email reads leave out what is never an account of a person: the test issuer's accounts and deleted ones.
 */
public final class AccountAggregateStore {
    /** The {@code ACCOUNT#id / CONTROL} item: the account and its state, in the shape {@code AccountControlStore} reads. */
    public record Control(UUID id, String issuer, String subject, String username, String displayName, String verifiedEmail,
            boolean admitted, boolean enabled, boolean author, boolean managedTest, boolean credentialChangePending,
            String state, long revision, long authGeneration, Instant requestedAt, Instant admittedAt) {
        public boolean active() { return "ACTIVE".equals(state); }
        public boolean removed() { return "REMOVED".equals(state); }
        public boolean disabled() { return "DISABLED".equals(state); }
        /** The account state as the merge reads it: null (active), "disabled" or "removed". */
        public String stateLabel() { return active() ? null : removed() ? "removed" : "disabled"; }
    }
    /** One provider identity of an account, oldest first by {@code linkedAt}. */
    public record Identity(UUID identityId, String issuer, String subject, String username, String provider, String verifiedEmail,
            String linkSource, Instant linkedAt) {}

    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    public AccountAggregateStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DynamoDbClient client() { return client; }
    public Instant now() { return clock.instant(); }
    public String accounts() { return tables.userAccountDetailsTableName(); }
    public String audit() { return tables.administratorAuditEventsTableName(); }
    public DynamoWrites transaction() { return DynamoWrites.transaction(client); }

    // ---- reads ---------------------------------------------------------------------------------------------------

    /** The account's control item, or null when there is no such account. */
    public Control control(UUID id) {
        var item = get(client, accounts(), account(id), "CONTROL");
        return item.isEmpty() ? null : parse(id, item);
    }

    public static Control parse(UUID id, Map<String, AttributeValue> item) {
        DynamoItems.requireSchema(item, SCHEMA);
        return new Control(id, DynamoItems.string(item, "issuer"), DynamoItems.string(item, "subject"), DynamoItems.string(item, "username"),
                text(item, "displayName"), text(item, "verifiedEmail"), flag(item, "admitted"), flag(item, "enabled"), flag(item, "author"),
                flag(item, "managedTest"), flag(item, "credentialChangePending"), DynamoItems.string(item, "accountState"),
                DynamoItems.number(item, "revision"), number(item, "authGeneration", 0), instant(item, "requestedAt"), instant(item, "admittedAt"));
    }

    /** The account of one (issuer, subject): its binding, or null. */
    public UUID accountOf(String issuer, String subject) {
        var item = get(client, accounts(), identityPk(issuer, subject), "BINDING");
        return item.isEmpty() ? null : DynamoItems.uuid(item, "accountId");
    }

    public Map<String, AttributeValue> binding(String issuer, String subject) {
        return get(client, accounts(), identityPk(issuer, subject), "BINDING");
    }

    /** Every identity of the account, oldest first. */
    public List<Identity> identities(UUID id) {
        return query(client, accounts(), account(id), "IDENTITY#").stream().map(AccountAggregateStore::identity).toList();
    }

    static Identity identity(Map<String, AttributeValue> item) {
        return new Identity(DynamoItems.uuid(item, "identityId"), DynamoItems.string(item, "issuer"), DynamoItems.string(item, "subject"),
                text(item, "username"), DynamoItems.string(item, "provider"), text(item, "verifiedEmail"), text(item, "linkSource"),
                DynamoItems.instant(item, "linkedAt"));
    }

    /** The account's sign-in methods, oldest first: provider and, when verified, email; test identities left out unless asked. */
    public List<Map<String, Object>> signInMethods(UUID id, boolean includeTest) {
        var methods = new ArrayList<Map<String, Object>>();
        for (Identity identity : identities(id)) {
            if (!includeTest && "test".equals(identity.provider())) continue;
            var method = new LinkedHashMap<String, Object>();
            method.put("provider", identity.provider());
            if (identity.verifiedEmail() != null) method.put("email", identity.verifiedEmail());
            methods.add(Collections.unmodifiableMap(method));
        }
        return List.copyOf(methods);
    }

    /** Every Cognito username the account holds now, oldest first (test identities left out). */
    public List<String> usernames(UUID id) {
        return identities(id).stream().filter(i -> !"test".equals(i.provider())).map(Identity::username).filter(Objects::nonNull).toList();
    }

    /** The revision of the per-email sentinel; 0 before the first write. */
    public long emailRevision(String email) {
        return number(get(client, accounts(), emailPk(email), "META"), "revision", 0);
    }

    /** Accounts that carry {@code email} through any identity or their own verified email, test-issuer accounts left out. */
    public List<UUID> emailAccounts(String email) {
        return query(client, accounts(), emailPk(email), "ACCOUNT#").stream().filter(item -> !testIssuer(item))
                .map(item -> DynamoItems.uuid(item, "accountId")).sorted().toList();
    }

    /** {@link #emailAccounts} with their controls, deleted accounts and missing controls left out. */
    public List<Control> emailControls(String email) {
        var controls = new ArrayList<Control>();
        for (UUID id : emailAccounts(email)) {
            Control control = control(id);
            if (control != null && !control.removed()) controls.add(control);
        }
        return controls;
    }

    /** DLV-969 S36: how many OTHER accounts, not deleted and not test accounts, share the verified email. */
    public int sameEmailAccounts(UUID account, String email) {
        if (email == null || email.isBlank()) return 0;
        return (int) emailControls(email).stream().filter(c -> !c.id().equals(account)).count();
    }

    // ---- write fragments (PartiQL: INSERT needs an absent key, UPDATE an existing one; one statement per item) ----

    /** Conditions the transaction on the control item as read; {@code bump} also advances its revision (row lock + write). */
    public void fence(DynamoWrites tx, Control control, boolean bump) {
        String where = "\"revision\"=? AND \"authGeneration\"=?";
        if (bump) tx.update(accounts(), key(account(control.id()), "CONTROL"), Map.of("revision", n(control.revision() + 1)), where, n(control.revision()), n(control.authGeneration()));
        else tx.check(accounts(), key(account(control.id()), "CONTROL"), SCHEMA, where, n(control.revision()), n(control.authGeneration()));
    }

    /** Changes control attributes at the read revision (advancing it); {@code where} adds conditions with {@code ?} parameters. */
    public void control(DynamoWrites tx, Control control, Map<String, AttributeValue> changes, String where, AttributeValue... parameters) {
        var set = new HashMap<String, AttributeValue>(changes);
        set.put("revision", n(control.revision() + 1));
        var values = new ArrayList<AttributeValue>(); values.add(n(control.revision())); values.addAll(List.of(parameters));
        tx.update(accounts(), key(account(control.id()), "CONTROL"), set, "\"revision\"=?" + (where == null ? "" : " AND " + where), values.toArray(AttributeValue[]::new));
    }

    /** The per-email sentinel: advances it from the revision the caller read, so two writers for one email serialise. */
    public void emailSentinel(DynamoWrites tx, String email, long expectedRevision) {
        if (expectedRevision == 0) tx.insertOnly(accounts(), item(emailPk(email), "META", attrs("revision", n(1))));
        else tx.update(accounts(), key(emailPk(email), "META"), Map.of("revision", n(expectedRevision + 1)), "\"revision\"=?", n(expectedRevision));
    }

    /** Records that {@code account} carries {@code email} through {@code sources} ("account" or identity ids); read first, one statement. */
    public void emailCarrier(DynamoWrites tx, String email, UUID account, String issuer, List<String> sources) {
        var item = get(client, accounts(), emailPk(email), account(account));
        var all = new ArrayList<>(strings(item, "sources"));
        for (String source : sources) if (!all.contains(source)) all.add(source);
        if (item.isEmpty()) tx.insertOnly(accounts(), item(emailPk(email), account(account), attrs("accountId", s(account.toString()), "issuer", s(issuer), "sources", list(all))));
        else if (all.size() != strings(item, "sources").size()) tx.update(accounts(), key(emailPk(email), account(account)), Map.of("sources", list(all)), null);
    }

    /** Carrier additions and removals of one transaction, coalesced so each {@code EMAIL#email / ACCOUNT#id} item is written once. */
    public static final class Carriers {
        private final LinkedHashMap<String, List<String>> adds = new LinkedHashMap<>(), removes = new LinkedHashMap<>();
        public void add(String email, String source) { adds.computeIfAbsent(SignInIdentity.normalizeEmail(email), e -> new ArrayList<>()).add(source); }
        public void remove(String email, String source) { removes.computeIfAbsent(SignInIdentity.normalizeEmail(email), e -> new ArrayList<>()).add(source); }
    }

    /** Applies coalesced carrier changes for one account: one read and one statement per email. */
    public void carriers(DynamoWrites tx, Carriers changes, UUID account, String issuer) {
        var emails = new LinkedHashSet<>(changes.adds.keySet()); emails.addAll(changes.removes.keySet());
        for (String email : emails) {
            var item = get(client, accounts(), emailPk(email), account(account));
            var sources = new ArrayList<>(strings(item, "sources"));
            sources.removeAll(changes.removes.getOrDefault(email, List.of()));
            for (String source : changes.adds.getOrDefault(email, List.of())) if (!sources.contains(source)) sources.add(source);
            if (item.isEmpty()) { if (!sources.isEmpty()) tx.insertOnly(accounts(), item(emailPk(email), account(account), attrs("accountId", s(account.toString()), "issuer", s(issuer), "sources", list(sources)))); }
            else if (sources.isEmpty()) tx.delete(accounts(), key(emailPk(email), account(account)), null);
            else if (!sources.equals(strings(item, "sources"))) tx.update(accounts(), key(emailPk(email), account(account)), Map.of("sources", list(sources)), null);
        }
    }

    /** Records one identity: its binding (unique per issuer/subject) and the account's identity list entry. */
    public void identity(DynamoWrites tx, UUID account, Identity identity) {
        tx.insertOnly(accounts(), item(identityPk(identity.issuer(), identity.subject()), "BINDING", attrs(
                "accountId", s(account.toString()), "identityId", s(identity.identityId().toString()), "username", s(identity.username()),
                "provider", s(identity.provider()), "verifiedEmail", s(identity.verifiedEmail()), "linkSource", s(identity.linkSource()),
                "linkedAt", time(identity.linkedAt()))));
        tx.insertOnly(accounts(), item(account(account), identitySk(identity.linkedAt(), identity.identityId()), attrs(
                "identityId", s(identity.identityId().toString()), "issuer", s(identity.issuer()), "subject", s(identity.subject()),
                "username", s(identity.username()), "provider", s(identity.provider()), "verifiedEmail", s(identity.verifiedEmail()),
                "linkSource", s(identity.linkSource()), "linkedAt", time(identity.linkedAt()))));
    }

    /** One insert-only admission event: request, link, admit, expire, merge, disable, enable, remove. */
    public void admission(DynamoWrites tx, UUID account, String issuer, String subject, UUID actor, String action, List<String> roles, Instant at) {
        tx.insertOnly(audit(), item(account(account), admissionSk(at, UUID.randomUUID()), attrs(
                "action", s(action), "actorId", actor == null ? null : s(actor.toString()), "issuer", s(issuer), "subject", s(subject),
                "roles", list(roles), "occurredAt", time(at))));
    }

    /** The directory projection of an account, with the lower-cased identity usernames for exact lookups. */
    public Map<String, AttributeValue> directoryItem(Control c, List<String> identityUsernames) {
        return item(DIRECTORY, personSk(c.username(), c.id()), attrs("accountId", s(c.id().toString()), "username", s(c.username()),
                "displayName", s(c.displayName()), "verifiedEmail", s(c.verifiedEmail()), "admitted", bool(c.admitted()), "enabled", bool(c.enabled()),
                "author", bool(c.author()), "managedTest", bool(c.managedTest()), "accountState", s(c.state()),
                "usernames", list(identityUsernames.stream().map(AccountItems::lower).toList())));
    }

    /** Changes attributes of the existing directory projection (the username, hence the key, unchanged). */
    public void directory(DynamoWrites tx, Control c, Map<String, AttributeValue> set) {
        tx.update(accounts(), key(DIRECTORY, personSk(c.username(), c.id())), set, null);
    }

    /** Admits a waiting account: admitted on control, the waiting projection gone, the directory updated, one event. */
    public void admit(DynamoWrites tx, Control c, UUID actor, List<String> roles, Instant now) {
        control(tx, c, attrs("admitted", bool(true), "admittedAt", time(now), "admittedBy", actor == null ? DynamoItems.nul() : s(actor.toString())),
                "\"admitted\"=? AND \"accountState\"=?", bool(false), s("ACTIVE"));
        if (c.requestedAt() != null) tx.delete(accounts(), key(WAITING, waitingSk(c.requestedAt(), c.id())), null);
        directory(tx, c, Map.of("admitted", bool(true)));
        admission(tx, c.id(), c.issuer(), c.subject(), actor, "admit", roles, now);
    }

    /** One insert-only {@code merge} admission event: the account went into {@code into} by {@code actor}'s merge. */
    public void merged(DynamoWrites tx, UUID account, String issuer, String subject, UUID actor, UUID into, Instant at) {
        tx.insertOnly(audit(), item(account(account), admissionSk(at, UUID.randomUUID()), attrs(
                "action", s("merge"), "actorId", s(actor.toString()), "issuer", s(issuer), "subject", s(subject),
                "roles", list(List.of()), "into", s(into.toString()), "occurredAt", time(at))));
    }

    /**
     * Deletes an admitted account the way the control library's REMOVE does, inside the caller's transaction (a merge,
     * which no owner command drives): the control at its read revision moves to REMOVED with the next sign-in
     * generation, the terminal marker goes to the audit table and the erasure job is opened in phase ERASE. The caller
     * queues and runs the erasure after the commit; a retry of Delete or the sweep finishes an interrupted one.
     */
    public void remove(DynamoWrites tx, Control c, UUID actor, UUID operation, Instant at) {
        long revision = c.revision() + 1, generation = c.authGeneration() + 1;
        tx.update(accounts(), key(account(c.id()), "CONTROL"), attrs("accountState", s("REMOVED"), "revision", n(revision), "authGeneration", n(generation),
                "changedAt", time(at), "changedBy", s(actor.toString())),
                "\"revision\"=? AND \"authGeneration\"=? AND \"accountState\"=? AND \"admitted\"=? AND \"author\"=? AND \"managedTest\"=?",
                n(c.revision()), n(c.authGeneration()), s("ACTIVE"), bool(true), bool(false), bool(false));
        tx.insertOnly(audit(), item(account(c.id()), "REMOVED", attrs("accountState", s("REMOVED"), "revision", n(revision), "authGeneration", n(generation),
                "issuer", s(c.issuer()), "subject", s(c.subject()), "username", s(c.username()), "admitted", bool(true), "author", bool(false), "managedTest", bool(false),
                "operationId", s(operation.toString()), "changedAt", time(at), "changedBy", s(actor.toString()))));
        tx.insertOnly(accounts(), item(removalJobPk(c.id()), "JOB", attrs("operationId", s(operation.toString()), "revision", n(revision), "authGeneration", n(generation),
                "issuer", s(c.issuer()), "subject", s(c.subject()), "username", s(c.username()), "phase", s("ERASE"))));
    }

    /**
     * Deletes a waiting account and everything still on it (the 48-hour expiry, Reject and a merge remove one only this
     * way): control (only while not admitted, at the read revision), subject, bindings, identity list, email carriers,
     * waiting and directory projections, preferences and links. The caller records the event.
     */
    public void deleteWaiting(DynamoWrites tx, Control c, List<Identity> identities) {
        var carriers = new Carriers();
        for (Identity identity : identities) {
            tx.delete(accounts(), key(identityPk(identity.issuer(), identity.subject()), "BINDING"), null);
            tx.delete(accounts(), key(account(c.id()), identitySk(identity.linkedAt(), identity.identityId())), null);
            if (identity.verifiedEmail() != null) carriers.remove(identity.verifiedEmail(), identity.identityId().toString());
        }
        if (c.verifiedEmail() != null) carriers.remove(c.verifiedEmail(), "account");
        carriers(tx, carriers, c.id(), c.issuer());
        deleteWaitingRecords(tx, c);
    }

    /** A waiting account's own records (control, subject, projections, settings); the identities and email carriers are the caller's. */
    public void deleteWaitingRecords(DynamoWrites tx, Control c) {
        tx.delete(accounts(), key(account(c.id()), "CONTROL"), "\"revision\"=? AND \"admitted\"=?", n(c.revision()), bool(false));
        tx.delete(accounts(), key(account(c.id()), "META"), null);
        if (c.requestedAt() != null) tx.delete(accounts(), key(WAITING, waitingSk(c.requestedAt(), c.id())), null);
        tx.delete(accounts(), key(DIRECTORY, personSk(c.username(), c.id())), null);
        tx.delete(accounts(), key(account(c.id()), "PREFERENCES"), null);
        tx.delete(accounts(), key(account(c.id()), "LINKS"), null);
    }
}
