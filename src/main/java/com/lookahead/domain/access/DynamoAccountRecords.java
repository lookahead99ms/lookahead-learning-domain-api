package com.lookahead.domain.access;

import static com.lookahead.domain.cloud.CloudAccountItems.*;

import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.membership.AccountGrantsLookup;
import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.testaccounts.AccountIdentityLookup;
import com.lookahead.domain.testaccounts.AccountRecordsMaintenance;
import com.lookahead.domain.testaccounts.TestAccountRoster;
import com.lookahead.learning.content.repository.DynamoAccountStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970 lane G: lane A's account, identity, staff and grant items for the other lanes, in dynamodb mode. A thin
 * adapter over {@link DynamoCloudAccounts} (the account aggregate), {@link DynamoStaffAccessRepository} (assignments)
 * and {@link DynamoAccountStore} (grants): {@link AccountGrantsLookup} for the access summary (lane C),
 * {@link AccountIdentityLookup} and {@link AccountRecordsMaintenance} for DEV test sessions and the test-account
 * roster (lane C). Each write is its own committed operation, as the SQL test-account paths made them.
 */
@Component
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "dynamodb")
public class DynamoAccountRecords implements AccountGrantsLookup, AccountIdentityLookup, AccountRecordsMaintenance {
    private final DynamoCloudAccounts accounts;
    private final DynamoStaffAccessRepository staff;
    private final DynamoAccountStore grants;

    @Autowired
    public DynamoAccountRecords(DynamoDbClient client, DynamoTableNames tables, DynamoStaffAccessRepository staff) { this(client, tables, staff, Clock.systemUTC()); }
    public DynamoAccountRecords(DynamoDbClient client, DynamoTableNames tables, DynamoStaffAccessRepository staff, Clock clock) {
        this.accounts = new DynamoCloudAccounts(client, tables, clock);
        this.staff = staff;
        this.grants = new DynamoAccountStore(client, tables, clock);
    }

    // ---- AccountGrantsLookup (the SQL account_grants valid at `now` plus staff_access.topic_grants) ----------------

    @Override public Set<String> grantedTopics(UUID account, Instant now) {
        var topics = new TreeSet<>(grants.grantTopics(account, now));
        var assignment = staff.forAccount(account);
        if (assignment != null) topics.addAll(assignment.topicGrants());
        return topics;
    }

    // ---- AccountIdentityLookup ------------------------------------------------------------------------------------

    @Override public UUID accountOf(String issuer, String subject) { return accounts.accountOf(issuer, subject); }
    /** Admitted, enabled and in its ACTIVE state (no disabled or removed row): the SQL holder query. */
    @Override public Holder holder(String issuer, String subject) {
        UUID id = accounts.accountOf(issuer, subject);
        var control = id == null ? null : accounts.control(id);
        if (control == null || !control.admitted() || !control.enabled() || !control.active()) return null;
        return new Holder(id, control.author());
    }
    @Override public Admitted admitted(UUID account) {
        var control = accounts.control(account);
        return control == null ? null : new Admitted(control.issuer(), control.displayName(), control.enabled(), control.admitted());
    }
    @Override public List<String> staffRoles(UUID account) {
        var assignment = staff.forAccount(account);
        return assignment == null ? List.of() : List.copyOf(assignment.roles());
    }
    @Override public String displayName(UUID account) {
        var control = accounts.control(account);
        return control == null ? null : control.displayName();
    }
    @Override public Instant staffAccessUpdatedAt(UUID account) { return staff.updatedAt(account); }

    // ---- AccountRecordsMaintenance --------------------------------------------------------------------------------

    /**
     * The admitted record of a DEV test account on the test issuer, in the shape lane A's first contact writes:
     * CONTROL (admitted, enabled, managed test), its one identity binding and list entry, the directory projection
     * and the admit event, in one transaction. The {@code ACCOUNT#id / META} subject is the test-account store's
     * (created in the same transaction as the registry row), so it is not written here.
     */
    @Override public void admitTestAccount(UUID account, String username, String displayName, UUID admittedBy) {
        Instant now = accounts.now();
        String issuer = TestAccountRoster.TEST_ISSUER, subject = account.toString();
        var control = new DynamoCloudAccounts.Control(account, issuer, subject, username, displayName, null, true, true, false, true, false, "ACTIVE", 1, 0, now, now);
        var identity = new DynamoCloudAccounts.Identity(UUID.randomUUID(), issuer, subject, username, "test", null, "first_contact", now);
        var tx = accounts.transaction();
        tx.insertOnly(accounts.accounts(), item(account(account), "CONTROL", attrs("issuer", s(issuer), "subject", s(subject), "username", s(username),
                "displayName", s(displayName), "admitted", bool(true), "author", bool(false), "managedTest", bool(true), "enabled", bool(true),
                "credentialChangePending", bool(false), "accountState", s("ACTIVE"), "revision", n(1), "authGeneration", n(0),
                "requestedAt", time(now), "admittedAt", time(now), "admittedBy", s(admittedBy.toString()))));
        accounts.identity(tx, account, identity);
        tx.insertOnly(accounts.accounts(), accounts.directoryItem(control, List.of(username)));
        accounts.admission(tx, account, issuer, subject, admittedBy, "admit", List.of(), now);
        tx.commit();
    }
    @Override public boolean hasAccountRecord(UUID account) { return accounts.control(account) != null; }
    @Override public void grantTopics(UUID account, Collection<String> topics) { for (String topic : topics) grants.grant(account, topic); }
    /** Every grant deleted; the assignment emptied at its revision (roles, topic grants, full curriculum), as the SQL reset did. */
    @Override public void clearAccessRecords(UUID account) {
        grants.revokeAll(account);
        var assignment = staff.forAccount(account);
        if (assignment != null)
            staff.update(new StaffAccessRepository.Assignment(assignment.id(), account, null, Set.of(), Set.of(), false, assignment.revision() + 1));
    }
}
