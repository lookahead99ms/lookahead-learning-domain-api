package com.lookahead.learning.content.repository;

import com.lookahead.learning.content.exception.AccountFailure;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-971: support_receipts on the real schema (V1): reserve, accepted, the per-hour limit and the new release that
 * frees an unconfirmed key whose request was neither recorded nor sent.
 */
@EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportReceiptRepositoryDatabaseTest {
    JdbcTemplate jdbc; DriverManagerDataSource source; String schema; boolean createdRuntimeRole;
    SupportReceiptRepository receipts;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("CLOUD_AUTH_TEST_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/lookahead_domain_cloud_auth"))throw new IllegalStateException("Use disposable cloud-auth test database only");
        String password=Files.readString(Path.of(System.getenv("CLOUD_AUTH_TEST_PASSWORD_FILE"))).strip();
        schema="support_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("CREATE SCHEMA "+schema);
        source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(source);
        createdRuntimeRole=jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='lookahead_platform_app'",Integer.class)==0;
        if(createdRuntimeRole)jdbc.execute("CREATE ROLE lookahead_platform_app NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO lookahead_platform_app");
        jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank integer)");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/domain/V1__platform.sql")));
        receipts=new SupportReceiptRepository(jdbc,new DataSourceTransactionManager(source));
    }
    @AfterAll void stop(){if(jdbc!=null){jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(createdRuntimeRole)jdbc.execute("DROP ROLE lookahead_platform_app");}}
    String subject(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES (?)",id);return id.toString();}
    String status(String owner,String key){var rows=jdbc.queryForList("SELECT status FROM support_receipts WHERE account_id=? AND request_key=?",String.class,UUID.fromString(owner),key);return rows.isEmpty()?null:rows.getFirst();}

    @Test void reserveAcceptAndReplay() {
        String owner=subject();
        var first=receipts.reserve(owner,"key-1","a".repeat(64));
        assertThat(first.dispatch()).isTrue();
        assertThat(first.receipt().status()).isEqualTo("unconfirmed");
        assertThat(status(owner,"key-1")).isEqualTo("unconfirmed");
        receipts.accepted(owner,"key-1");
        var replay=receipts.reserve(owner,"key-1","a".repeat(64));
        assertThat(replay.dispatch()).isFalse();
        assertThat(replay.receipt()).isEqualTo(new com.lookahead.learning.content.service.SupportFeedbackService.Receipt(first.receipt().reference(),"accepted"));
        assertThat(catchThrowableOfType(AccountFailure.class,()->receipts.reserve(owner,"key-1","b".repeat(64))).code()).isEqualTo("FEEDBACK_KEY_REUSED");
        assertThat(catchThrowableOfType(AccountFailure.class,()->receipts.reserve(UUID.randomUUID().toString(),"key-1","a".repeat(64))).status()).isEqualTo(401);
    }

    /** DLV-971: release frees an unconfirmed key (a later reserve starts over with a new reference); an accepted one stays. */
    @Test void releaseFreesAnUnconfirmedKeyOnly() {
        String owner=subject();
        var first=receipts.reserve(owner,"key-2","c".repeat(64));
        receipts.release(owner,"key-2");
        assertThat(status(owner,"key-2")).isNull();
        var again=receipts.reserve(owner,"key-2","d".repeat(64));
        assertThat(again.dispatch()).isTrue();
        assertThat(again.receipt().reference()).isNotEqualTo(first.receipt().reference());
        receipts.accepted(owner,"key-2");
        receipts.release(owner,"key-2");
        assertThat(status(owner,"key-2")).isEqualTo("accepted");
        receipts.release(owner,"never-reserved");
    }

    @Test void fiveRequestsAnHourAreTheLimit() {
        String owner=subject();
        for(int i=0;i<5;i++)receipts.reserve(owner,"limit-"+i,"e".repeat(64));
        assertThat(catchThrowableOfType(AccountFailure.class,()->receipts.reserve(owner,"limit-5","e".repeat(64))).code()).isEqualTo("FEEDBACK_RATE_LIMIT");
        receipts.release(owner,"limit-4");
        assertThat(receipts.reserve(owner,"limit-5","e".repeat(64)).dispatch()).isTrue();
    }
}
