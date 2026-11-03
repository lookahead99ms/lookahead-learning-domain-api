package com.lookahead.domain.persistence.dynamodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Operator-only library for a quiescent empty namespace; no client creation, Spring bean or repair path. */
public final class DynamoStaffAliasBootstrap {
    private static final List<String> ROLES = List.of("co-author", "admin", "reviewer", "tester", "learner");
    private final DynamoDbClient client;
    private final DynamoTableNames tables;
    public DynamoStaffAliasBootstrap(DynamoDbClient client, DynamoTableNames tables) {
        this.client = Objects.requireNonNull(client, "client");
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    /** False = first initialization; true = matching durable initialization already exists. */
    public boolean initialize(UUID initializationId, Instant initializedAt, int maxScanPages) {
        Objects.requireNonNull(initializationId, "initializationId");
        Objects.requireNonNull(initializedAt, "initializedAt");
        if (initializedAt.isBefore(Instant.EPOCH) || initializedAt.isAfter(Instant.parse("9999-12-31T23:59:59Z"))
                || maxScanPages < 1 || maxScanPages > 10_000) throw new IllegalArgumentException("Invalid bootstrap time/budget");
        if (exists(initializationId, initializedAt)) return true;
        requireEmpty(maxScanPages);
        var statements = new ArrayList<ParameterizedStatement>();
        for (String role : ROLES) {
            statements.add(statement("INSERT INTO \"" + tables.userAccountDetailsTableName()
                    + "\" VALUE {'pk': ?, 'sk': 'COUNTER', 'schemaVersion': 1, 'lastAssigned': 0, 'revision': 0, 'initializationId': ?}",
                    s("STAFF_ROLE#" + role), s(initializationId.toString())));
            statements.add(statement("INSERT INTO \"" + tables.administratorAuditEventsTableName()
                    + "\" VALUE {'pk': ?, 'sk': 'INITIALIZED', 'schemaVersion': 1, 'initializationId': ?, 'initializedAt': ?}",
                    s("STAFF_ROLE#" + role), s(initializationId.toString()), s(initializedAt.toString())));
        }
        try {
            client.executeTransaction(ExecuteTransactionRequest.builder().transactStatements(statements).build());
        } catch (SdkException failure) {
            try {
                if (exists(initializationId, initializedAt)) return true;
            } catch (RuntimeException reconciliationFailure) {
                failure.addSuppressed(reconciliationFailure);
            }
            throw failure;
        }
        if (!exists(initializationId, initializedAt)) throw new IllegalStateException("Initialization has no durable records");
        return false;
    }

    private boolean exists(UUID id, Instant time) {
        var keys = new ArrayList<TransactGetItem>();
        for (String role : ROLES) {
            keys.add(read(tables.userAccountDetailsTableName(), role, "COUNTER"));
            keys.add(read(tables.administratorAuditEventsTableName(), role, "INITIALIZED"));
        }
        var records = client.transactGetItems(TransactGetItemsRequest.builder().transactItems(keys).build()).responses();
        if (records.size() != 10) throw new IllegalStateException("Incomplete initialization read");
        int present = 0;
        for (int index = 0; index < records.size(); index += 2) {
            var counter = records.get(index).item();
            var marker = records.get(index + 1).item();
            if (!counter.isEmpty()) {
                common(counter, id);
                number(counter, "lastAssigned", Integer.MAX_VALUE);
                number(counter, "revision", Long.MAX_VALUE);
                present++;
            }
            if (!marker.isEmpty()) {
                common(marker, id);
                if (!s(time.toString()).equals(marker.get("initializedAt"))) throw new IllegalStateException("Initialization timestamp mismatch");
                present++;
            }
        }
        if (present != 0 && present != 10) throw new IllegalStateException("Partial alias initialization requires reviewed recovery");
        return present == 10;
    }

    private void requireEmpty(int maxPages) {
        Map<String, AttributeValue> cursor = Map.of();
        int pages = 0;
        do {
            if (++pages > maxPages) throw new IllegalStateException("Empty-namespace scan budget exhausted");
            var response = client.scan(ScanRequest.builder().tableName(tables.administratorAuditEventsTableName())
                    .consistentRead(true).limit(100).exclusiveStartKey(cursor.isEmpty() ? null : cursor)
                    .filterExpression("begins_with(pk, :alias) OR begins_with(pk, :role)")
                    .expressionAttributeValues(Map.of(":alias", s("STAFF_ALIAS#"), ":role", s("STAFF_ROLE#"))).build());
            if (!response.items().isEmpty()) throw new IllegalStateException("Alias namespace is not empty");
            var next = response.lastEvaluatedKey();
            if (!next.isEmpty() && (next.size() != 2 || !validKey(next.get("pk")) || !validKey(next.get("sk")) || next.equals(cursor))) {
                throw new IllegalStateException("Invalid empty-namespace scan continuation");
            }
            cursor = next;
        } while (!cursor.isEmpty());
    }

    private static TransactGetItem read(String table, String role, String sk) {
        return TransactGetItem.builder().get(Get.builder().tableName(table)
                .key(Map.of("pk", s("STAFF_ROLE#" + role), "sk", s(sk))).build()).build();
    }
    private static void common(Map<String, AttributeValue> item, UUID id) {
        if (!AttributeValue.builder().n("1").build().equals(item.get("schemaVersion"))
                || !s(id.toString()).equals(item.get("initializationId"))) throw new IllegalStateException("Invalid or different alias initialization");
    }
    private static void number(Map<String, AttributeValue> item, String field, long max) {
        try {
            long number = Long.parseLong(item.get(field).n());
            if (number < 0 || number > max) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid initialized counter", invalid); }
    }
    private static boolean validKey(AttributeValue value) { return value != null && value.s() != null && !value.s().isBlank(); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static ParameterizedStatement statement(String text, AttributeValue... values) {
        return ParameterizedStatement.builder().statement(text).parameters(values).build();
    }
}
