package com.lookahead.domain;

import com.lookahead.domain.HealthReport.Diagnosis;
import com.lookahead.domain.compatibility.LegacyStorageNames;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Component("domainDatabase")
public class DomainDatabaseHealthIndicator implements HealthIndicator {
    static final String HEALTH_SQL = """
        SELECT current_user='%s' AND session_user='%s'
          AND EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname=current_user
            AND rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
          AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname<>current_user
            AND pg_catalog.pg_has_role(current_user,oid,'MEMBER'))
          AND NOT pg_catalog.has_database_privilege(current_user,pg_catalog.current_database(),'CREATE')
          AND NOT pg_catalog.has_database_privilege(current_user,pg_catalog.current_database(),'TEMPORARY')
          AND pg_catalog.has_schema_privilege(current_user,'public','USAGE')
          AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
            WHERE pg_catalog.has_schema_privilege(current_user,oid,'CREATE'))
          AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_roles r ON r.oid=c.relowner WHERE r.rolname=current_user)
          AND EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='2' AND success)
          AND pg_catalog.has_table_privilege(current_user,'public.author_review_events','SELECT')
          AND pg_catalog.has_table_privilege(current_user,'public.author_review_events','INSERT')
          AND NOT pg_catalog.has_table_privilege(current_user,'public.author_review_events','UPDATE')
          AND NOT pg_catalog.has_table_privilege(current_user,'public.author_review_events','DELETE')
          AND NOT pg_catalog.has_table_privilege(current_user,'public.author_review_events','TRUNCATE')
          AND NOT EXISTS (
            SELECT 1 FROM unnest(ARRAY['public.%s','public.account_grants','public.plans',
              'public.plan_versions','public.plan_activity','public.mutation_receipts','public.support_receipts']) AS t(name)
            CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) AS p(privilege)
            WHERE NOT pg_catalog.has_table_privilege(current_user,t.name,p.privilege))
        """.formatted(LegacyStorageNames.RUNTIME_ROLE, LegacyStorageNames.RUNTIME_ROLE,
                LegacyStorageNames.SUBJECTS_TABLE);
    /**
     * DEV/PROD: the cloud sign-in schema and exactly the runtime role's grants on it. Since V17 (DLV-969) the role
     * creates waiting rows at first contact, admits them for the owner and deletes expired waiting rows, so it holds
     * INSERT and DELETE on cloud_accounts and UPDATE on the admission columns, and records admission events it can
     * never change. Identity columns and the operator-only enabled and author_access flags stay out of reach.
     * Since V18 (DLV-969, run before this release) the role also keeps the owner's Disable/Delete states and the
     * permanent role labels, which it may add but never change or remove.
     * Since V19 (DLV-969 S36, run before this release) it records, links and refreshes the provider identities of an
     * account (cloud_identities) and removes those of an expired waiting account.
     * Since V20 (DLV-969 S36, run before this release) it keeps the single-use merge links (cloud_merge_tokens).
     */
    public static final String CLOUD_SQL = """
        SELECT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='4' AND success)
          AND EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='17' AND success)
          AND EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='18' AND success)
          AND EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='19' AND success)
          AND EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='20' AND success)
          AND has_table_privilege(current_user,'public.cloud_accounts','SELECT')
          AND has_table_privilege(current_user,'public.cloud_accounts','INSERT')
          AND has_table_privilege(current_user,'public.cloud_accounts','DELETE')
          AND NOT has_table_privilege(current_user,'public.cloud_accounts','UPDATE')
          AND NOT has_table_privilege(current_user,'public.cloud_accounts','TRUNCATE')
          AND has_column_privilege(current_user,'public.cloud_accounts','display_name','UPDATE')
          AND has_column_privilege(current_user,'public.cloud_accounts','credential_change_pending','UPDATE')
          AND has_column_privilege(current_user,'public.cloud_accounts','admitted','UPDATE')
          AND has_column_privilege(current_user,'public.cloud_accounts','admitted_at','UPDATE')
          AND has_column_privilege(current_user,'public.cloud_accounts','admitted_by','UPDATE')
          AND has_column_privilege(current_user,'public.cloud_accounts','requested_at','UPDATE')
          AND has_column_privilege(current_user,'public.cloud_accounts','verified_email','UPDATE')
          AND NOT has_column_privilege(current_user,'public.cloud_accounts','enabled','UPDATE')
          AND NOT has_column_privilege(current_user,'public.cloud_accounts','author_access','UPDATE')
          AND NOT has_column_privilege(current_user,'public.cloud_accounts','issuer','UPDATE')
          AND NOT has_column_privilege(current_user,'public.cloud_accounts','subject','UPDATE')
          AND NOT has_column_privilege(current_user,'public.cloud_accounts','account_id','UPDATE')
          AND has_table_privilege(current_user,'public.cloud_admission_events','SELECT')
          AND has_table_privilege(current_user,'public.cloud_admission_events','INSERT')
          AND NOT has_table_privilege(current_user,'public.cloud_admission_events','UPDATE')
          AND NOT has_table_privilege(current_user,'public.cloud_admission_events','DELETE')
          AND NOT has_table_privilege(current_user,'public.cloud_admission_events','TRUNCATE')
          AND has_table_privilege(current_user,'public.staff_aliases','SELECT')
          AND has_table_privilege(current_user,'public.staff_aliases','INSERT')
          AND NOT has_table_privilege(current_user,'public.staff_aliases','UPDATE')
          AND NOT has_table_privilege(current_user,'public.staff_aliases','DELETE')
          AND NOT has_table_privilege(current_user,'public.staff_aliases','TRUNCATE')
          AND NOT has_table_privilege(current_user,'public.cloud_account_states','TRUNCATE')
          AND NOT has_table_privilege(current_user,'public.cloud_identities','TRUNCATE')
          AND NOT has_table_privilege(current_user,'public.cloud_merge_tokens','TRUNCATE')
          AND NOT EXISTS(SELECT 1 FROM unnest(ARRAY['public.cloud_sign_ins','public.cloud_sign_in_challenges','public.cloud_account_states','public.cloud_identities','public.cloud_merge_tokens']) t(name)
            CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) p(privilege)
            WHERE NOT has_table_privilege(current_user,t.name,p.privilege))
        """;
    /** Migrations each check needs; the diagnosis names the ones missing. */
    static final List<String> BASE_MIGRATIONS = List.of("2"), CLOUD_MIGRATIONS = List.of("4", "17", "18", "19", "20");
    static final List<String> BASE_TABLES = List.of("public.author_review_events", "public." + LegacyStorageNames.SUBJECTS_TABLE,
            "public.account_grants", "public.plans", "public.plan_versions", "public.plan_activity", "public.mutation_receipts",
            "public.support_receipts");
    static final List<String> CLOUD_TABLES = List.of("public.cloud_accounts", "public.cloud_admission_events", "public.staff_aliases",
            "public.cloud_account_states", "public.cloud_sign_ins", "public.cloud_sign_in_challenges", "public.cloud_identities",
            "public.cloud_merge_tokens");
    /** While still DOWN, the reason is written again this often, so every failed deploy attempt shows it. */
    static final Duration REPEAT = HealthReport.REPEAT;

    private final JdbcTemplate jdbc;
    private final boolean cloud;
    private final HealthReport report;

    public DomainDatabaseHealthIndicator(JdbcTemplate jdbc) { this(jdbc, false, Clock.systemUTC()); }
    @org.springframework.beans.factory.annotation.Autowired
    public DomainDatabaseHealthIndicator(JdbcTemplate jdbc,org.springframework.core.env.Environment environment) {
        this(jdbc, java.util.Set.of("dev","prod").contains(environment.getProperty("app.deployment-environment","")), Clock.systemUTC());
    }
    DomainDatabaseHealthIndicator(JdbcTemplate jdbc, boolean cloud, Clock clock) {
        this.jdbc = jdbc; this.cloud = cloud;
        this.report = new HealthReport("database", "the database checks pass; this task can take traffic", clock);
    }

    /** The answer carries no details (callers learn only UP or DOWN); the log says why, without connection details. */
    @Override public Health health() {
        Diagnosis down;
        try {
            Boolean ready = jdbc.queryForObject(HEALTH_SQL, Boolean.class);
            down = Boolean.TRUE.equals(ready) ? null : diagnose(BASE_MIGRATIONS, BASE_TABLES);
            if (down == null && cloud)
                down = Boolean.TRUE.equals(jdbc.queryForObject(CLOUD_SQL, Boolean.class)) ? null : diagnose(CLOUD_MIGRATIONS, CLOUD_TABLES);
        } catch (Exception error) {
            down = new Diagnosis("DATABASE_UNAVAILABLE", "the database check could not run: " + Events.types(error) + sqlState(error));
        }
        report.report(down);
        return down == null ? Health.up().build() : Health.down().build();
    }

    /**
     * DLV-969 (owner, 2026-11-04): a DOWN readiness used to leave nothing in the log, so a release deployed before its
     * migrations failed its health checks silently. Missing migrations come first (the usual cause), then missing
     * tables, else the role or its grants differ from what this release checks for.
     */
    Diagnosis diagnose(List<String> migrations, List<String> tables) {
        try {
            List<String> missing = jdbc.queryForList("SELECT v FROM unnest(ARRAY[" + quoted(migrations) + "]) v"
                    + " WHERE NOT EXISTS (SELECT 1 FROM public.flyway_schema_history h WHERE h.version=v AND h.success) ORDER BY v::int", String.class);
            if (!missing.isEmpty())
                return new Diagnosis("MIGRATIONS_MISSING", "the database is missing migration(s) " + String.join(", ", missing)
                        + "; run Flyway Utils migrate and verify before deploying this release");
            List<String> absent = jdbc.queryForList("SELECT t FROM unnest(ARRAY[" + quoted(tables) + "]) t WHERE to_regclass(t) IS NULL", String.class);
            if (!absent.isEmpty())
                return new Diagnosis("TABLES_MISSING", "the database is missing table(s) " + String.join(", ", absent));
            return new Diagnosis("GRANTS_DIFFER", "every migration and table is there, but the runtime role, its ownership or its"
                    + " table privileges differ from what this release checks for (DomainDatabaseHealthIndicator)");
        } catch (Exception error) {
            return new Diagnosis("DATABASE_CHECK_FAILED", "the check failed and its diagnosis could not run: " + Events.types(error) + sqlState(error));
        }
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'" + value + "'").collect(java.util.stream.Collectors.joining(","));
    }

    private static String sqlState(Throwable error) {
        for (Throwable at = error; at != null; at = at.getCause())
            if (at instanceof java.sql.SQLException sql && sql.getSQLState() != null) return " (SQL state " + sql.getSQLState() + ")";
        return "";
    }
}
