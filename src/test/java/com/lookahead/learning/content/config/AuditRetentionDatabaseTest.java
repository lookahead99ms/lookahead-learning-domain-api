package com.lookahead.learning.content.config;

import com.lookahead.domain.DomainApiMigration;
import com.lookahead.domain.testaccounts.AccountDataErasure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DLV921_DATABASE_URL", matches=".+")
class AuditRetentionDatabaseTest {
    String schema;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    static final List<String> AUDITS = List.of("membership_audit", "author_review_events", "staff_access_events", "real_account_maintenance");

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("DLV921_DATABASE_URL");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:4392/lookahead_domain_review");
        String password = Files.readString(Path.of(System.getenv("DLV921_DATABASE_PASSWORD_FILE"))).strip();
        schema = "audit_boundary_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", password));
        admin.execute("CREATE SCHEMA " + schema);
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='lookahead_platform_app') THEN CREATE ROLE lookahead_platform_app NOLOGIN; END IF; END $$");
        source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "postgres", password);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        flyway("14").migrate();
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO lookahead_platform_app");
    }
    Flyway flyway(String version) {
        return DomainApiMigration.configure(Flyway.configure().dataSource(source))
                .defaultSchema(schema).schemas(schema).target(version).load();
    }
    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
    void asApp(Runnable operation) {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE lookahead_platform_app");
            operation.run();
        });
    }
    String guard() {
        return AccountDatabaseConfiguration.CLOUD_AUDIT_GUARD_SQL.replace("public.", schema + ".")
                .replace("n.nspname='public'", "n.nspname='" + schema + "'");
    }
    UUID subject() {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO platform_subjects VALUES (?)", id); return id;
    }
    UUID purchase(UUID account, UUID replaces) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO membership_purchases(id,account_id,plan_id,plan_snapshot,request_fingerprint,idempotency_key,
              price_paid_cents,status,starts_at,ends_at,source,actor_id,provider,replaces_id)
            VALUES (?,?,'learn','{}',?,?,0,'succeeded',now(),now()+interval '1 day','checkout',?,'mock',?)
            """, id, account, "0".repeat(64), id.toString(), account, replaces);
        jdbc.update("INSERT INTO membership_audit(id,actor_id,action,subject_id,detail) VALUES (?,?,'purchase-created',?,'{}')",
                UUID.randomUUID(), account, id.toString());
        return id;
    }
    @Test void emptyUpgradePreservesChecksumsAndDeniesAllAuditMutationEvenWithPurgeContext() {
        var before = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("15").validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'15' AND version IS NOT NULL ORDER BY installed_rank")).isEqualTo(before);
        UUID account = subject(); purchase(account, null);
        jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES (?,'test-audit','created','fixture')", account);
        asApp(() -> jdbc.execute(guard()));
        for (String table : AUDITS) {
            for (String sql : List.of("DELETE FROM " + table, "UPDATE " + table + " SET actor_id=actor_id", "TRUNCATE " + table)) {
                assertThatThrownBy(() -> asApp(() -> {
                    jdbc.query("SELECT set_config('lookahead.test_account_purge',?,true)", rs -> {}, account.toString());
                    jdbc.execute(sql);
                })).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(flyway("15").migrate().migrationsExecuted).isZero();
    }
    @Test void populatedUpgradeAndRestrictedCleanupRetainAuditAndLifetimeRecordsOnRetry() {
        UUID account = subject(), other = subject();
        UUID first = purchase(account, null), second = purchase(account, first), untouched = purchase(other, null);
        jdbc.update("INSERT INTO test_account_registry(account_id,username,initialization,last_changed_by) VALUES (?,'test-retain','created','fixture')", account);
        jdbc.update("INSERT INTO test_account_creations(creator_id,mutation_key,account_id,username,state) VALUES (?,?,?,'test-retain','active')", other, UUID.randomUUID(), account);
        jdbc.update("INSERT INTO membership_payment_attempts(id,purchase_id,result) VALUES (?,?,'success')", UUID.randomUUID(), first);
        jdbc.update("INSERT INTO membership_provider_events VALUES ('mock','event-retain',?,now())", second);
        jdbc.update("INSERT INTO membership_mock_payments VALUES (?, 'pi-retain',0,'USD','succeeded')", first.toString());
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:test')", account);
        var auditBefore = jdbc.queryForList("SELECT * FROM membership_audit ORDER BY id");
        flyway("15").migrate();
        var cleanup = new AccountDataErasure(jdbc);
        // No audit DELETE is attempted: the restricted runtime has no such permission.
        asApp(() -> cleanup.erase(account, false));
        asApp(() -> cleanup.erase(account, false));
        assertThat(jdbc.queryForList("SELECT * FROM membership_audit ORDER BY id")).isEqualTo(auditBefore);
        for (String table : List.of("membership_purchases", "account_grants"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id=?", Integer.class, account)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_payment_attempts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_provider_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_mock_payments", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_purchases WHERE id=?", Integer.class, untouched)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_account_creations WHERE account_id=?", Integer.class, account)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_subjects WHERE id=?", Integer.class, account)).isEqualTo(1);
        // Local code can also complete without the optional fixture grant.
        asApp(() -> cleanup.erase(account, true));
    }
    @Test void crossAccountPurchaseDependencyRollsBackErasureWithoutCascade() {
        UUID account = subject(), other = subject();
        UUID purchase = purchase(account, null); purchase(other, purchase);
        jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,'learn:test')", account);
        flyway("15").migrate();
        assertThatThrownBy(() -> asApp(() -> new AccountDataErasure(jdbc).erase(account, false)))
                .hasMessageContaining("foreign key constraint");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_grants WHERE account_id=?", Integer.class, account)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_audit", Integer.class)).isEqualTo(2);
    }
    @Test void publicColumnAndPrivilegedFunctionBackdoorsFailClosed() {
        flyway("15").migrate();
        jdbc.execute("GRANT DELETE ON membership_audit TO PUBLIC");
        assertThatThrownBy(() -> asApp(() -> jdbc.execute(guard()))).hasMessageContaining("retain audit");
        jdbc.execute("REVOKE DELETE ON membership_audit FROM PUBLIC");
        jdbc.execute("GRANT UPDATE(detail) ON membership_audit TO lookahead_platform_app");
        assertThatThrownBy(() -> asApp(() -> jdbc.execute(guard()))).hasMessageContaining("retain audit");
        jdbc.execute("REVOKE UPDATE(detail) ON membership_audit FROM lookahead_platform_app");
        jdbc.execute("CREATE FUNCTION bypass() RETURNS integer LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'");
        assertThatThrownBy(() -> asApp(() -> jdbc.execute(guard()))).hasMessageContaining("privileged functions");
        jdbc.execute("REVOKE EXECUTE ON FUNCTION bypass() FROM PUBLIC");
        asApp(() -> jdbc.execute(guard()));
    }
    @Test void inheritedAuditGrantRejectsMigrationAndGuard() throws Exception {
        String role = "audit_grant_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE ROLE " + role);
        try {
            jdbc.execute("GRANT DELETE ON membership_audit TO " + role);
            jdbc.execute("GRANT " + role + " TO lookahead_platform_app");
            assertThatThrownBy(() -> flyway("15").migrate()).hasMessageContaining("Runtime audit mutation remains effective");
            jdbc.execute("REVOKE " + role + " FROM lookahead_platform_app");
            flyway("15").migrate();
        } finally {
            jdbc.execute("REVOKE " + role + " FROM lookahead_platform_app");
            jdbc.execute("REVOKE ALL ON membership_audit FROM " + role);
            jdbc.execute("DROP ROLE " + role);
        }
    }
}
