package com.lookahead.learning.content.config;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Configuration
@Profile("accounts")
@EnableTransactionManagement
public class AccountDatabaseConfiguration {
    /** Hikari runs this on each new physical connection before lending it to application code. */
    static final String CONNECTION_GUARD_SQL = """
        DO $lookahead_runtime_guard$
        BEGIN
          IF current_user <> '%s' OR session_user <> '%s'
            OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname=current_user
              AND rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname<>current_user
              AND pg_catalog.pg_has_role(current_user,oid,'MEMBER'))
            OR pg_catalog.has_database_privilege(current_user,pg_catalog.current_database(),'CREATE')
            OR pg_catalog.has_database_privilege(current_user,pg_catalog.current_database(),'TEMPORARY')
            OR NOT pg_catalog.has_schema_privilege(current_user,'public','USAGE')
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
              WHERE pg_catalog.has_schema_privilege(current_user,oid,'CREATE'))
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_roles r ON r.oid=c.relowner WHERE r.rolname=current_user)
          THEN
            RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Runtime database role violates application isolation';
          END IF;
          PERFORM pg_catalog.set_config('search_path','pg_catalog,public',false);
        END;
        $lookahead_runtime_guard$;
        """.formatted(LegacyStorageNames.RUNTIME_ROLE, LegacyStorageNames.RUNTIME_ROLE);

    @Bean
    HikariDataSource accountDataSource(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username:#{T(com.lookahead.domain.compatibility.LegacyStorageNames).RUNTIME_ROLE}}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:6}") int maximumPoolSize,
            @Value("${spring.datasource.hikari.connection-timeout:3000}") long connectionTimeout,
            @Value("${spring.datasource.hikari.validation-timeout:2000}") long validationTimeout) {
        if (!LegacyStorageNames.RUNTIME_ROLE.equals(username) || password == null || password.isBlank())
            throw new IllegalStateException("Accounts require the dedicated runtime database role");
        validateJdbcUrl(url);
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        source.setConnectionInitSql(CONNECTION_GUARD_SQL);
        // Pool acquisition does not bound an already borrowed connection's socket read.
        source.addDataSourceProperty("connectTimeout", "3");
        source.addDataSourceProperty("socketTimeout", "5");
        source.addDataSourceProperty("cancelSignalTimeout", "2");
        source.setMaximumPoolSize(maximumPoolSize);
        source.setConnectionTimeout(connectionTimeout);
        source.setValidationTimeout(validationTimeout);
        return source;
    }

    /** URL properties take precedence over pgJDBC's separate credentials and timeout properties. */
    public static void validateJdbcUrl(String url) {
        try {
            if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI parsed = URI.create(url.substring("jdbc:".length()));
            if (parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getRawFragment() != null
                    || parsed.getPort() == 0 || parsed.getPort() > 65535
                    || !parsed.getRawPath().matches("/[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"))
                throw new IllegalArgumentException();
            String query = parsed.getRawQuery();
            if (query == null) return;
            Set<String> names = new HashSet<>();
            for (String parameter : query.split("&", -1)) {
                String[] parts = parameter.split("=", -1);
                if (parts.length != 2 || parts[1].isEmpty()) throw new IllegalArgumentException();
                String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                if (!parts[0].equals(name) || !names.add(name)) throw new IllegalArgumentException();
                if (name.equals("sslmode")) {
                    if (!Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full").contains(value))
                        throw new IllegalArgumentException();
                } else if (name.equals("sslrootcert")) {
                    if (!value.startsWith("/") || value.codePoints().anyMatch(Character::isISOControl))
                        throw new IllegalArgumentException();
                } else throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            // Do not retain an exception or URL that could contain injected credentials.
            throw new IllegalStateException("Use an explicit single-host PostgreSQL JDBC URL; only sslmode and an absolute sslrootcert path are supported");
        }
    }

    @Bean JdbcTemplate accountJdbcTemplate(DataSource source) {
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(5);
        return template;
    }
    @Bean JdbcTransactionManager accountTransactionManager(DataSource source) { return new JdbcTransactionManager(source); }
}
