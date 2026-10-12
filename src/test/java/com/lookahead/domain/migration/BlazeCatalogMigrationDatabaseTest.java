package com.lookahead.domain.migration;

import com.lookahead.domain.DomainApiMigration;
import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.service.PlanService;
import com.lookahead.learning.content.util.CatalogIds;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLV-931 V5 on real PostgreSQL through the production Flyway configuration: data written before
 * the rename (look-ahead IDs) is migrated to blaze, digests are recomputed, prose and platform
 * names are untouched, and the migrated plan keeps working through the plan service.
 * Uses the disposable author-document-review fixture; each run owns and removes one schema.
 */
@EnabledIfEnvironmentVariable(named = "DLV921_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlazeCatalogMigrationDatabaseTest {
    static final String CATALOG_VERSION = "sha256:" + "c".repeat(64);
    final JsonMapper mapper = new JsonMapper();
    final PlanJson json = new PlanJson(mapper);
    final UUID owner = UUID.randomUUID(), both = UUID.randomUUID(), plain = UUID.randomUUID();
    final UUID plan = UUID.randomUUID(), version = UUID.randomUUID(), cleanPlan = UUID.randomUUID(), cleanVersion = UUID.randomUUID();
    final UUID receipt = UUID.randomUUID(), tombstone = UUID.randomUUID();
    String schema, url, password, legacyDigest, cleanDigest;
    JdbcTemplate jdbc;
    DriverManagerDataSource source;

    @BeforeAll void migrate() throws Exception {
        String base = System.getenv("DLV921_DATABASE_URL");
        password = Files.readString(Path.of(System.getenv("DLV921_DATABASE_PASSWORD_FILE"))).strip();
        schema = "blaze_v5_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", password));
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
        admin.execute("CREATE SCHEMA " + schema);
        url = base + "?currentSchema=" + schema;
        source = new DriverManagerDataSource(url, "postgres", password);
        jdbc = new JdbcTemplate(source);

        // The schema as it was before DLV-931, then data written by the old code.
        flyway().target("4").load().migrate();
        // Plans and their first version reference each other through deferred keys: one transaction.
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                .executeWithoutResult(status -> seedLegacyData());
        var result = flyway().load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.targetSchemaVersion).isEqualTo("5");
    }

    org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        // Production migration discovery, isolated schema, and explicit V5 boundary.
        // Later migrations are covered by the full bootstrap/database suites.
        return DomainApiMigration.configure(Flyway.configure().dataSource(url, "postgres", password)).defaultSchema(schema).schemas(schema).target("5");
    }

    @AfterAll void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    JsonNode legacySnapshot() {
        ObjectNode snapshot = (ObjectNode) mapper.readTree("""
            {"config":{"days":1,"dailyHours":1,"topicIds":["look-ahead:system-design"],"accessTopicIds":["look-ahead:system-design"],
                       "familiarity":{"look-ahead:system-design":"new"}},
             "focusedDailyHours":1,"bufferHours":0,"excludedTopics":[],"uniqueNewItems":1,"reviewAssignments":0,
             "schedulingVersion":"study-schedule/v2",
             "includedTopics":[{"id":"look-ahead:system-design","path":"look-ahead","title":"System Design","description":"","courseIds":["system-design"]}],
             "days":[{"day":1,"phase":"Start","focus":"System Design","newCount":1,"reviewCount":0,"focusedMinutes":30,
               "assignments":[{"id":"look-ahead:system-design:caching","kind":"new","activity":"Understand","topicId":"look-ahead:system-design",
                 "topicTitle":"System Design","title":"Caching","courseTitle":"System Design","contentType":"theory",
                 "route":["/","look-ahead","system-design","caching"],"minutes":30}]}],"weeks":[]}
            """);
        snapshot.withArray("weeks").addObject().put("number", 1).put("label", "Start").set("days", snapshot.path("days").deepCopy());
        return snapshot;
    }

    void seedLegacyData() {
        for (UUID subject : List.of(owner, both, plain)) jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)", subject);
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?),(?,?)", owner, "look-ahead:system-design", owner, "learn:core-java");
        // An account that already holds both forms keeps the longer-lasting grant and loses the legacy row.
        jdbc.update("INSERT INTO account_grants(account_id,topic_id,valid_until) VALUES (?,?,?),(?,?,?),(?,?,NULL)",
                both, "look-ahead:system-design", Timestamp.from(Instant.parse("2027-06-01T00:00:00Z")),
                both, "blaze:system-design", Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                both, "look-ahead:distributed-systems");
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)", plain, "grow:spring-boot");

        JsonNode snapshot = legacySnapshot();
        legacyDigest = json.digest(json.canonical(snapshot));
        JsonNode progress = mapper.readTree("""
            {"completedContentIds":[],"completedSessionIds":["look-ahead:system-design:caching"],"attemptedContentIds":["look-ahead:system-design:caching"],
             "needsReviewContentIds":[],"notes":{"look-ahead:system-design:caching":"look-ahead: re-read the eviction part"},
             "sessionOutcomes":{"look-ahead:system-design:caching":"completed"},
             "studyLog":[{"assignmentId":"look-ahead:system-design:caching","day":1,"minutes":30,"recordedAt":"2026-10-01T10:00:00Z"}],
             "legacySource":{"schemaVersion":"study-plan-local/v1","storageKey":"look-ahead.study-plan.v1","completedIds":["look-ahead:system-design:caching"],
                             "reviewNotes":{"look-ahead:system-design:caching":"look-ahead"},"goal":"look-ahead"}}
            """);
        String provenance = "{\"snapshotSchemaVersion\":\"study-plan/v1\",\"algorithmVersion\":\"study-schedule/v2\",\"catalogVersion\":\"" + CATALOG_VERSION
                + "\",\"rankingVersion\":null,\"origin\":\"generated\",\"validatedAgainstCatalogVersion\":\"" + CATALOG_VERSION + "\"}";
        jdbc.update("INSERT INTO plans(id,account_id,revision,current_version,goal,progress,name,plan_number) VALUES (?,?,1,?,?,?::jsonb,?,1)",
                plan, owner, version, "look-ahead", json.json(progress), "My look-ahead plan");
        jdbc.update("INSERT INTO plan_versions(id,account_id,plan_id,parent_version,snapshot,snapshot_digest,membership,provenance,recovery,reason) VALUES (?,?,?,NULL,?::jsonb,?,?::jsonb,?::jsonb,?::jsonb,'create')",
                version, owner, plan, json.json(snapshot), legacyDigest,
                "{\"look-ahead:system-design:caching\":\"look-ahead:system-design:caching\"}", provenance,
                "{\"strategy\":\"none\",\"elapsedDays\":0,\"deadlineDays\":1,\"deferredContentIds\":[\"look-ahead:system-design:caching\"]}");
        jdbc.update("INSERT INTO plan_activity(id,account_id,plan_id,version_id,revision,kind,payload) VALUES (?,?,?,?,1,'setNote',?::jsonb)",
                UUID.randomUUID(), owner, plan, version, "{\"type\":\"setNote\",\"canonicalContentId\":\"look-ahead:system-design:caching\",\"text\":\"look-ahead\"}");
        jdbc.update("INSERT INTO mutation_receipts(account_id,mutation_key,request_hash,plan_id,response,response_status) VALUES (?,?,?,?,?::jsonb,201)",
                owner, receipt, "sha256:" + "e".repeat(64), plan, "{\"planId\":\"" + plan + "\",\"snapshot\":" + json.json(snapshot) + ",\"restrictedContentIds\":[]}");
        jdbc.update("INSERT INTO mutation_receipts(account_id,mutation_key,request_hash,plan_id,response,response_status,deleted) VALUES (?,?,?,?,NULL,204,true)",
                owner, tombstone, "sha256:" + "f".repeat(64), plan);

        // A plan with no catalog IDs to rewrite is left byte-for-byte as it was.
        JsonNode clean = mapper.readTree(json.json(snapshot).replace("look-ahead:", "learn:").replace("\"look-ahead\"", "\"learn\""));
        cleanDigest = json.digest(json.canonical(clean));
        jdbc.update("INSERT INTO plans(id,account_id,revision,current_version,goal,progress,name,plan_number) VALUES (?,?,1,?,'Java',?::jsonb,'Java plan',1)",
                cleanPlan, plain, cleanVersion, "{\"completedContentIds\":[],\"notes\":{}}");
        jdbc.update("INSERT INTO plan_versions(id,account_id,plan_id,parent_version,snapshot,snapshot_digest,membership,provenance,recovery,reason) VALUES (?,?,?,NULL,?::jsonb,?,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'create')",
                cleanVersion, plain, cleanPlan, json.json(clean), "sha256:not-recomputed-for-unchanged-rows");
    }

    @Test void grantsMoveToBlazeAndAnAccountHoldingBothKeepsTheLongerGrant() {
        assertThat(jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id=? ORDER BY topic_id", String.class, owner))
                .containsExactly("blaze:system-design", "learn:core-java");
        assertThat(jdbc.queryForObject("SELECT valid_until FROM account_grants WHERE account_id=? AND topic_id='blaze:system-design'", Timestamp.class, owner)).isNull();
        assertThat(jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id=? ORDER BY topic_id", String.class, both))
                .containsExactly("blaze:distributed-systems", "blaze:system-design");
        assertThat(jdbc.queryForObject("SELECT valid_until FROM account_grants WHERE account_id=? AND topic_id='blaze:system-design'", Timestamp.class, both).toInstant())
                .isEqualTo(Instant.parse("2027-06-01T00:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT valid_until FROM account_grants WHERE account_id=? AND topic_id='blaze:distributed-systems'", Timestamp.class, both)).isNull();
        assertThat(jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id=?", String.class, plain)).containsExactly("grow:spring-boot");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE topic_id LIKE '%look-ahead%'", Integer.class)).isZero();
    }

    @Test void plansAreRewrittenWithRecomputedDigestsAndProseAndPlatformNamesUntouched() {
        JsonNode snapshot = json.parse(jdbc.queryForObject("SELECT snapshot::text FROM plan_versions WHERE id=?", String.class, version));
        assertThat(snapshot).isEqualTo(CatalogIds.normalize(legacySnapshot()));
        assertThat(snapshot.path("days").get(0).path("assignments").get(0).path("route").get(1).asText()).isEqualTo("blaze");
        assertThat(snapshot.path("config").path("familiarity").has("blaze:system-design")).isTrue();
        String digest = jdbc.queryForObject("SELECT snapshot_digest FROM plan_versions WHERE id=?", String.class, version);
        assertThat(digest).isEqualTo(json.digest(json.canonical(snapshot))).isNotEqualTo(legacyDigest);

        JsonNode progress = json.parse(jdbc.queryForObject("SELECT progress::text FROM plans WHERE id=?", String.class, plan));
        assertThat(progress.path("completedSessionIds").get(0).asText()).isEqualTo("blaze:system-design:caching");
        assertThat(progress.path("sessionOutcomes").path("blaze:system-design:caching").asText()).isEqualTo("completed");
        assertThat(progress.path("studyLog").get(0).path("assignmentId").asText()).isEqualTo("blaze:system-design:caching");
        assertThat(progress.path("notes").path("blaze:system-design:caching").asText()).isEqualTo("look-ahead: re-read the eviction part");
        assertThat(progress.path("legacySource").path("storageKey").asText()).isEqualTo("look-ahead.study-plan.v1");
        assertThat(progress.path("legacySource").path("reviewNotes").path("blaze:system-design:caching").asText()).isEqualTo("look-ahead");
        assertThat(progress.path("legacySource").path("goal").asText()).isEqualTo("look-ahead");
        assertThat(jdbc.queryForMap("SELECT goal,name FROM plans WHERE id=?", plan)).containsEntry("goal", "look-ahead").containsEntry("name", "My look-ahead plan");

        assertThat(jdbc.queryForObject("SELECT membership::text FROM plan_versions WHERE id=?", String.class, version))
                .isEqualTo("{\"blaze:system-design:caching\": \"blaze:system-design:caching\"}");
        JsonNode recovery = json.parse(jdbc.queryForObject("SELECT recovery::text FROM plan_versions WHERE id=?", String.class, version));
        assertThat(recovery.path("deferredContentIds").get(0).asText()).isEqualTo("blaze:system-design:caching");
        JsonNode provenance = json.parse(jdbc.queryForObject("SELECT provenance::text FROM plan_versions WHERE id=?", String.class, version));
        assertThat(provenance.path("catalogVersion").asText()).isEqualTo(CATALOG_VERSION);
        JsonNode payload = json.parse(jdbc.queryForObject("SELECT payload::text FROM plan_activity WHERE plan_id=? AND kind='setNote'", String.class, plan));
        assertThat(payload.path("canonicalContentId").asText()).isEqualTo("blaze:system-design:caching");
        assertThat(payload.path("text").asText()).isEqualTo("look-ahead");

        var stored = jdbc.queryForMap("SELECT request_hash, response::text AS response FROM mutation_receipts WHERE mutation_key=?", receipt);
        assertThat(stored.get("request_hash")).isEqualTo("sha256:" + "e".repeat(64));
        assertThat(CatalogIds.containsLegacy(json.parse((String) stored.get("response")))).isFalse();
        assertThat(jdbc.queryForObject("SELECT response IS NULL AND deleted FROM mutation_receipts WHERE mutation_key=?", Boolean.class, tombstone)).isTrue();

        for (String query : List.of("SELECT count(*) FROM plan_versions WHERE (snapshot::text || membership::text || recovery::text) ~ '(^|[\":])look-ahead[\":]'",
                "SELECT count(*) FROM plan_activity WHERE payload->>'canonicalContentId' LIKE 'look-ahead%'"))
            assertThat(jdbc.queryForObject(query, Integer.class)).isZero();
    }

    @Test void rowsWithoutCatalogIdsAreUntouched() {
        assertThat(jdbc.queryForObject("SELECT snapshot_digest FROM plan_versions WHERE id=?", String.class, cleanVersion))
                .isEqualTo("sha256:not-recomputed-for-unchanged-rows");
        assertThat(cleanDigest).startsWith("sha256:");
    }

    @Test void historyRecordsVersionFiveAndRepeatingMigrationIsANoOp() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(jdbc.queryForObject("SELECT type FROM flyway_schema_history WHERE version='5'", String.class)).isEqualTo("JDBC");
        flyway().load().validate();
        assertThat(flyway().load().migrate().migrationsExecuted).isZero();
    }

    @Test void theMigratedPlanStillLoadsAndAcceptsProgressWithTheBlazeCatalog() {
        var catalog = new SnapshotValidator(mapper, mapper.readTree("""
            {"schemaVersion":"account-catalog/v1","catalogVersion":"VERSION","algorithmVersions":["study-schedule/v2"],"rankingVersions":[],
             "records":[{"id":"blaze:system-design:caching","aliases":[],"contentType":"theory",
               "topicIds":["blaze:system-design"],"route":["/","blaze","system-design","caching"]}]}
            """.replace("VERSION", CATALOG_VERSION)));
        var service = new PlanService(new PlanRepository(jdbc, json), mapper, catalog, json);
        JsonNode view = service.get(owner, plan, null);
        assertThat(view.path("restrictedContentIds")).isEmpty();
        assertThat(view.path("snapshot").path("includedTopics").get(0).path("path").asText()).isEqualTo("blaze");
        var body = mapper.readTree("""
            {"expectedRevision":1,"versionId":"VERSION","studyDay":1,
             "operations":[{"type":"setContentCompletion","canonicalContentId":"look-ahead:system-design:caching","completed":true}]}
            """.replace("VERSION", version.toString()));
        JsonNode updated = service.activity(owner, plan, UUID.randomUUID(), body).data();
        assertThat(updated.path("progress").path("completedContentIds").get(0).asText()).isEqualTo("blaze:system-design:caching");
    }
}
