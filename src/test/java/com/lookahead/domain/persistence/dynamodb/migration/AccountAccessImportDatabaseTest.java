package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.access.DynamoAdmissionStore;
import com.lookahead.domain.access.DynamoStaffAccessRepository;
import com.lookahead.domain.cloud.CognitoIdentity;
import com.lookahead.domain.cloud.DynamoCloudAccounts;
import com.lookahead.domain.cloud.DynamoCloudSignInRegistry;
import com.lookahead.domain.cloud.DynamoLocalFixture;
import com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasStore;
import com.lookahead.domain.persistence.dynamodb.DynamoStaffAliasVerifier;
import com.lookahead.domain.persistence.dynamodb.DynamoTableNames;
import com.lookahead.domain.testaccounts.TestAccountRoster;
import com.lookahead.learning.content.exception.AccountFailure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lookahead.domain.cloud.CloudAccountItems.get;
import static com.lookahead.domain.cloud.DynamoLocalFixture.TABLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DLV-970 step 5 on both real fixtures: a PostgreSQL schema with the platform migrations and the people of a small DEV,
 * and DynamoDB Local. The import is run through the real catalog (steps 1 to 4 first), then checked through the stores
 * and services the running application uses, never by reading items back alone.
 */
@EnabledIfEnvironmentVariable(named = "DLV970_DYNAMODB_TEST_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CLOUD_AUTH_TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountAccessImportDatabaseTest {
    static final String ISSUER = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Synthetic";
    static final Instant T0 = Instant.parse("2026-11-01T08:00:00.123456Z"), NOW = Instant.parse("2026-11-17T18:00:00Z");
    static final List<String> FILES = List.of("V1__platform.sql", "V2__author_review_events.sql", "V3__plan_names.sql", "V4__cloud_identity_and_sign_ins.sql",
            "V6__staff_access.sql", "V7__cloud_account_preferences.sql", "V8__memberships.sql", "V9__plan_lifecycle.sql", "V10__test_account_registry.sql",
            "V11__free_plan_allowances.sql", "V12__created_test_accounts.sql", "V13__real_account_maintenance.sql", "V14__managed_test_purchase_purge_grants.sql",
            "V15__retain_runtime_audit.sql", "V17__dev_access_admission.sql", "V18__access_removal_and_staff_aliases.sql", "V19__cloud_identities.sql",
            "V20__cloud_merge_tokens.sql", "V21__learner_test_accounts_only.sql", "V22__review_records_and_status_events.sql");

    static DynamoDbClient client;
    String url, password, schema; JdbcTemplate jdbc; DriverManagerDataSource source;
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final UUID owner = UUID.randomUUID(), admin = UUID.randomUUID(), waiting = UUID.randomUUID(), disabled = UUID.randomUUID(), removed = UUID.randomUUID(),
            tester = UUID.randomUUID(), invitation = UUID.randomUUID(), assignment = UUID.randomUUID();

    @BeforeAll void connect() throws Exception {
        client = DynamoLocalFixture.connect();
        url = System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth")) throw new IllegalStateException("Use the disposable cloud-auth test database only");
        password = Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
    }
    @AfterAll void close() { if (client != null) client.close(); }
    /** A fresh PostgreSQL schema with every platform migration, and a clean DynamoDB store, for each test (the audit tables are append-only). */
    @BeforeEach void fresh() throws Exception {
        schema = "import_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", password));
        admin.execute("CREATE SCHEMA " + schema);
        for (String role : List.of("lookahead_platform_app", "lookahead_platform_migrator"))
            if (admin.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='" + role + "'", Integer.class) == 0) admin.execute("CREATE ROLE " + role + " NOLOGIN");
        source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "postgres", password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO lookahead_platform_app");
        jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer PRIMARY KEY)"); // V1 grants on it; Flyway itself is not run here
        for (String file : FILES) jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain", file)));
        DynamoLocalFixture.purgeLaneA(client);
        for (String prefix : List.of("DATA_VERSION#DOMAIN")) DynamoLocalFixture.purge(client, TABLES.protectedOwnerIdentityTableName(), prefix);
        for (String prefix : List.of("STAFF_ROLE#", "CATALOG", "SEQUENCE#", "IMPORT#")) DynamoLocalFixture.purge(client, TABLES.userAccountDetailsTableName(), prefix);
        for (String prefix : List.of("STAFF_ROLE#", "STAFF_ALIAS#")) DynamoLocalFixture.purge(client, TABLES.administratorAuditEventsTableName(), prefix);
        DynamoLocalFixture.seedOwner(client, ISSUER, "owner-subject");
        seedPostgres();
    }
    @AfterEach void dropSchema() { if (schema != null) new JdbcTemplate(new DriverManagerDataSource(url, "postgres", password)).execute("DROP SCHEMA " + schema + " CASCADE"); }

    /** A small DEV: the owner, an admin with a Google identity too, a waiting person, a disabled one, a removed one, a test learner, a pending reviewer invitation. */
    void seedPostgres() {
        for (UUID id : List.of(owner, admin, waiting, disabled, removed, tester)) jdbc.update("INSERT INTO platform_subjects(id,last_plan_number) VALUES(?,?)", id, id.equals(admin) ? 3 : 0);
        account(owner, ISSUER, "owner-subject", "owner@example.invalid", "Owner", true, true, T0, T0.plusSeconds(60), null);
        account(admin, ISSUER, "admin-subject", "Admin@Example.invalid", "Ada Admin", true, true, T0.plusSeconds(100), T0.plusSeconds(200), owner);
        account(waiting, ISSUER, "waiting-subject", "waiting@example.invalid", "Will Wait", false, true, T0.plusSeconds(300), null, null);
        account(disabled, ISSUER, "disabled-subject", "disabled@example.invalid", "Dee Disabled", true, true, T0.plusSeconds(400), T0.plusSeconds(500), owner);
        account(removed, ISSUER, "removed-subject", "removed@example.invalid", "Ray Removed", true, true, T0.plusSeconds(600), T0.plusSeconds(700), owner);
        account(tester, TestAccountRoster.TEST_ISSUER, tester.toString(), null, "learner01", true, true, T0.plusSeconds(800), T0.plusSeconds(800), owner);
        jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES(?,?,?,?)", tester, "learner01", "scenario", "fixture");
        jdbc.update("UPDATE cloud_accounts SET author_access=true WHERE account_id=?", owner);
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_at,changed_by) VALUES(?,?,?,?)", disabled, "disabled", at(T0.plusSeconds(900)), owner);
        jdbc.update("INSERT INTO cloud_account_states(account_id,state,changed_at,changed_by) VALUES(?,?,?,?)", removed, "removed", at(T0.plusSeconds(901)), owner);
        // Identities: the V19 backfill row for everyone, plus the admin's Google sign-in linked later.
        jdbc.query("SELECT account_id, issuer, subject, username, verified_email, requested_at FROM cloud_accounts", r -> {
            jdbc.update("INSERT INTO cloud_identities(id,account_id,issuer,subject,username,provider,verified_email,link_source,linked_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), r.getObject("account_id", UUID.class), r.getString("issuer"), r.getString("subject"), r.getString("username"),
                    r.getString("issuer").equals(TestAccountRoster.TEST_ISSUER) ? "test" : "cognito", r.getString("verified_email"), "backfill", r.getObject("requested_at", java.time.OffsetDateTime.class));
        });
        jdbc.update("INSERT INTO cloud_identities(id,account_id,issuer,subject,username,provider,verified_email,link_source,linked_at) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), admin, ISSUER, "google-subject", "Google_12345", "google", "admin@example.invalid", "linked", at(T0.plusSeconds(1000)));
        for (UUID id : List.of(owner, admin, waiting, disabled, removed)) admission(id, null, "request", "[]", T0.plusSeconds(10));
        admission(owner, null, "admit", "[]", T0.plusSeconds(60));
        admission(admin, owner, "admit", "[\"admin\"]", T0.plusSeconds(200));
        admission(disabled, owner, "admit", "[]", T0.plusSeconds(500));
        admission(disabled, owner, "disable", "[]", T0.plusSeconds(900));
        jdbc.update("INSERT INTO staff_access(id,account_id,pending_email,roles,topic_grants,full_curriculum,revision,updated_at) VALUES(?,?,?,?::jsonb,?::jsonb,?,?,?)",
                assignment, admin, null, "[\"admin\"]", "[]", false, 2, at(T0.plusSeconds(200)));
        jdbc.update("INSERT INTO staff_access_events(event_id,assignment_id,actor_id,action,previous_state,next_state,recorded_at) VALUES(?,?,?,?,?::jsonb,?::jsonb,?)",
                UUID.randomUUID(), assignment, owner, "assign", null, "{\"id\":\"" + assignment + "\",\"revision\":2,\"roles\":[\"admin\"]}", at(T0.plusSeconds(200)));
        jdbc.update("INSERT INTO staff_access(id,account_id,pending_email,roles,topic_grants,full_curriculum,revision,updated_at) VALUES(?,?,?,?::jsonb,?::jsonb,?,?,clock_timestamp())",
                invitation, null, "reviewer@example.invalid", "[\"reviewer\"]", "[]", false, 1);
        // Labels: the retired test staff account took Admin 1 (V21 kept the alias), the real admin is Admin 2; the learner label belongs to the test account.
        jdbc.update("INSERT INTO staff_aliases(account_id,role,number,assigned_at) VALUES(?,?,?,?)", tester, "admin", 1, at(T0.plusSeconds(150)));
        jdbc.update("INSERT INTO staff_aliases(account_id,role,number,assigned_at) VALUES(?,?,?,?)", admin, "admin", 2, at(T0.plusSeconds(201)));
        jdbc.update("INSERT INTO staff_aliases(account_id,role,number,assigned_at) VALUES(?,?,?,?)", tester, "learner", 1, at(T0.plusSeconds(800)));
        jdbc.execute("SELECT setval('review_record_seq', 17)");
    }
    void account(UUID id, String issuer, String subject, String email, String name, boolean admitted, boolean enabled, Instant requested, Instant admittedAt, UUID by) {
        jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,enabled,author_access,credential_change_pending,username,display_name,requested_at,admitted_at,admitted_by,verified_email)"
                + " VALUES(?,?,?,?,?,false,false,?,?,?,?,?,?)", id, issuer, subject, admitted, enabled, email == null ? name : email, name, at(requested), admittedAt == null ? null : at(admittedAt), by, email);
    }
    void admission(UUID id, UUID actor, String action, String roles, Instant at) {
        jdbc.update("INSERT INTO cloud_admission_events(id,account_id,issuer,subject,actor_id,action,roles,recorded_at) VALUES(?,?,?,?,?,?,?::jsonb,?)",
                UUID.randomUUID(), id, ISSUER, id + "-subject", actor, action, roles, at(at));
    }
    static java.time.OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }

    DynamoMigrationRunner runner() { return new DynamoMigrationRunner(client, TABLES, DomainDynamoCatalog.catalog(), clock, DynamoMigrationSources.of("local", source)); }
    DynamoCloudAccounts accounts() { return new DynamoCloudAccounts(client, TABLES, clock); }
    CognitoIdentity identity(String subject, String username, String email) { return new CognitoIdentity(ISSUER, subject, UUID.randomUUID().toString(), username, "Person " + subject, NOW, email); }
    static String proof(char c) { return String.valueOf(c).repeat(43); }

    @Test void theRealPeopleArriveWithTheirIdsIdentitiesRolesLabelsAndCountersAndTheStoreServesThem() {
        // The store already recorded the admin's post-cutover sign-in as a waiting account: it must make way for the imported one.
        var registry = new DynamoCloudSignInRegistry(client, TABLES, clock);
        assertThatThrownBy(() -> registry.admit(identity("admin-subject", "Admin@Example.invalid", "Admin@Example.invalid"), proof('a')))
                .isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("ACCOUNT_ADMISSION_REQUIRED"));
        UUID strayId = accounts().accountOf(ISSUER, "admin-subject");
        assertThat(strayId).isNotEqualTo(admin);

        var outcome = runner().migrate("github-5");
        assertThat(outcome.applied()).containsExactly("1 base-tables", "2 staff-alias-counters", "3 membership-catalog", "4 review-record-counters", "5 account-access-import");
        var marker = get(client, TABLES.userAccountDetailsTableName(), AccountAccessImport.MARKER_PK, AccountAccessImport.MARKER_SK);
        assertThat(marker.get("importedAccounts").n()).isEqualTo("4");
        assertThat(marker.get("removedWaiting").n()).isEqualTo("1");

        var accounts = accounts();
        assertThat(accounts.control(strayId)).isNull();
        assertThat(accounts.accountOf(ISSUER, "admin-subject")).isEqualTo(admin);
        assertThat(accounts.accountOf(ISSUER, "google-subject")).isEqualTo(admin);
        var ada = accounts.control(admin);
        assertThat(ada.admitted()).isTrue(); assertThat(ada.author()).isFalse(); assertThat(ada.enabled()).isTrue(); assertThat(ada.active()).isTrue();
        assertThat(ada.verifiedEmail()).isEqualTo("Admin@Example.invalid"); assertThat(ada.displayName()).isEqualTo("Ada Admin");
        assertThat(ada.requestedAt()).isEqualTo(T0.plusSeconds(100)); assertThat(ada.admittedAt()).isEqualTo(T0.plusSeconds(200));
        assertThat(accounts.identities(admin)).extracting(DynamoCloudAccounts.Identity::provider).containsExactly("cognito", "google");
        assertThat(accounts.emailAccounts("admin@example.invalid")).containsExactly(admin);
        assertThat(accounts.usernames(admin)).containsExactly("Admin@Example.invalid", "Google_12345");
        assertThat(get(client, TABLES.userAccountDetailsTableName(), "ACCOUNT#" + admin, "META").get("lastPlanNumber").n()).isEqualTo("3");
        var theOwner = accounts.control(owner);
        assertThat(theOwner.admitted()).isTrue(); assertThat(theOwner.author()).isTrue();
        assertThat(accounts.control(disabled).disabled()).isTrue();
        assertThat(accounts.control(removed)).isNull();
        assertThat(accounts.control(tester)).isNull();
        assertThat(new DynamoAdmissionStore(client, TABLES, clock).waiting()).singleElement().satisfies(w -> assertThat(w.accountId()).isEqualTo(waiting));
        // Roles, invitation and labels as the access services read them.
        var staff = new DynamoStaffAccessRepository(client, TABLES, new ObjectMapper(), clock);
        assertThat(staff.forAccount(admin).roles()).containsExactly("admin");
        assertThat(staff.forAccount(admin).revision()).isEqualTo(2);
        assertThat(staff.pending("reviewer@example.invalid").roles()).containsExactly("reviewer");
        assertThat(staff.owners()).containsExactly(owner);
        var aliases = new DynamoStaffAliasStore(client, TABLES, clock);
        assertThat(aliases.read(admin, "admin")).map(DynamoStaffAliasStore.Assignment::number).contains(2);
        var labels = new DynamoStaffAliasVerifier(client, TABLES).verify();
        assertThat(labels.assignedPerRole()).containsEntry("admin", 2L).containsEntry("learner", 0L);
        assertThat(aliases.assign(waiting, "admin").number()).isEqualTo(3); // the next label continues after the imported ones
        assertThat(get(client, TABLES.userAccountDetailsTableName(), "SEQUENCE#review-record", "COUNTER").get("lastAssigned").n()).isEqualTo("17");
        assertThat(get(client, TABLES.userAccountDetailsTableName(), "SEQUENCE#support-request", "COUNTER").get("lastAssigned").n()).isEqualTo("0");
        // Audit: the admin's two admission events at their original times, the expire of the stray row, the staff event.
        var adminEvents = com.lookahead.domain.cloud.CloudAccountItems.query(client, TABLES.administratorAuditEventsTableName(), "ACCOUNT#" + admin, "ADMISSION#");
        assertThat(adminEvents).extracting(e -> e.get("action").s()).containsExactly("request", "admit");
        assertThat(adminEvents.getLast().get("roles").l()).extracting(software.amazon.awssdk.services.dynamodb.model.AttributeValue::s).containsExactly("admin");
        assertThat(com.lookahead.domain.cloud.CloudAccountItems.query(client, TABLES.administratorAuditEventsTableName(), "ACCOUNT#" + strayId, "ADMISSION#"))
                .extracting(e -> e.get("action").s()).containsExactlyInAnyOrder("request", "expire"); // the fixed clock gives both the same time
        assertThat(com.lookahead.domain.cloud.CloudAccountItems.query(client, TABLES.administratorAuditEventsTableName(), "STAFF#" + assignment, "EVENT#")).hasSize(1);
        // The imported people sign in: the owner with author access, the admin admitted, the waiting one still waiting.
        assertThat(registry.authenticate(signedIn(registry, "owner-subject", "owner@example.invalid", 'o'), proof('o')).cloudAuthor()).isTrue();
        assertThat(registry.admit(identity("admin-subject", "Admin@Example.invalid", "Admin@Example.invalid"), proof('b')).accountId()).isEqualTo(admin);
        assertThatThrownBy(() -> registry.admit(identity("waiting-subject", "waiting@example.invalid", "waiting@example.invalid"), proof('w')))
                .isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("ACCOUNT_ADMISSION_REQUIRED"));
        assertThat(accounts.accountOf(ISSUER, "waiting-subject")).isEqualTo(waiting);
        // A rerun of the step writes nothing new; verify holds.
        new AccountAccessImport().apply(client, TABLES, DynamoMigrationSources.of("local", source));
        assertThat(accounts.identities(admin)).hasSize(2);
        assertThat(adminEvents).hasSameSizeAs(com.lookahead.domain.cloud.CloudAccountItems.query(client, TABLES.administratorAuditEventsTableName(), "ACCOUNT#" + admin, "ADMISSION#"));
        assertThat(runner().verify().verified()).hasSize(5);
    }

    private CognitoIdentity signedIn(DynamoCloudSignInRegistry registry, String subject, String email, char proof) {
        var identity = identity(subject, email, email);
        assertThat(registry.admit(identity, proof(proof)).signInId()).isNotNull();
        return identity;
    }

    @Test void anAdmittedStrangerOnTheStoreStopsTheImportBeforeAnythingIsDeleted() {
        new DynamoMigrationRunner(client, TABLES, new DynamoMigrationCatalog(DomainDynamoCatalog.catalog().steps().subList(0, 4)), clock).migrate("github-4");
        var registry = new DynamoCloudSignInRegistry(client, TABLES, clock);
        var stranger = identity("stranger-subject", "stranger@example.invalid", "stranger@example.invalid");
        assertThatThrownBy(() -> registry.admit(stranger, proof('s'))).isInstanceOf(AccountFailure.class);
        UUID id = accounts().accountOf(ISSUER, "stranger-subject");
        var tx = accounts().transaction(); accounts().admit(tx, accounts().control(id), null, List.of(), NOW); tx.commit();
        assertThatThrownBy(() -> new AccountAccessImport().apply(client, TABLES, DynamoMigrationSources.of("local", source)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("admitted account the import does not know");
        assertThat(accounts().control(id).admitted()).isTrue();
        assertThat(accounts().control(admin)).isNull();
    }

    @Test void withoutASourceALocalStoreImportsNothingAndDevRefuses() {
        new DynamoMigrationRunner(client, TABLES, new DynamoMigrationCatalog(DomainDynamoCatalog.catalog().steps().subList(0, 4)), clock).migrate("github-4");
        var step = new AccountAccessImport();
        assertThatThrownBy(() -> step.apply(client, TABLES, new DynamoMigrationSources("dev", Optional.empty())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LOOKAHEAD_IMPORT_JDBC_URL");
        step.apply(client, TABLES);
        var marker = get(client, TABLES.userAccountDetailsTableName(), AccountAccessImport.MARKER_PK, AccountAccessImport.MARKER_SK);
        assertThat(marker.get("importedAccounts").n()).isEqualTo("0");
        assertThat(accounts().control(admin)).isNull();
        step.verify(client, TABLES);
    }
}
