package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.membership.MembershipCatalogSeed;
import com.lookahead.domain.membership.MembershipItems;
import com.lookahead.domain.persistence.dynamodb.DynamoItems;
import com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasBootstrap;
import com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasVerifier;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * DLV-970: the real Domain catalog. Append a new numbered step for every data change; never edit one that a
 * store may have applied. {@link #REQUIRED} is the data version this build needs, bound to the catalog in tests.
 */
public final class DomainDynamoCatalog {
    /** The release requirement: every step below. A deploy whose data is behind reports DATA_VERSION_BEHIND. */
    public static final int REQUIRED = 5;

    private static final DynamoMigrationCatalog CATALOG = new DynamoMigrationCatalog(List.of(new BaseTables(), new StaffAliasCounters(), new MembershipCatalog(), new ReviewRecordCounters(), new AccountAccessImport()));

    private DomainDynamoCatalog() { }

    public static DynamoMigrationCatalog catalog() { return CATALOG; }

    /**
     * Step 1: the four approved tables exist, are ACTIVE and key on string pk/sk. Tables are created by the
     * reviewed Infra stack, not by this step; applying it only checks, so a store without its tables records
     * nothing and readiness keeps saying TABLE_MISSING.
     */
    static final class BaseTables implements DynamoMigrationStep {
        @Override public int version() { return 1; }
        @Override public String name() { return "base-tables"; }
        @Override public String definition() {
            return "The four approved tables (user-account-details, administrator-audit-events, user-study-plan-details,"
                    + " protected-owner-identity) exist, are ACTIVE and have the key schema pk HASH string, sk RANGE string."
                    + " Created by infrastructure; this step verifies and records that the store is usable.";
        }
        @Override public void apply(DynamoDbClient client, DynamoTableNames tables) { verify(client, tables); }
        @Override public void verify(DynamoDbClient client, DynamoTableNames tables) {
            for (String name : tables.all()) {
                var table = client.describeTable(DescribeTableRequest.builder().tableName(name).build()).table();
                if (table.tableStatus() != TableStatus.ACTIVE) throw new IllegalStateException("DynamoDB table " + name + " is not ACTIVE");
                var keys = table.keySchema();
                if (keys.size() != 2 || !"pk".equals(keys.get(0).attributeName()) || keys.get(0).keyType() != KeyType.HASH
                        || !"sk".equals(keys.get(1).attributeName()) || keys.get(1).keyType() != KeyType.RANGE)
                    throw new IllegalStateException("DynamoDB table " + name + " does not key on pk/sk");
                for (var attribute : table.attributeDefinitions())
                    if (("pk".equals(attribute.attributeName()) || "sk".equals(attribute.attributeName())) && attribute.attributeType() != ScalarAttributeType.S)
                        throw new IllegalStateException("DynamoDB table " + name + " keys must be strings");
            }
        }
    }

    /**
     * Step 2: the five permanent staff-label counters and their initialization markers, created once in one
     * transaction by the reviewed bootstrap. Fixed initialization identity and time make a repeat a replay. Verify
     * reads the counters, markers and the claims at each high-water mark by key; it never scans the audit table.
     */
    static final class StaffAliasCounters implements DynamoMigrationStep {
        static final UUID INITIALIZATION = UUID.fromString("7a1a6d5e-1d4c-4b7e-9a0f-2f3c5d8e1b02");
        static final Instant INITIALIZED_AT = Instant.parse("2026-11-10T00:00:00Z");
        @Override public int version() { return 2; }
        @Override public String name() { return "staff-alias-counters"; }
        @Override public String definition() {
            return "Initialize the five STAFF_ROLE#<role> COUNTER items (user-account-details) and INITIALIZED markers"
                    + " (administrator-audit-events) for co-author, admin, reviewer, tester and learner with initializationId "
                    + INITIALIZATION + " at " + INITIALIZED_AT + ", only when the alias namespace is empty; a repeat is a replay.";
        }
        @Override public void apply(DynamoDbClient client, DynamoTableNames tables) {
            new DynamoStaffAliasBootstrap(client, tables).initialize(INITIALIZATION, INITIALIZED_AT, 100);
        }
        @Override public void verify(DynamoDbClient client, DynamoTableNames tables) {
            new DynamoStaffAliasVerifier(client, tables).verify();
        }
    }

    /**
     * Step 3: the membership catalog (the ten V8 plans of {@code membership_plans}) as {@code CATALOG / PLAN#id} items
     * at revision 1 plus the {@code CATALOG / META} revision controller, inserted once in one transaction. A repeat is
     * a replay: plans already there under the seeded ids are accepted as they are (the owner may have changed a price
     * since), missing ones are inserted; a plan id outside the seed means a different catalog and fails.
     */
    static final class MembershipCatalog implements DynamoMigrationStep {
        static final Instant SEEDED_AT = Instant.parse("2026-11-10T00:00:00Z");
        private final tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        @Override public int version() { return 3; }
        @Override public String name() { return "membership-catalog"; }
        @Override public String definition() {
            return "Seed the membership catalog in user-account-details: CATALOG / PLAN#<id> for " + String.join(", ", MembershipCatalogSeed.ids())
                    + " with schemaVersion 1, revision 1, the V8 definition and price, updatedAt " + SEEDED_AT + ", and CATALOG / META at revision 1;"
                    + " existing items under these ids are accepted (a replay), any other plan id fails.";
        }
        @Override public void apply(DynamoDbClient client, DynamoTableNames tables) {
            String table = tables.userAccountDetailsTableName();
            var present = new HashSet<String>();
            for (var item : MembershipItems.query(client, table, MembershipItems.CATALOG_PK, MembershipItems.PLAN_PREFIX))
                present.add(DynamoItems.string(item, "sk").substring(MembershipItems.PLAN_PREFIX.length()));
            var unknown = new HashSet<>(present); MembershipCatalogSeed.ids().forEach(unknown::remove);
            if (!unknown.isEmpty()) throw new IllegalStateException("The store holds a different membership catalog (" + unknown.size() + " unknown plan id(s))");
            var tx = DynamoWrites.transaction(client);
            for (var plan : MembershipCatalogSeed.PLANS) if (!present.contains(plan.id())) tx.insertOnly(table, MembershipCatalogSeed.planItem(json, plan, SEEDED_AT));
            if (MembershipItems.get(client, table, MembershipItems.CATALOG_PK, MembershipItems.META_SK).isEmpty()) tx.insertOnly(table, MembershipCatalogSeed.metaItem(SEEDED_AT));
            if (tx.size() == 0) return;
            try { tx.commit(); }
            catch (DynamoWrites.Conflict raced) { verify(client, tables); /* another run seeded meanwhile: the same catalog is there */ }
        }
        @Override public void verify(DynamoDbClient client, DynamoTableNames tables) {
            String table = tables.userAccountDetailsTableName();
            for (String id : MembershipCatalogSeed.ids()) {
                var item = MembershipItems.get(client, table, MembershipItems.CATALOG_PK, MembershipItems.planSk(id));
                if (item.isEmpty()) throw new IllegalStateException("Membership catalog plan " + id + " is missing");
                DynamoItems.requireSchema(item, MembershipItems.SCHEMA);
                DynamoItems.number(item, "priceCents"); DynamoItems.number(item, "revision");
                if (item.get("definition") == null || !item.get("definition").hasM()) throw new IllegalStateException("Membership catalog plan " + id + " has no definition");
            }
            var meta = MembershipItems.get(client, table, MembershipItems.CATALOG_PK, MembershipItems.META_SK);
            if (meta.isEmpty()) throw new IllegalStateException("Membership catalog META is missing");
            DynamoItems.requireSchema(meta, MembershipItems.SCHEMA);
            DynamoItems.number(meta, "revision");
        }
    }

    /**
     * Step 4: the two record-number counters of the platform review service ({@code review_record_numbers} and
     * {@code support_request_numbers} sequences): {@code SEQUENCE#review-record / COUNTER} and
     * {@code SEQUENCE#support-request / COUNTER} at lastAssigned 0, inserted only when absent so an assigned number
     * is never reset.
     */
    static final class ReviewRecordCounters implements DynamoMigrationStep {
        static final List<String> PARTITIONS = List.of("SEQUENCE#review-record", "SEQUENCE#support-request");
        static final String COUNTER = "COUNTER";
        @Override public int version() { return 4; }
        @Override public String name() { return "review-record-counters"; }
        @Override public String definition() {
            return "Initialize " + String.join(" and ", PARTITIONS) + " / " + COUNTER + " in user-account-details with schemaVersion 1 and lastAssigned 0,"
                    + " only when absent; an existing counter keeps its value.";
        }
        @Override public void apply(DynamoDbClient client, DynamoTableNames tables) {
            String table = tables.userAccountDetailsTableName();
            for (String partition : PARTITIONS) {
                if (!MembershipItems.get(client, table, partition, COUNTER).isEmpty()) continue;
                var tx = DynamoWrites.transaction(client).insertOnly(table, DynamoItems.item(partition, COUNTER, 1, Map.of("lastAssigned", DynamoItems.n(0))));
                try { tx.commit(); } catch (DynamoWrites.Conflict present) { /* assigned or initialized meanwhile */ }
            }
        }
        @Override public void verify(DynamoDbClient client, DynamoTableNames tables) {
            for (String partition : PARTITIONS) {
                Map<String, AttributeValue> counter = MembershipItems.get(client, tables.userAccountDetailsTableName(), partition, COUNTER);
                if (counter.isEmpty()) throw new IllegalStateException("Counter " + partition + " is missing");
                DynamoItems.requireSchema(counter, 1);
                if (DynamoItems.number(counter, "lastAssigned") < 0) throw new IllegalStateException("Counter " + partition + " is negative");
            }
        }
    }
}
