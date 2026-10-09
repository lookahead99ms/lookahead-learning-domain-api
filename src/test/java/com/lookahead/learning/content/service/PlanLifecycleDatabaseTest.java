package com.lookahead.learning.content.service;

import com.lookahead.learning.content.controller.PlanController;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.repository.PlanRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL, production SQL/repository/validator/transaction proxy and MVC boundary.
 * The root-owned disposable fixture is shared; every class owns and removes only its unique schema. */
@EnabledIfEnvironmentVariable(named = "DLV921_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanLifecycleDatabaseTest {
    final JsonMapper mapper = new JsonMapper();
    JdbcTemplate jdbc;
    DriverManagerDataSource source;
    String schema;
    PlanService service;
    ObjectNode template;
    AccountPrincipal principal;
    MockMvc http;

    @BeforeAll void database() throws Exception {
        String base = System.getenv("DLV921_DATABASE_URL");
        assertThat(base).matches("jdbc:postgresql://127\\.0\\.0\\.1:4392/lookahead_domain_review");
        String password = Files.readString(Path.of(System.getenv("DLV921_DATABASE_PASSWORD_FILE"))).strip();
        schema = "plan_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", password));
        admin.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(base + "?currentSchema=" + schema, "postgres", password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
        jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer)");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V1__platform.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V3__plan_names.sql")));
        try (var input = getClass().getResourceAsStream("/accounts/generated-plan.json")) {
            template = (ObjectNode) mapper.readTree(input);
        }
        service = newService();
        http = MockMvcBuilders.standaloneSetup(new PlanController(service))
            .setControllerAdvice(new AccountErrorHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType() == AccountPrincipal.class;
                }
                public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                        NativeWebRequest request, WebDataBinderFactory binder) { return principal; }
            }).build();
    }

    PlanService newService() throws Exception {
        JsonNode catalog;
        try (var input = getClass().getResourceAsStream("/accounts/catalog.json")) { catalog = mapper.readTree(input); }
        var json = new PlanJson(mapper);
        var target = new PlanService(new PlanRepository(jdbc, json), mapper, new SnapshotValidator(mapper, catalog), json);
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (PlanService) factory.getProxy();
    }

    @AfterAll void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @BeforeEach void account() {
        principal = new AccountPrincipal(UUID.randomUUID(), "synthetic", "Synthetic", true);
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)", owner());
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?)", owner(), "learn:core-java");
    }
    UUID owner() { return principal.accountId(); }
    UUID id(JsonNode result) { return UUID.fromString(result.path("planId").asText()); }
    JsonNode create() { return service.create(owner(), UUID.randomUUID(), template.deepCopy(), false).data(); }
    ObjectNode recovery(String strategy, int elapsed, int deadline) {
        var value = mapper.createObjectNode().put("strategy", strategy).put("elapsedDays", elapsed).put("deadlineDays", deadline);
        value.putArray("deferredContentIds"); return value;
    }
    ObjectNode version(JsonNode previous, String reason, JsonNode recovery) {
        var body = template.deepCopy().put("expectedRevision", previous.path("revision").asLong()).put("reason", reason);
        body.set("recovery", recovery); return body;
    }
    ObjectNode activity(JsonNode previous, String operations) {
        var body = mapper.createObjectNode().put("expectedRevision", previous.path("revision").asLong())
            .put("versionId", previous.path("versionId").asText()).put("studyDay", 1);
        body.set("operations", mapper.readTree(operations)); return body;
    }
    void failure(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    void assertSameJson(JsonNode actual, JsonNode expected) {
        // PostgreSQL JSONB reparses integral values as IntNode rather than the original LongNode.
        // Compare every serialized field exactly, independent of map order and JVM numeric node class.
        var json = new PlanJson(mapper);
        assertThat(json.canonical(actual)).isEqualTo(json.canonical(expected));
    }

    @Test void persistedCreateRetryPaginationOwnershipAndRenamingSurviveServiceReconstruction() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode first = service.create(owner(), key, template, false).data();
        assertThat(first.path("planNumber").asLong()).isEqualTo(1);
        assertThat(first.path("name").asText()).startsWith("Study plan #1_");
        service = newService();
        assertSameJson(service.create(owner(), key, template, false).data(), first);
        failure("IDEMPOTENCY_KEY_REUSED", () -> service.create(owner(), key, template.deepCopy().put("goal", "Changed"), false));
        create();
        JsonNode page = service.list(owner(), 1, null);
        assertThat(page.path("plans")).hasSize(1);
        assertThat(page.path("nextPlanNumber").asLong()).isEqualTo(3);
        assertThat(service.list(owner(), 1, page.path("nextCursor").asText()).path("plans")).hasSize(1);
        UUID otherOwner = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)", otherOwner);
        assertThat(service.list(otherOwner, 20, null).path("plans")).isEmpty();
        failure("PLAN_NOT_FOUND", () -> service.get(UUID.randomUUID(), id(first), null));
        failure("VERSION_NOT_FOUND", () -> service.get(owner(), id(first), UUID.randomUUID()));
        for (String cursor : new String[]{"bad!", "LTE=", "MTAwMDAx"})
            assertThatThrownBy(() -> service.list(owner(), 1, cursor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(owner(), 0, null)).isInstanceOf(IllegalArgumentException.class);
        var rename = mapper.createObjectNode().put("expectedRevision", 1).put("name", "  Interview practice  ");
        UUID renameKey = UUID.randomUUID();
        JsonNode renamed = service.rename(owner(), id(first), renameKey, rename).data();
        assertThat(renamed.path("name").asText()).isEqualTo("Interview practice");
        assertThat(renamed.path("revision").asLong()).isEqualTo(2);
        assertSameJson(service.rename(owner(), id(first), renameKey, rename).data(), renamed);
        failure("REVISION_CONFLICT", () -> service.rename(owner(), id(first), UUID.randomUUID(), rename));
        failure("REVISION_REQUIRED", () -> service.rename(owner(), id(first), UUID.randomUUID(), mapper.createObjectNode().put("name", "Lost revision")));
    }

    @Test void progressOperationsAreAtomicAndCurrentGrantsApplyToRetriesWhileNotesRemainEditable() {
        JsonNode first = create();
        var body = activity(first, """
          [{"type":"recordAttempt","assignmentId":"smoke-session-fixture-a","canonicalContentId":"fixture-a","outcome":"needs-review"},
           {"type":"setSessionCompletion","assignmentId":"smoke-session-fixture-a","completed":true},
           {"type":"setContentCompletion","canonicalContentId":"fixture-a","completed":true},
           {"type":"setNote","canonicalContentId":"fixture-a","text":"Check invariant"}]
          """);
        UUID key = UUID.randomUUID();
        JsonNode updated = service.activity(owner(), id(first), key, body).data();
        assertThat(updated.path("progress").path("completedContentIds").get(0).asText()).isEqualTo("fixture-a");
        assertThat(updated.path("progress").path("needsReviewContentIds")).isEmpty();
        assertThat(updated.path("progress").path("notes").path("fixture-a").asText()).isEqualTo("Check invariant");
        assertThat(updated.path("progress").path("studyLog")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_activity WHERE plan_id=?", Integer.class, id(first))).isEqualTo(4);
        jdbc.update("DELETE FROM account_grants WHERE account_id=?", owner());
        assertThat(service.activity(owner(), id(first), key, body).data().path("restrictedContentIds").get(0).asText()).isEqualTo("fixture-a");
        failure("CONTENT_ACCESS_DENIED", () -> service.activity(owner(), id(first), UUID.randomUUID(), activity(updated,
            "[{\"type\":\"recordAttempt\",\"assignmentId\":\"smoke-session-fixture-a\",\"canonicalContentId\":\"fixture-a\",\"outcome\":\"attempted\"}]")));
        var removal = activity(updated, """
          [{"type":"setSessionCompletion","assignmentId":"smoke-session-fixture-a","completed":false},
           {"type":"setContentCompletion","canonicalContentId":"fixture-a","completed":false},
           {"type":"setNote","canonicalContentId":"fixture-a","text":""}]
          """);
        JsonNode removed = service.activity(owner(), id(first), UUID.randomUUID(), removal).data();
        assertThat(removed.path("progress").path("completedSessionIds")).isEmpty();
        assertThat(removed.path("progress").path("notes")).isEmpty();
        var invalid = activity(removed, """
          [{"type":"setNote","canonicalContentId":"fixture-a","text":"Must roll back"},{"type":"unknown"}]
          """);
        assertThatThrownBy(() -> service.activity(owner(), id(first), UUID.randomUUID(), invalid)).hasMessage("Unknown activity type");
        assertThat(service.get(owner(), id(first), null)).isEqualTo(removed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_activity WHERE plan_id=?", Integer.class, id(first))).isEqualTo(7);
    }

    @Test void versionHistoryRecoveryAndExplicitExtensionKeepPinnedWorkAndRejectHiddenChanges() {
        JsonNode first = create();
        var update = version(first, "update-plan", recovery("none", 0, 7));
        UUID updateKey = UUID.randomUUID();
        JsonNode second = service.saveVersion(owner(), id(first), updateKey, update).data();
        assertThat(second.path("versionId")).isNotEqualTo(first.path("versionId"));
        assertSameJson(service.saveVersion(owner(), id(first), updateKey, update).data(), second);
        assertThat(service.get(owner(), id(first), UUID.fromString(first.path("versionId").asText())).path("snapshot")).isEqualTo(first.path("snapshot"));
        var fixed = version(second, "recovery", recovery("fixed-window", 1, 7));
        JsonNode third = service.saveVersion(owner(), id(first), UUID.randomUUID(), fixed).data();
        assertThat(third.path("recovery").path("elapsedDays").asInt()).isEqualTo(1);
        var backwards = version(third, "recovery", recovery("fixed-window", 0, 7));
        assertThatThrownBy(() -> service.saveVersion(owner(), id(first), UUID.randomUUID(), backwards)).hasMessageContaining("backwards");
        var changedBudget = version(third, "recovery", recovery("fixed-window", 1, 7));
        ((ObjectNode)changedBudget.path("snapshot").path("config")).put("dailyHours", 2);
        assertThatThrownBy(() -> service.saveVersion(owner(), id(first), UUID.randomUUID(), changedBudget)).hasMessageContaining("daily budget");
        var extension = version(third, "extend-deadline", recovery("explicit-extension", 1, 8));
        ObjectNode snap = (ObjectNode) extension.path("snapshot");
        ((ObjectNode)snap.path("config")).put("days", 8);
        var day = ((ObjectNode)snap.path("days").get(6)).deepCopy().put("day", 8);
        ((tools.jackson.databind.node.ArrayNode)snap.path("days")).add(day);
        var nextWeek = mapper.createObjectNode().put("number", 2).put("label", "Extension");
        nextWeek.putArray("days").add(day.deepCopy());
        ((tools.jackson.databind.node.ArrayNode)snap.path("weeks")).add(nextWeek);
        JsonNode fourth = service.saveVersion(owner(), id(first), UUID.randomUUID(), extension).data();
        assertThat(fourth.path("recovery").path("deadlineDays").asInt()).isEqualTo(8);
        assertThat(fourth.path("revision").asInt()).isEqualTo(4);
    }

    @Test void legacyImportPreservesRawProvenanceAndMapsOnlyKnownProgress() {
        var local = mapper.createObjectNode().put("schemaVersion", "study-plan-local/v1").put("revision", 3)
            .put("goal", "Imported practice").put("shiftedDays", 2);
        local.putNull("catalogVersion").putNull("rankingVersion");
        local.set("snapshot", template.path("snapshot").deepCopy());
        local.putArray("completedIds").add("fixture-a").add("smoke-session-fixture-a").add("unrelated-old-content");
        local.putArray("history");
        local.putObject("reviewNotes").put("fixture-a", "Keep my note");
        var pins = ((ObjectNode)template.path("provenance")).deepCopy().put("origin", "legacy-local-import");
        pins.putNull("catalogVersion").putNull("rankingVersion");
        var body = mapper.createObjectNode().put("sourceSchemaVersion", "study-plan-local/v1");
        body.set("localSnapshot", local); body.set("provenance", pins);
        UUID key = UUID.randomUUID();
        JsonNode imported = service.create(owner(), key, body, true).data();
        assertThat(imported.path("progress").path("completedContentIds")).hasSize(1);
        assertThat(imported.path("progress").path("completedSessionIds")).hasSize(1);
        assertThat(imported.path("progress").path("legacySource").path("completedIds")).hasSize(3);
        assertThat(imported.path("recovery").path("legacyShiftedDays").asInt()).isEqualTo(2);
        assertSameJson(service.create(owner(), key, body, true).data(), imported);
    }

    @Test void deletionTombstonesEarlierReceiptsAndRetriesCannotResurrectPrivateData() {
        UUID createKey = UUID.randomUUID();
        JsonNode first = service.create(owner(), createKey, template, false).data();
        UUID deleteKey = UUID.randomUUID();
        assertThat(service.delete(owner(), id(first), deleteKey, 1).status()).isEqualTo(204);
        assertThat(service.delete(owner(), id(first), deleteKey, 1).status()).isEqualTo(204);
        failure("PLAN_DELETED", () -> service.create(owner(), createKey, template, false));
        failure("PLAN_NOT_FOUND", () -> service.get(owner(), id(first), null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_versions WHERE plan_id=?", Integer.class, id(first))).isZero();
        assertThat(jdbc.queryForObject("SELECT response IS NULL FROM mutation_receipts WHERE mutation_key=?", Boolean.class, createKey)).isTrue();
        assertThat(create().path("planNumber").asInt()).isEqualTo(2);
    }

    @Test void actualMvcContractRequiresKeysAndRevisionsAndReturnsNoStoreAcrossLifecycle() throws Exception {
        http.perform(get("/api/v1/account-catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.data.planNamingPolicies[0]").value("plan-name-v1"));
        http.perform(post("/api/v1/plans").contentType("application/json").content(template.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        String response = http.perform(post("/api/v1/plans").header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(template.toString()))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(response).path("data"); String path = "/api/v1/plans/" + id(created);
        http.perform(get("/api/v1/plans")).andExpect(status().isOk()).andExpect(jsonPath("$.data.plans.length()").value(1));
        http.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        http.perform(get(path + "/versions/" + created.path("versionId").asText())).andExpect(status().isOk());
        http.perform(post(path + "/name").header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content("{\"expectedRevision\":1,\"name\":\"Renamed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        http.perform(delete(path).header("Idempotency-Key", UUID.randomUUID())).andExpect(status().is(428));
        http.perform(delete(path).header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "2")).andExpect(status().isBadRequest());
        http.perform(delete(path).header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "\"revision-1\""))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.currentRevision").value(2));
        http.perform(delete(path).header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "\"revision-2\""))
            .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        http.perform(get(path)).andExpect(status().isNotFound());
    }
}
