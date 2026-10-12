package com.lookahead.domain.migration;

import com.lookahead.learning.content.util.CatalogIds;
import com.lookahead.learning.content.util.PlanJson;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLV-931: the catalog data ID {@code look-ahead} becomes {@code blaze} in stored grants and plans.
 *
 * <p>This is a Java migration rather than SQL for two reasons. Plan versions store
 * {@code snapshot_digest}, the SHA-256 of the canonical snapshot JSON computed by {@link PlanJson};
 * a rewritten snapshot gets its digest recomputed with exactly that code. And the rewrite uses
 * {@link CatalogIds}, the same rules the API applies to requests from old browsers, so prose
 * (plan names, goals, titles, notes) and platform names such as {@code look-ahead.study-plan.v1}
 * stay untouched. Flyway runs it in the migration transaction: all of it applies or none of it.
 *
 * <p>Not rewritten on purpose: {@code mutation_receipts.request_hash} (the hash of a request
 * exactly as the browser sent it, so an old tab retrying the same request still matches),
 * plan {@code catalogVersion} pins (digests of the catalog the plan was generated against),
 * and the append-only {@code author_review_events}, which hold no catalog IDs.
 *
 * <p>Registered explicitly by {@link com.lookahead.domain.DomainApiMigration}; it is outside the
 * scanned {@code db/domain} location so it cannot be found twice.
 */
public class V5__blaze_catalog_id extends BaseJavaMigration {
    private record Column(String table, String column, String key) {}

    private static final List<Column> JSON_COLUMNS = List.of(
            new Column("plans", "progress", "id"),
            new Column("plan_versions", "membership", "id"),
            new Column("plan_versions", "provenance", "id"),
            new Column("plan_versions", "recovery", "id"),
            new Column("plan_activity", "payload", "id"),
            new Column("mutation_receipts", "response", "mutation_key"));

    @Override public void migrate(Context context) throws Exception {
        apply(context.getConnection());
    }

    /** Counts of changed rows, for the migration log and tests. */
    public record Result(int grants, int snapshots, int jsonValues) {}

    public static Result apply(Connection connection) throws SQLException {
        JsonMapper mapper = JsonMapper.builder().build();
        PlanJson json = new PlanJson(mapper);
        return new Result(grants(connection), snapshots(connection, json), jsonColumns(connection, json));
    }

    /** Moves each legacy grant to its blaze ID; an account holding both keeps the longer-lasting one. */
    private static int grants(Connection connection) throws SQLException {
        record Grant(UUID account, String topic, Timestamp validUntil) {}
        List<Grant> legacy = new ArrayList<>();
        try (var select = connection.prepareStatement(
                "SELECT account_id, topic_id, valid_until FROM account_grants WHERE topic_id LIKE '%look-ahead%' ORDER BY account_id, topic_id");
             var rows = select.executeQuery()) {
            while (rows.next()) legacy.add(new Grant(rows.getObject(1, UUID.class), rows.getString(2), rows.getTimestamp(3)));
        }
        int changed = 0;
        try (var upsert = connection.prepareStatement("""
                INSERT INTO account_grants(account_id, topic_id, valid_until) VALUES (?, ?, ?)
                ON CONFLICT (account_id, topic_id) DO UPDATE SET valid_until =
                  CASE WHEN account_grants.valid_until IS NULL OR EXCLUDED.valid_until IS NULL THEN NULL
                       ELSE GREATEST(account_grants.valid_until, EXCLUDED.valid_until) END
                """);
             var delete = connection.prepareStatement("DELETE FROM account_grants WHERE account_id = ? AND topic_id = ?")) {
            for (Grant grant : legacy) {
                String topic = CatalogIds.normalize(grant.topic());
                if (topic.equals(grant.topic())) continue;
                delete.setObject(1, grant.account()); delete.setString(2, grant.topic()); delete.executeUpdate();
                upsert.setObject(1, grant.account()); upsert.setString(2, topic); upsert.setTimestamp(3, grant.validUntil());
                upsert.executeUpdate();
                changed++;
            }
        }
        return changed;
    }

    /** Rewrites snapshots and recomputes their digest with the API's own canonical form. */
    private static int snapshots(Connection connection, PlanJson json) throws SQLException {
        Map<UUID, JsonNode> rewritten = new LinkedHashMap<>();
        try (var select = connection.prepareStatement(
                "SELECT id, snapshot::text FROM plan_versions WHERE snapshot::text LIKE '%look-ahead%' ORDER BY id");
             var rows = select.executeQuery()) {
            while (rows.next()) {
                JsonNode before = json.parse(rows.getString(2)), after = CatalogIds.normalize(before);
                if (!after.equals(before)) rewritten.put(rows.getObject(1, UUID.class), after);
            }
        }
        try (var update = connection.prepareStatement("UPDATE plan_versions SET snapshot = ?::jsonb, snapshot_digest = ? WHERE id = ?")) {
            for (var entry : rewritten.entrySet()) {
                update.setString(1, json.json(entry.getValue()));
                update.setString(2, snapshotDigest(json, entry.getValue()));
                update.setObject(3, entry.getKey());
                update.executeUpdate();
            }
        }
        return rewritten.size();
    }

    /** Same expression as PlanService.insertVersion. */
    public static String snapshotDigest(PlanJson json, JsonNode snapshot) {
        return json.digest(json.canonical(snapshot));
    }

    private static int jsonColumns(Connection connection, PlanJson json) throws SQLException {
        int changed = 0;
        for (Column column : JSON_COLUMNS) {
            Map<Object, JsonNode> rewritten = new LinkedHashMap<>();
            // mutation_receipts is keyed by (account_id, mutation_key); keep both parts of the key.
            boolean receipts = column.table().equals("mutation_receipts");
            String key = receipts ? "account_id, mutation_key" : column.key();
            try (var select = connection.prepareStatement("SELECT " + key + ", " + column.column() + "::text FROM " + column.table()
                    + " WHERE " + column.column() + "::text LIKE '%look-ahead%' ORDER BY " + key);
                 var rows = select.executeQuery()) {
                while (rows.next()) {
                    Object id = receipts ? List.of(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class)) : rows.getObject(1, UUID.class);
                    String text = rows.getString(receipts ? 3 : 2);
                    JsonNode before = json.parse(text), after = CatalogIds.normalize(before);
                    if (!after.equals(before)) rewritten.put(id, after);
                }
            }
            String where = receipts ? "account_id = ? AND mutation_key = ?" : column.key() + " = ?";
            try (var update = connection.prepareStatement("UPDATE " + column.table() + " SET " + column.column() + " = ?::jsonb WHERE " + where)) {
                for (var entry : rewritten.entrySet()) {
                    update.setString(1, json.json(entry.getValue()));
                    if (entry.getKey() instanceof List<?> parts) { update.setObject(2, parts.get(0)); update.setObject(3, parts.get(1)); }
                    else update.setObject(2, Objects.requireNonNull(entry.getKey()));
                    update.executeUpdate();
                }
            }
            changed += rewritten.size();
        }
        return changed;
    }
}
