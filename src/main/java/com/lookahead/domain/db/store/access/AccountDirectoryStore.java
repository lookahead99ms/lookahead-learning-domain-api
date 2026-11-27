package com.lookahead.domain.db.store.access;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.model.AccountDirectoryModels.Account;
import com.lookahead.domain.model.AccountDirectoryModels.Page;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DLV-970: the directory on DynamoDB. One strong query of the {@code DIRECTORY#00 / PERSON#username#id} projection
 * (maintained in the same transaction as every profile, admission and enabled change), filtered and paged in Java
 * with the existing case-insensitive substring, username ordering and 50-row page contract; the authoritative control
 * item of each matching row decides the deleted state before anything is returned.
 */
@Repository
public class AccountDirectoryStore implements AccountDirectory {
    private record Person(UUID id, String username, String displayName, String email, boolean admitted, boolean enabled, List<String> usernames) {}
    private final AccountAggregateStore accounts;
    private final AccountSubjectStore subjects;

    @Autowired
    public AccountDirectoryStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    AccountDirectoryStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) {
        this.accounts = new AccountAggregateStore(client, tables, clock);
        this.subjects = new AccountSubjectStore(client, tables, clock);
    }

    private List<Person> people() {
        return query(accounts.client(), accounts.accounts(), DIRECTORY, "PERSON#").stream().map(AccountDirectoryStore::person).toList();
    }
    private static Person person(Map<String, AttributeValue> item) {
        return new Person(DynamoItems.uuid(item, "accountId"), DynamoItems.string(item, "username"), text(item, "displayName"), text(item, "verifiedEmail"),
                flag(item, "admitted"), flag(item, "enabled"), strings(item, "usernames"));
    }
    /** Admitted, enabled and, by its control item, not deleted. */
    private boolean listed(Person p) {
        if (!p.admitted || !p.enabled) return false;
        var control = accounts.control(p.id);
        return control != null && !control.removed();
    }

    @Override public Page list(String term, int page) {
        var matches = new ArrayList<Person>();
        for (Person p : people()) {
            if (!p.admitted || !p.enabled) continue;
            if (!(term.isEmpty() || lower(p.username).contains(term) || lower(p.displayName).contains(term) || lower(p.email).contains(term))) continue;
            matches.add(p);
        }
        matches.sort(Comparator.comparing((Person p) -> lower(p.username)).thenComparing(p -> p.id.toString()));
        var rows = new ArrayList<Account>();
        int from = page * 50;
        // The offset counts listed rows only, as the SQL did; deleted rows are decided by the authoritative control item.
        int seen = 0;
        for (Person p : matches) {
            if (!listed(p)) continue;
            if (seen++ < from) continue;
            rows.add(new Account(p.id, p.username, p.displayName, p.email));
            if (rows.size() == 51) break;
        }
        return new Page(List.copyOf(rows.subList(0, Math.min(50, rows.size()))), page, rows.size() > 50);
    }
    @Override public Optional<Account> lookup(String query) {
        var rows = people().stream().filter(p -> lower(p.username).equals(query) || p.usernames.contains(query)).filter(this::listed).limit(2).toList();
        if (rows.size() != 1) return Optional.empty();
        Person p = rows.getFirst();
        return Optional.of(new Account(p.id, p.username, p.displayName, null));
    }
    @Override public Optional<Account> byId(UUID id) {
        var control = accounts.control(id);
        if (control == null || !control.admitted() || !control.enabled()) return Optional.empty();
        return Optional.of(new Account(id, control.username(), control.displayName(), null));
    }
    @Override public void ensureSubject(UUID id) { subjects.ensureSubject(id); }
}
