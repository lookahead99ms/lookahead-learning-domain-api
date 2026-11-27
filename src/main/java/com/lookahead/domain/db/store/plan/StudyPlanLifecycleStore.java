package com.lookahead.domain.db.store.plan;

import com.lookahead.domain.db.store.dynamodb.OwnerTransactionReads;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionUnit;
import com.lookahead.domain.model.StudyPlanLifecycleModels;
import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** DLV-970: {@code ACCOUNT#id / LIFECYCLE#planId} items; reads merge the rows this unit already changed, writes land at commit under the owner revision. */
@Repository
@Profile("accounts")
public class StudyPlanLifecycleStore implements StudyPlanLifecycle {
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    private final Clock clock;

    @Autowired
    public StudyPlanLifecycleStore(DynamoDbClient client, DynamoTableNames tables) { this(client, tables, Clock.systemUTC()); }
    StudyPlanLifecycleStore(DynamoDbClient client, DynamoTableNames tables, Clock clock) { this.client = client; this.tables = tables; this.clock = clock; }

    static final class Pending { final Map<UUID, StudyPlanLifecycleModels.Entry> entries = new HashMap<>(); final Map<UUID, Boolean> stored = new HashMap<>(); }
    private Pending pending(OwnerTransactionUnit unit) { return unit.state(this, Pending::new); }
    private String table() { return tables.userAccountDetailsTableName(); }
    private static String sk(UUID id) { return "LIFECYCLE#" + id; }

    static StudyPlanLifecycleModels.Entry entry(Map<String, AttributeValue> item) {
        DynamoItems.requireSchema(item, OwnerTransactionUnit.SCHEMA);
        return new StudyPlanLifecycleModels.Entry(DynamoItems.uuid(item, "planId"), DynamoItems.string(item, "state"), LocalDate.parse(DynamoItems.string(item, "startDate")),
                (int) DynamoItems.number(item, "dayCount"), (int) DynamoItems.number(item, "dailyMinutes"), DynamoItems.stringSet(item, "subjectIds"));
    }

    private static Map<String, AttributeValue> attributes(StudyPlanLifecycleModels.Entry entry) {
        return Map.of("planId", DynamoItems.s(entry.id().toString()), "state", DynamoItems.s(entry.state()), "startDate", DynamoItems.s(entry.start().toString()),
                "dayCount", DynamoItems.n(entry.days()), "dailyMinutes", DynamoItems.n(entry.minutes()), "subjectIds", DynamoItems.strings(entry.subjects()));
    }

    @Override public List<StudyPlanLifecycleModels.Entry> entries(UUID owner) {
        // Text order of the UUID equals PostgreSQL's byte order of ORDER BY plan_id.
        var rows = new TreeMap<String, StudyPlanLifecycleModels.Entry>();
        for (var item : OwnerTransactionReads.queryAll(client, table(), DynamoItems.account(owner), "LIFECYCLE#")) { var entry = entry(item); rows.put(entry.id().toString(), entry); }
        var unit = OwnerTransactionUnit.current();
        if (unit != null) pending(unit).entries.forEach((id, entry) -> rows.put(id.toString(), entry));
        return new ArrayList<>(rows.values());
    }

    private StudyPlanLifecycleModels.Entry current(OwnerTransactionUnit unit, UUID owner, UUID id) {
        var pending = pending(unit).entries.get(id);
        if (pending != null) return pending;
        var item = OwnerTransactionReads.get(client, table(), DynamoItems.account(owner), sk(id));
        pending(unit).stored.put(id, !item.isEmpty());
        return item.isEmpty() ? null : entry(item);
    }

    private void write(OwnerTransactionUnit unit, UUID owner, StudyPlanLifecycleModels.Entry entry) {
        pending(unit).entries.put(entry.id(), entry);
        boolean stored = pending(unit).stored.get(entry.id());
        unit.statement(table(), DynamoItems.account(owner), sk(entry.id()), tx -> {
            if (stored) tx.update(table(), DynamoItems.key(DynamoItems.account(owner), sk(entry.id())), attributes(entry), null);
            else tx.insertOnly(table(), DynamoItems.item(DynamoItems.account(owner), sk(entry.id()), OwnerTransactionUnit.SCHEMA, attributes(entry)));
        });
    }

    @Override public void setState(UUID owner, UUID id, String state) {
        OwnerTransactionUnit.run(client, tables, clock, unit -> {
            unit.fence(owner);
            var old = current(unit, owner, id);
            if (old != null) write(unit, owner, new StudyPlanLifecycleModels.Entry(id, state, old.start(), old.days(), old.minutes(), old.subjects()));
            return null;
        });
    }

    @Override public void reserve(UUID owner, UUID id, LocalDate start, int days, int minutes, Set<String> subjects) {
        OwnerTransactionUnit.run(client, tables, clock, unit -> {
            unit.fence(owner);
            current(unit, owner, id);
            write(unit, owner, new StudyPlanLifecycleModels.Entry(id, "active", start, days, minutes, Set.copyOf(subjects)));
            return null;
        });
    }
}
