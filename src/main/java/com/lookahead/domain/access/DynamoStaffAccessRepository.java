package com.lookahead.domain.access;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-970: staff assignments on DynamoDB. The assignment itself lives at {@code STAFFACCESS / ASSIGNMENT#id} (one
 * partition: the list is a strong query, sorted in Java), with {@code ACCOUNT#id / STAFF} and
 * {@code INVITE#email / ASSIGNMENT} pointers as the uniqueness sentinels (one assignment per account, one invitation
 * per email) that commit with it. Events are insert-only {@code STAFF#assignmentId / EVENT#revision#id} items.
 */
@Repository
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoStaffAccessRepository extends StaffAccessRepository {
    static final Duration PENDING_LIFETIME = Duration.ofDays(14);
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public DynamoStaffAccessRepository(DynamoDbClient client, DynamoTableNames tables, ObjectMapper mapper) { this(client, tables, mapper, Clock.systemUTC()); }
    public DynamoStaffAccessRepository(DynamoDbClient client, DynamoTableNames tables, ObjectMapper mapper, Clock clock) {
        this.client = client; this.tables = tables; this.mapper = mapper; this.clock = clock;
    }
    private String accounts() { return tables.userAccountDetailsTableName(); }

    @Override public void mutationActor(UUID actor) { /* the actor is recorded on the event item */ }

    @Override public Assignment forAccount(UUID account) {
        var pointer = get(client, accounts(), account(account), "STAFF");
        return pointer.isEmpty() ? null : lock(DynamoItems.uuid(pointer, "assignmentId"));
    }
    /** When the account's assignment was last written ({@code staff_access.updated_at}), or null without one. */
    public Instant updatedAt(UUID account) {
        var pointer = get(client, accounts(), account(account), "STAFF");
        if (pointer.isEmpty()) return null;
        var item = get(client, accounts(), STAFF_ACCESS, assignmentSk(DynamoItems.uuid(pointer, "assignmentId")));
        return item.isEmpty() ? null : DynamoItems.instant(item, "updatedAt");
    }
    @Override public List<Assignment> list() {
        return query(client, accounts(), STAFF_ACCESS, "ASSIGNMENT#").stream()
                .sorted(Comparator.comparing((Map<String, AttributeValue> item) -> DynamoItems.instant(item, "updatedAt")).thenComparing(item -> DynamoItems.string(item, "sk")).reversed())
                .limit(500).map(DynamoStaffAccessRepository::assignment).toList();
    }
    @Override public Set<UUID> owners() {
        return query(client, accounts(), DIRECTORY, "PERSON#").stream().filter(item -> flag(item, "author"))
                .map(item -> DynamoItems.uuid(item, "accountId")).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public Assignment lock(UUID id) {
        var item = get(client, accounts(), STAFF_ACCESS, assignmentSk(id));
        return item.isEmpty() ? null : assignment(item);
    }
    @Override public Assignment pending(String email) {
        var pointer = get(client, accounts(), invitePk(email), "ASSIGNMENT");
        if (pointer.isEmpty()) return null;
        var item = get(client, accounts(), STAFF_ACCESS, assignmentSk(DynamoItems.uuid(pointer, "assignmentId")));
        if (item.isEmpty()) return null;
        var a = assignment(item);
        boolean live = a.accountId() == null && email.equals(a.pendingEmail()) && DynamoItems.instant(item, "updatedAt").isAfter(clock.instant().minus(PENDING_LIFETIME))
                && (!a.roles().isEmpty() || !a.topicGrants().isEmpty() || a.fullCurriculum());
        return live ? a : null;
    }
    @Override public void insert(Assignment a) { var tx = DynamoWrites.transaction(client); insert(tx, a); tx.commit(); }
    @Override public void update(Assignment a) { var tx = DynamoWrites.transaction(client); update(tx, a, lock(a.id())); tx.commit(); }
    @Override public void event(UUID actor, String action, Assignment old, Assignment next) { var tx = DynamoWrites.transaction(client); event(tx, actor, action, old, next); tx.commit(); }
    /** One transaction: the assignment and its pointers at the expected revision, and the event. */
    @Override public void save(UUID actor, String action, Assignment old, Assignment next) {
        var tx = DynamoWrites.transaction(client);
        if (old == null) insert(tx, next); else update(tx, next, old);
        event(tx, actor, action, old, next);
        tx.commit();
    }

    private void insert(DynamoWrites tx, Assignment a) {
        tx.insertOnly(accounts(), item(STAFF_ACCESS, assignmentSk(a.id()), attributes(a, clock.instant())));
        pointer(tx, a);
    }
    private void pointer(DynamoWrites tx, Assignment a) {
        if (a.accountId() != null) tx.insertOnly(accounts(), item(account(a.accountId()), "STAFF", attrs("assignmentId", s(a.id().toString()))));
        else tx.insertOnly(accounts(), item(invitePk(a.pendingEmail()), "ASSIGNMENT", attrs("assignmentId", s(a.id().toString()))));
    }
    /** The row replaced at {@code old}'s revision; a claim (pending → account) moves the pointer in the same transaction. */
    private void update(DynamoWrites tx, Assignment a, Assignment old) {
        if (old == null) throw new DynamoConflict();
        tx.updateAtRevision(accounts(), key(STAFF_ACCESS, assignmentSk(a.id())), attributes(a, clock.instant()), a.revision() - 1);
        if (old.accountId() == null && a.accountId() != null) {
            if (old.pendingEmail() != null) tx.delete(accounts(), key(invitePk(old.pendingEmail()), "ASSIGNMENT"), null);
            tx.insertOnly(accounts(), item(account(a.accountId()), "STAFF", attrs("assignmentId", s(a.id().toString()))));
        }
    }
    private void event(DynamoWrites tx, UUID actor, String action, Assignment old, Assignment next) {
        tx.insertOnly(tables.administratorAuditEventsTableName(), item(staffEventPk(next.id()), staffEventSk(next.revision(), UUID.randomUUID()), attrs(
                "actorId", s(actor.toString()), "action", s(action), "assignmentId", s(next.id().toString()),
                "previousState", old == null ? null : s(mapper.writeValueAsString(old)), "nextState", s(mapper.writeValueAsString(next)), "occurredAt", time(clock.instant()))));
    }
    private static Map<String, AttributeValue> attributes(Assignment a, Instant now) {
        return attrs("accountId", a.accountId() == null ? DynamoItems.nul() : s(a.accountId().toString()), "pendingEmail", a.pendingEmail() == null ? DynamoItems.nul() : s(a.pendingEmail()),
                "roles", DynamoItems.strings(a.roles()), "topicGrants", DynamoItems.strings(a.topicGrants()), "fullCurriculum", bool(a.fullCurriculum()),
                "revision", n(a.revision()), "updatedAt", time(now));
    }
    private static Assignment assignment(Map<String, AttributeValue> item) {
        DynamoItems.requireSchema(item, SCHEMA);
        return new Assignment(UUID.fromString(DynamoItems.string(item, "sk").substring("ASSIGNMENT#".length())), uuid(item, "accountId"), text(item, "pendingEmail"),
                DynamoItems.stringSet(item, "roles"), DynamoItems.stringSet(item, "topicGrants"), flag(item, "fullCurriculum"), DynamoItems.number(item, "revision"));
    }
    /** An update of an assignment that is not there: the same answer as a stale revision. */
    static final class DynamoConflict extends org.springframework.dao.DuplicateKeyException { DynamoConflict() { super("Assignment missing"); } }
}
