package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Carriers;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Control;
import com.lookahead.domain.cloud.DynamoCloudAccounts.Identity;
import com.lookahead.domain.Events;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasVerifier;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.domain.testaccounts.TestAccountRoster;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.lookahead.domain.cloud.CloudAccountItems.DIRECTORY;
import static com.lookahead.domain.cloud.CloudAccountItems.STAFF_ACCESS;
import static com.lookahead.domain.cloud.CloudAccountItems.WAITING;
import static com.lookahead.domain.cloud.CloudAccountItems.account;
import static com.lookahead.domain.cloud.CloudAccountItems.assignmentSk;
import static com.lookahead.domain.cloud.CloudAccountItems.attrs;
import static com.lookahead.domain.cloud.CloudAccountItems.bool;
import static com.lookahead.domain.cloud.CloudAccountItems.get;
import static com.lookahead.domain.cloud.CloudAccountItems.invitePk;
import static com.lookahead.domain.cloud.CloudAccountItems.item;
import static com.lookahead.domain.cloud.CloudAccountItems.key;
import static com.lookahead.domain.cloud.CloudAccountItems.n;
import static com.lookahead.domain.cloud.CloudAccountItems.query;
import static com.lookahead.domain.cloud.CloudAccountItems.s;
import static com.lookahead.domain.cloud.CloudAccountItems.staffEventPk;
import static com.lookahead.domain.cloud.CloudAccountItems.staffEventSk;
import static com.lookahead.domain.cloud.CloudAccountItems.time;
import static com.lookahead.domain.cloud.CloudAccountItems.waitingSk;

/**
 * DLV-970 step 5 (owner decision 2026-11-17: import accounts and access only). The PostgreSQL platform database the
 * Domain served before the cutover is read once, and every real account (not a managed test account, not removed)
 * is written into the DynamoDB store in the shape the running application writes at first contact, admission and
 * staff assignment, with its PostgreSQL account id, so People and access shows the same people with the same roles
 * and labels and nobody is admitted twice. Study plans, purchases, receipts and reviews are not imported (none of
 * the real accounts had any); the test roster is created again from the Test accounts page.
 *
 * <p>Idempotent and resumable: one transaction per account, an account whose control item already says
 * {@code importedFrom postgresql} is skipped, counters only ever rise, the result marker is upserted. The store is
 * expected to be out of service during the import (the service switched back to PostgreSQL): sign-in waiting rows it
 * recorded after the cutover are removed first, with an {@code expire} admission event, because their people are
 * imported with their PostgreSQL identity; an admitted account the import does not know is refused, never deleted.
 */
final class AccountAccessImport implements DynamoMigrationStep {
    static final int VERSION = 5;
    static final String NAME = "account-access-import";
    static final String SOURCE = "postgresql";
    static final String MARKER_PK = "IMPORT#account-access", MARKER_SK = "RESULT";
    static final List<String> ROLES = List.of("co-author", "admin", "reviewer", "tester", "learner");
    /** PostgreSQL sequence → the step-4 counter partition it continues. */
    static final Map<String, String> SEQUENCES = new LinkedHashMap<>(Map.of("review_record_seq", "SEQUENCE#review-record", "support_request_seq", "SEQUENCE#support-request"));
    static final int MAX_STATEMENTS = DynamoWrites.MAX_ITEMS;

    private final ObjectMapper json = JsonMapper.builder().build();

    @Override public int version() { return VERSION; }
    @Override public String name() { return NAME; }
    @Override public String definition() {
        return "Import the real accounts of the PostgreSQL platform database (cloud_accounts rows without a test_account_registry row, not on the"
                + " test issuer, not removed) into user-account-details and administrator-audit-events with their PostgreSQL ids: META and CONTROL"
                + " (importedFrom postgresql), cloud_identities as IDENTITY bindings, EMAIL carriers and sentinels, the DIRECTORY projection, WAITING"
                + " while not admitted, the staff_access assignment with its STAFF pointer and staff_access_events, cloud_admission_events as"
                + " ADMISSION events at their recorded times, staff_aliases as immutable number claims; live pending staff invitations as INVITE"
                + " pointers; STAFF_ROLE counters raised to the highest imported number; SEQUENCE#review-record and SEQUENCE#support-request raised"
                + " to the PostgreSQL sequence values. Waiting sign-in rows recorded before the import are removed with an expire event; an admitted"
                + " account unknown to the import is refused. One transaction per account, imported accounts are skipped on a rerun; the result is"
                + " IMPORT#account-access / RESULT. A local store without a PostgreSQL source imports nothing.";
    }

    @Override public void apply(DynamoDbClient client, DynamoTableNames tables) { apply(client, tables, DynamoMigrationSources.none()); }

    @Override public void apply(DynamoDbClient client, DynamoTableNames tables, DynamoMigrationSources sources) {
        var accounts = new DynamoCloudAccounts(client, tables, Clock.systemUTC());
        if (sources.postgres().isEmpty()) {
            if (!sources.local()) sources.requirePostgres("Data step " + VERSION + " " + NAME);
            marker(client, tables, List.of(), 0, Instant.now());
            Events.success(DynamoMigrationRunner.LOG, "migration.import").addKeyValue("count", 0).log("Account import: no PostgreSQL source on a local store; nothing imported");
            return;
        }
        var jdbc = new JdbcTemplate(sources.postgres().get());
        List<Person> people = read(jdbc);
        var ids = new java.util.HashSet<UUID>(); for (Person person : people) ids.add(person.id());
        int removed = clearSignInRows(accounts, ids);
        int imported = 0;
        var highest = new HashMap<String, Integer>();
        for (Person person : people) {
            for (Alias alias : person.aliases()) highest.merge(alias.role(), alias.number(), Math::max);
            if (importOne(accounts, person)) imported++;
        }
        int invitations = 0;
        for (Assignment pending : pendingInvitations(jdbc)) if (importInvitation(accounts, pending)) invitations++;
        raiseCounters(client, tables, highest, sequences(jdbc));
        marker(client, tables, people.stream().map(Person::id).toList(), removed, Instant.now());
        Events.success(DynamoMigrationRunner.LOG, "migration.import").addKeyValue("count", imported).addKeyValue("accounts", people.size())
                .addKeyValue("invitations", invitations).addKeyValue("removed", removed)
                .log("Account import: {} of {} real account(s) written now, {} pending invitation(s), {} waiting sign-in row(s) removed", imported, people.size(), invitations, removed);
    }

    /** Read-only, on the store alone: the marker names every imported account, each one is there as imported; the alias invariants hold. */
    @Override public void verify(DynamoDbClient client, DynamoTableNames tables) {
        String table = tables.userAccountDetailsTableName();
        var marker = get(client, table, MARKER_PK, MARKER_SK);
        if (marker.isEmpty()) throw new IllegalStateException("Account import result is missing");
        DynamoItems.requireSchema(marker, 1);
        for (String id : DynamoItems.stringSet(marker, "accountIds")) {
            var control = get(client, table, account(UUID.fromString(id)), "CONTROL");
            if (control.isEmpty() || !SOURCE.equals(DynamoItems.stringOrNull(control, "importedFrom"))) throw new IllegalStateException("An imported account is missing from the store");
        }
        new DynamoStaffAliasVerifier(client, tables).verify();
        for (String partition : SEQUENCES.values()) {
            var counter = get(client, table, partition, "COUNTER");
            if (counter.isEmpty() || DynamoItems.number(counter, "lastAssigned") < 0) throw new IllegalStateException("Counter " + partition + " is missing or negative");
        }
    }

    // ---- PostgreSQL -----------------------------------------------------------------------------------------------

    record Person(UUID id, String issuer, String subject, String username, String displayName, String verifiedEmail, boolean admitted, boolean enabled,
                  boolean author, boolean credentialChangePending, Instant requestedAt, Instant admittedAt, UUID admittedBy, long lastPlanNumber,
                  String state, Instant changedAt, UUID changedBy, List<Identity> identities, List<Admission> admissions, Assignment assignment, List<Alias> aliases) { }
    record Admission(String issuer, String subject, UUID actor, String action, List<String> roles, UUID into, Instant at) { }
    record Assignment(UUID id, UUID accountId, String pendingEmail, Set<String> roles, Set<String> topicGrants, boolean fullCurriculum, long revision, Instant updatedAt, List<StaffEvent> events) { }
    record StaffEvent(UUID id, UUID actor, String action, String previous, String next, long revision, Instant at) { }
    record Alias(String role, int number, Instant assignedAt) { }

    private List<Person> read(JdbcTemplate jdbc) {
        var people = new ArrayList<Person>();
        jdbc.query("""
            SELECT a.account_id, a.issuer, a.subject, a.username, a.display_name, a.verified_email, a.admitted, a.enabled, a.author_access,
                   a.credential_change_pending, a.requested_at, a.admitted_at, a.admitted_by, s.last_plan_number, st.state, st.changed_at, st.changed_by
            FROM cloud_accounts a JOIN platform_subjects s ON s.id = a.account_id LEFT JOIN cloud_account_states st ON st.account_id = a.account_id
            WHERE a.issuer <> ? AND NOT EXISTS (SELECT 1 FROM test_account_registry t WHERE t.account_id = a.account_id)
            ORDER BY a.requested_at, a.account_id""", r -> {
            String state = r.getString("state");
            if ("removed".equals(state)) return;
            UUID id = r.getObject("account_id", UUID.class);
            people.add(new Person(id, r.getString("issuer"), r.getString("subject"), r.getString("username"), r.getString("display_name"), r.getString("verified_email"),
                    r.getBoolean("admitted"), r.getBoolean("enabled"), r.getBoolean("author_access"), r.getBoolean("credential_change_pending"),
                    instant(r, "requested_at"), instant(r, "admitted_at"), r.getObject("admitted_by", UUID.class), r.getLong("last_plan_number"),
                    state == null ? "ACTIVE" : "DISABLED", instant(r, "changed_at"), r.getObject("changed_by", UUID.class),
                    identities(jdbc, id, r.getString("issuer"), r.getString("subject"), r.getString("username"), r.getString("verified_email"), instant(r, "requested_at")),
                    admissions(jdbc, id), assignment(jdbc, id), aliases(jdbc, id)));
        }, TestAccountRoster.TEST_ISSUER);
        return people;
    }

    /** The account's identity rows, oldest first; a row from before V19 without its own identity gets the backfill V19 would have made. */
    private List<Identity> identities(JdbcTemplate jdbc, UUID account, String issuer, String subject, String username, String email, Instant requestedAt) {
        var identities = new ArrayList<Identity>(jdbc.query("SELECT id, issuer, subject, username, provider, verified_email, link_source, linked_at FROM cloud_identities WHERE account_id = ? ORDER BY linked_at, id",
                (r, i) -> new Identity(r.getObject("id", UUID.class), r.getString("issuer"), r.getString("subject"), r.getString("username"), r.getString("provider"),
                        r.getString("verified_email"), r.getString("link_source"), instant(r, "linked_at")), account));
        boolean ownMissing = Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM cloud_identities WHERE issuer = ? AND subject = ?)", Boolean.class, issuer, subject));
        if (ownMissing) identities.addFirst(new Identity(UUID.nameUUIDFromBytes(("lookahead-import:" + account).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                issuer, subject, username, com.lookahead.domain.cloud.CloudIdentities.provider(issuer, username), email, "backfill", requestedAt));
        return identities;
    }

    private List<Admission> admissions(JdbcTemplate jdbc, UUID account) {
        return jdbc.query("SELECT issuer, subject, actor_id, action, roles::text AS roles, recorded_at FROM cloud_admission_events WHERE account_id = ? ORDER BY recorded_at, id", (r, i) -> {
            JsonNode roles = json.readTree(r.getString("roles"));
            var names = new ArrayList<String>();
            if (roles.isArray()) roles.forEach(node -> names.add(node.asText()));
            UUID into = roles.isObject() && roles.has("into") ? UUID.fromString(roles.get("into").asText()) : null;
            return new Admission(r.getString("issuer"), r.getString("subject"), r.getObject("actor_id", UUID.class), r.getString("action"), List.copyOf(names), into, instant(r, "recorded_at"));
        }, account);
    }

    private Assignment assignment(JdbcTemplate jdbc, UUID account) {
        return jdbc.query("SELECT id, account_id, pending_email, roles::text AS roles, topic_grants::text AS topic_grants, full_curriculum, revision, updated_at FROM staff_access WHERE account_id = ?",
                this::assignmentRow, account).stream().findFirst().map(a -> withEvents(jdbc, a)).orElse(null);
    }

    private List<Assignment> pendingInvitations(JdbcTemplate jdbc) {
        return jdbc.query("SELECT id, account_id, pending_email, roles::text AS roles, topic_grants::text AS topic_grants, full_curriculum, revision, updated_at FROM staff_access"
                + " WHERE account_id IS NULL AND pending_email IS NOT NULL AND updated_at > clock_timestamp() - interval '14 days'"
                + " AND (roles <> '[]'::jsonb OR topic_grants <> '[]'::jsonb OR full_curriculum) ORDER BY updated_at, id", this::assignmentRow)
                .stream().map(a -> withEvents(jdbc, a)).toList();
    }

    private Assignment assignmentRow(ResultSet r, int index) throws SQLException {
        return new Assignment(r.getObject("id", UUID.class), r.getObject("account_id", UUID.class), r.getString("pending_email"), strings(r.getString("roles")),
                strings(r.getString("topic_grants")), r.getBoolean("full_curriculum"), r.getLong("revision"), instant(r, "updated_at"), List.of());
    }

    private Assignment withEvents(JdbcTemplate jdbc, Assignment a) {
        var events = jdbc.query("SELECT event_id, actor_id, action, previous_state::text AS previous_state, next_state::text AS next_state, recorded_at FROM staff_access_events WHERE assignment_id = ? ORDER BY recorded_at, event_id", (r, i) -> {
            String next = r.getString("next_state");
            JsonNode revision = json.readTree(next).get("revision");
            return new StaffEvent(r.getObject("event_id", UUID.class), r.getObject("actor_id", UUID.class), r.getString("action"), r.getString("previous_state"), next,
                    revision != null && revision.isNumber() ? revision.asLong() : i + 1L, instant(r, "recorded_at"));
        }, a.id());
        return new Assignment(a.id(), a.accountId(), a.pendingEmail(), a.roles(), a.topicGrants(), a.fullCurriculum(), a.revision(), a.updatedAt(), events);
    }

    private List<Alias> aliases(JdbcTemplate jdbc, UUID account) {
        return jdbc.query("SELECT role, number, assigned_at FROM staff_aliases WHERE account_id = ? ORDER BY role", (r, i) -> new Alias(r.getString("role"), r.getInt("number"), instant(r, "assigned_at")), account);
    }

    /** The last value each record sequence handed out (0 before its first use); {@code pg_sequences} answers with the runtime role's USAGE. */
    private Map<String, Long> sequences(JdbcTemplate jdbc) {
        var values = new LinkedHashMap<String, Long>();
        for (var sequence : SEQUENCES.entrySet()) {
            // last_value is null until the sequence hands out its first number; Optional cannot hold that null, so read through a list.
            var rows = jdbc.query("SELECT COALESCE(last_value, 0) AS last_value FROM pg_sequences WHERE schemaname = current_schema() AND sequencename = ?",
                    (r, i) -> r.getLong("last_value"), sequence.getKey());
            if (rows.isEmpty()) throw new IllegalStateException("Record sequence " + sequence.getKey() + " is missing from the PostgreSQL source");
            values.put(sequence.getValue(), rows.getFirst());
        }
        return values;
    }

    private Set<String> strings(String jsonArray) {
        var values = new TreeSet<String>();
        JsonNode node = json.readTree(jsonArray);
        if (node.isArray()) node.forEach(element -> values.add(element.asText()));
        return values;
    }
    private static Instant instant(ResultSet r, String column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // ---- DynamoDB -------------------------------------------------------------------------------------------------

    /** Waiting rows the store recorded since the cutover go (their people arrive with their PostgreSQL identity); an admitted stranger stops the import. */
    private int clearSignInRows(DynamoCloudAccounts accounts, Set<UUID> imported) {
        int removed = 0;
        for (var person : query(accounts.client(), accounts.accounts(), DIRECTORY, "PERSON#")) {
            UUID id = DynamoItems.uuid(person, "accountId");
            Control control = accounts.control(id);
            if (control == null || control.managedTest() || imported.contains(id)) continue;
            var raw = get(accounts.client(), accounts.accounts(), account(id), "CONTROL");
            if (SOURCE.equals(DynamoItems.stringOrNull(raw, "importedFrom"))) continue;
            if (control.admitted()) throw new IllegalStateException("The store holds an admitted account the import does not know; nothing is deleted");
            var tx = accounts.transaction();
            accounts.admission(tx, id, control.issuer(), control.subject(), null, "expire", List.of(), accounts.now());
            accounts.deleteWaiting(tx, control, accounts.identities(id));
            try { tx.commit(); removed++; } catch (DynamoWrites.Conflict gone) { /* removed meanwhile */ }
        }
        return removed;
    }

    /** One transaction with everything the account owns; a conflict means it was imported already (skipped) or the store changed (refused). */
    private boolean importOne(DynamoCloudAccounts accounts, Person p) {
        String table = accounts.accounts(), audit = accounts.audit();
        if (SOURCE.equals(DynamoItems.stringOrNull(get(accounts.client(), table, account(p.id()), "CONTROL"), "importedFrom"))) return false;
        Instant now = accounts.now();
        var control = new Control(p.id(), p.issuer(), p.subject(), p.username(), p.displayName(), p.verifiedEmail(), p.admitted(), p.enabled(), p.author(), false,
                p.credentialChangePending(), p.state(), 1, 0, p.requestedAt(), p.admittedAt());
        var tx = accounts.transaction();
        tx.insertOnly(table, item(account(p.id()), "META", attrs("revision", n(1), "lastPlanNumber", n(p.lastPlanNumber()), "createdAt", time(p.requestedAt()))));
        tx.insertOnly(table, item(account(p.id()), "CONTROL", attrs("issuer", s(p.issuer()), "subject", s(p.subject()), "username", s(p.username()),
                "displayName", s(p.displayName()), "verifiedEmail", s(p.verifiedEmail()), "admitted", bool(p.admitted()), "author", bool(p.author()), "managedTest", bool(false),
                "enabled", bool(p.enabled()), "credentialChangePending", bool(p.credentialChangePending()), "accountState", s(p.state()), "revision", n(1), "authGeneration", n(0),
                "requestedAt", time(p.requestedAt()), "admittedAt", time(p.admittedAt()), "admittedBy", p.admittedBy() == null ? null : s(p.admittedBy().toString()),
                "changedAt", time(p.changedAt()), "changedBy", p.changedBy() == null ? null : s(p.changedBy().toString()),
                "importedFrom", s(SOURCE), "importedAt", time(now))));
        var carriers = new Carriers();
        var usernames = new ArrayList<String>();
        for (Identity identity : p.identities()) {
            accounts.identity(tx, p.id(), identity);
            if (identity.verifiedEmail() != null) carriers.add(identity.verifiedEmail(), identity.identityId().toString());
            if (!"test".equals(identity.provider()) && identity.username() != null) usernames.add(identity.username());
        }
        if (p.verifiedEmail() != null) carriers.add(p.verifiedEmail(), "account");
        accounts.carriers(tx, carriers, p.id(), p.issuer());
        for (String email : emails(p)) accounts.emailSentinel(tx, email, accounts.emailRevision(email));
        tx.insertOnly(table, accounts.directoryItem(control, usernames));
        if (!p.admitted()) tx.insertOnly(table, item(WAITING, waitingSk(p.requestedAt(), p.id()), attrs("accountId", s(p.id().toString()), "requestedAt", time(p.requestedAt()))));
        if (p.assignment() != null) {
            tx.insertOnly(table, item(STAFF_ACCESS, assignmentSk(p.assignment().id()), assignmentAttributes(p.assignment())));
            tx.insertOnly(table, item(account(p.id()), "STAFF", attrs("assignmentId", s(p.assignment().id().toString()))));
            staffEvents(tx, audit, p.assignment());
        }
        for (Admission a : p.admissions()) {
            if (a.into() != null && a.actor() != null) accounts.merged(tx, p.id(), a.issuer(), a.subject(), a.actor(), a.into(), a.at());
            else accounts.admission(tx, p.id(), a.issuer(), a.subject(), a.actor(), a.action(), a.roles(), a.at());
        }
        for (Alias alias : p.aliases()) {
            String at = alias.assignedAt().toString();
            tx.insertOnly(audit, item("STAFF_ALIAS#" + p.id(), "ROLE#" + alias.role(), attrs("number", n(alias.number()), "assignedAt", s(at))));
            tx.insertOnly(audit, item(roleKey(alias.role()), numberKey(alias.number()), attrs("accountId", s(p.id().toString()), "assignedAt", s(at))));
        }
        if (tx.size() > MAX_STATEMENTS) throw new IllegalStateException("An account has more records than one import transaction can carry (" + tx.size() + ")");
        try { tx.commit(); }
        catch (DynamoWrites.Conflict raced) {
            if (SOURCE.equals(DynamoItems.stringOrNull(get(accounts.client(), table, account(p.id()), "CONTROL"), "importedFrom"))) return false;
            throw new IllegalStateException("An account could not be imported: the store already holds one of its records", raced);
        }
        return true;
    }

    private boolean importInvitation(DynamoCloudAccounts accounts, Assignment pending) {
        String table = accounts.accounts();
        if (!get(accounts.client(), table, STAFF_ACCESS, assignmentSk(pending.id())).isEmpty()) return false;
        var tx = accounts.transaction();
        tx.insertOnly(table, item(STAFF_ACCESS, assignmentSk(pending.id()), assignmentAttributes(pending)));
        tx.insertOnly(table, item(invitePk(pending.pendingEmail()), "ASSIGNMENT", attrs("assignmentId", s(pending.id().toString()))));
        staffEvents(tx, accounts.audit(), pending);
        try { tx.commit(); } catch (DynamoWrites.Conflict present) { return false; }
        return true;
    }

    private static Map<String, AttributeValue> assignmentAttributes(Assignment a) {
        return attrs("accountId", a.accountId() == null ? DynamoItems.nul() : s(a.accountId().toString()), "pendingEmail", a.pendingEmail() == null ? DynamoItems.nul() : s(a.pendingEmail()),
                "roles", DynamoItems.strings(a.roles()), "topicGrants", DynamoItems.strings(a.topicGrants()), "fullCurriculum", bool(a.fullCurriculum()),
                "revision", n(a.revision()), "updatedAt", time(a.updatedAt()));
    }
    private static void staffEvents(DynamoWrites tx, String audit, Assignment a) {
        for (StaffEvent e : a.events())
            tx.insertOnly(audit, item(staffEventPk(a.id()), staffEventSk(e.revision(), e.id()), attrs("actorId", s(e.actor().toString()), "action", s(e.action()),
                    "assignmentId", s(a.id().toString()), "previousState", s(e.previous()), "nextState", s(e.next()), "occurredAt", time(e.at()))));
    }
    private static Set<String> emails(Person p) {
        var emails = new java.util.LinkedHashSet<String>();
        if (p.verifiedEmail() != null) emails.add(com.lookahead.domain.cloud.CloudIdentities.normalizeEmail(p.verifiedEmail()));
        for (Identity identity : p.identities()) if (identity.verifiedEmail() != null) emails.add(com.lookahead.domain.cloud.CloudIdentities.normalizeEmail(identity.verifiedEmail()));
        return emails;
    }

    /** Counters only rise: each role to its highest imported number, each record sequence to the PostgreSQL value; one conditional statement each. */
    private void raiseCounters(DynamoDbClient client, DynamoTableNames tables, Map<String, Integer> highest, Map<String, Long> sequences) {
        String table = tables.userAccountDetailsTableName();
        var raises = new LinkedHashMap<String, Long>();
        for (String role : ROLES) if (highest.containsKey(role)) raises.put(roleKey(role), (long) highest.get(role));
        raises.putAll(sequences);
        for (var raise : raises.entrySet()) {
            var counter = get(client, table, raise.getKey(), "COUNTER");
            if (counter.isEmpty()) throw new IllegalStateException("Counter " + raise.getKey() + " is missing; steps 2 and 4 must be applied first");
            long last = DynamoItems.number(counter, "lastAssigned");
            if (last >= raise.getValue()) continue;
            // The role counters carry a revision (the allocator fences on it); the record counters of step 4 do not.
            var set = new HashMap<String, AttributeValue>(Map.of("lastAssigned", n(raise.getValue())));
            if (counter.containsKey("revision")) set.put("revision", n(DynamoItems.number(counter, "revision") + 1));
            DynamoWrites.transaction(client).update(table, key(raise.getKey(), "COUNTER"), set, "\"lastAssigned\"=?", n(last)).commit();
        }
    }

    private void marker(DynamoDbClient client, DynamoTableNames tables, List<UUID> ids, int removed, Instant at) {
        String table = tables.userAccountDetailsTableName();
        var set = attrs("accountIds", DynamoItems.strings(new TreeSet<>(ids.stream().map(UUID::toString).toList())), "importedAccounts", n(ids.size()), "removedWaiting", n(removed),
                "source", s(SOURCE), "importedAt", time(at));
        var tx = DynamoWrites.transaction(client);
        if (get(client, table, MARKER_PK, MARKER_SK).isEmpty()) tx.insertOnly(table, item(MARKER_PK, MARKER_SK, set)); else tx.update(table, key(MARKER_PK, MARKER_SK), set, null);
        tx.commit();
    }

    private static String roleKey(String role) { return "STAFF_ROLE#" + role; }
    private static String numberKey(long number) { return "NUMBER#" + String.format(Locale.ROOT, "%010d", number); }
}
