package com.lookahead.domain;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
    private final JdbcTemplate jdbc;
    private final boolean cloud;
    public DomainDatabaseHealthIndicator(JdbcTemplate jdbc) { this.jdbc = jdbc; this.cloud=false; }
    @org.springframework.beans.factory.annotation.Autowired
    public DomainDatabaseHealthIndicator(JdbcTemplate jdbc,org.springframework.core.env.Environment environment) {
        this.jdbc=jdbc;this.cloud=java.util.Set.of("dev","prod").contains(environment.getProperty("app.deployment-environment",""));
    }
    @Override public Health health() {
        try {
            Boolean ready = jdbc.queryForObject(HEALTH_SQL, Boolean.class);
            if(Boolean.TRUE.equals(ready)&&cloud)ready=jdbc.queryForObject(CLOUD_SQL,Boolean.class);
            return Boolean.TRUE.equals(ready) ? Health.up().build() : Health.down().build();
        } catch (Exception error) { return Health.down().build(); }
    }
}
