package com.lookahead.domain.review.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-971 on the disposable cloud-auth database, the full schema (V1-V22): the two record sequences and the
 * append-only review_status_events table with the membership-audit grant pattern (the runtime role appends and reads,
 * never changes or removes; the trigger refuses every role).
 */
@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewRecordsDatabaseTest {
    JdbcTemplate jdbc; String schema;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Use disposable cloud-auth test database only");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        schema="records_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("CREATE SCHEMA "+schema);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password));
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO lookahead_platform_app");
        jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer)");
        for(String file:List.of("V1__platform.sql","V2__author_review_events.sql","V3__plan_names.sql","V4__cloud_identity_and_sign_ins.sql","V6__staff_access.sql",
                "V7__cloud_account_preferences.sql","V8__memberships.sql","V9__plan_lifecycle.sql","V10__test_account_registry.sql","V11__free_plan_allowances.sql",
                "V12__created_test_accounts.sql","V13__real_account_maintenance.sql","V14__managed_test_purchase_purge_grants.sql","V15__retain_runtime_audit.sql",
                "V17__dev_access_admission.sql","V18__access_removal_and_staff_aliases.sql","V19__cloud_identities.sql","V20__cloud_merge_tokens.sql",
                "V21__learner_test_accounts_only.sql","V22__review_records_and_status_events.sql"))
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain",file)));
    }
    @AfterAll void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}

    @Test void sequencesNumberEachKindFromOneAndTheRuntimeRoleMayUseThem() {
        var numbers=new ReviewRecordNumbers.Jdbc(jdbc);
        assertThat(numbers.next("review")).isEqualTo(1);
        assertThat(numbers.next("review")).isEqualTo(2);
        assertThat(numbers.next("request")).isEqualTo(1);
        var store=new RecordStore(new FileSystemPlatformReviewObjects(Path.of(System.getProperty("java.io.tmpdir")).resolve(schema)),"request",new tools.jackson.databind.json.JsonMapper(),numbers);
        assertThat(store.nextId()).isEqualTo("SUP-000002");
        for(String sequence:List.of("review_record_seq","support_request_seq"))
            assertThat(jdbc.queryForObject("SELECT has_sequence_privilege('lookahead_platform_app','"+sequence+"','USAGE')",Boolean.class)).as(sequence).isTrue();
    }

    @Test void statusEventsAreAppendOnlyForTheRuntimeRoleAndImmutableForEveryone() {
        var history=new ReviewStatusHistory.Jdbc(jdbc);
        UUID actor=UUID.randomUUID();
        history.record("REV-000001","review",actor,"new","accepted",true);
        history.record("SUP-000001","request",null,"new","closed",true);
        history.record("DLV-42","ticket",actor,null,"backlog",false);
        assertThat(jdbc.queryForList("SELECT record_id||'|'||kind||'|'||coalesce(from_status,'-')||'|'||to_status||'|'||note_set FROM review_status_events ORDER BY recorded_at",String.class))
                .containsExactly("REV-000001|review|new|accepted|true","SUP-000001|request|new|closed|true","DLV-42|ticket|-|backlog|false");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM review_status_events WHERE record_id='SUP-000001'",UUID.class)).isNull();
        // The membership-audit grant pattern: the runtime role appends and reads, never changes or removes.
        for(String privilege:List.of("INSERT","SELECT"))
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','review_status_events','"+privilege+"')",Boolean.class)).as(privilege).isTrue();
        for(String privilege:List.of("UPDATE","DELETE","TRUNCATE"))
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('lookahead_platform_app','review_status_events','"+privilege+"')",Boolean.class)).as(privilege).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_any_column_privilege('lookahead_platform_app','review_status_events','UPDATE')",Boolean.class)).isFalse();
        assertThatThrownBy(()->jdbc.update("INSERT INTO review_status_events(id,record_id,kind,to_status) VALUES (?,?,?,?)",UUID.randomUUID(),"X","nowhere","x")).hasMessageContaining("check constraint");
        // The owner of the table cannot change history either: the immutability trigger refuses it.
        assertThatThrownBy(()->jdbc.update("UPDATE review_status_events SET to_status='done'")).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("DELETE FROM review_status_events")).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_status_events",Integer.class)).isEqualTo(3);
    }

    /** A history failure is logged, not thrown: the status change it describes is already saved. */
    @Test void aHistoryFailureIsLoggedAndSwallowed() {
        var history=new ReviewStatusHistory.Jdbc(jdbc);
        try (var log=com.lookahead.domain.LogCapture.start()) {
            assertThatCode(()->history.record("REV-000009","mystery",null,null,"x",false)).doesNotThrowAnyException();
            var line=log.only("platform-review.history");
            assertThat(com.lookahead.domain.LogCapture.keys(line)).containsEntry("reason","HISTORY_NOT_WRITTEN").containsEntry("target.id","REV-000009").containsKey("db.sqlState");
            assertThat(line.getFormattedMessage()).startsWith("Status history for REV-000009 was not written (").endsWith("); the change itself is saved");
        }
    }
}
