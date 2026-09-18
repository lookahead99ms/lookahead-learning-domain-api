package com.lookahead.platform;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("platformDatabase")
public class PlatformDatabaseHealthIndicator implements HealthIndicator {
    static final String HEALTH_SQL = """
        SELECT current_user='lookahead_platform_app' AND session_user='lookahead_platform_app'
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
          AND EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='1' AND success)
          AND NOT EXISTS (
            SELECT 1 FROM unnest(ARRAY['public.platform_subjects','public.account_grants','public.plans',
              'public.plan_versions','public.plan_activity','public.mutation_receipts','public.support_receipts']) AS t(name)
            CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) AS p(privilege)
            WHERE NOT pg_catalog.has_table_privilege(current_user,t.name,p.privilege))
        """;
    private final JdbcTemplate jdbc;
    public PlatformDatabaseHealthIndicator(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Health health() {
        try {
            Boolean ready = jdbc.queryForObject(HEALTH_SQL, Boolean.class);
            return Boolean.TRUE.equals(ready) ? Health.up().build() : Health.down().build();
        } catch (Exception error) { return Health.down().build(); }
    }
}
