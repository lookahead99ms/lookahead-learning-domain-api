package com.lookahead.domain;

import com.lookahead.domain.database.DomainDatabaseConnections;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Fixed, one-shot cloud role provisioning; no Spring server or AWS API calls. */
public final class DomainApiBootstrap {
    private static final String MASTER = "lookahead_bootstrap";
    private static final String APP = "lookahead_platform_app";
    private static final String MIGRATOR = "lookahead_platform_migrator";
    private DomainApiBootstrap() { }

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalStateException("Arguments are forbidden");
            var environment = System.getenv();
            validateConfiguration(environment);
            bootstrap(required(environment, "SPRING_FLYWAY_URL"),
                    required(environment, "LOOKAHEAD_BOOTSTRAP_PASSWORD"),
                    required(environment, "LOOKAHEAD_APP_PASSWORD"),
                    required(environment, "LOOKAHEAD_MIGRATOR_PASSWORD"));
            System.out.println("Domain database bootstrap verified; migrations have not run");
        } catch (Exception failure) {
            // JDBC exceptions can contain SQL, endpoints and password verifiers.
            System.err.println("Domain database bootstrap failed; activation remains blocked");
            System.exit(1);
        }
    }

    public static void validateConfiguration(Map<String, String> environment) {
        if (!Set.of("dev", "prod").contains(required(environment, "LOOKAHEAD_ENVIRONMENT")))
            throw new IllegalStateException("Bootstrap requires explicit dev or prod");
        String url = required(environment, "SPRING_FLYWAY_URL");
        DomainDatabaseConnections.validateTarget(required(environment, "LOOKAHEAD_ENVIRONMENT"),
                required(environment, "AWS_REGION"), url);
        var uri = java.net.URI.create(url.substring(5));
        if (!uri.getHost().matches("[a-z][a-z0-9-]*\\.[a-z0-9]+\\." +
                java.util.regex.Pattern.quote(required(environment, "AWS_REGION")) + "\\.rds\\.amazonaws\\.com")
                || uri.getPort() != 5432)
            throw new IllegalStateException("Exact RDS instance endpoint on port 5432 required");
        for (String name : Set.of("LOOKAHEAD_BOOTSTRAP_PASSWORD", "LOOKAHEAD_APP_PASSWORD", "LOOKAHEAD_MIGRATOR_PASSWORD"))
            validatePassword(required(environment, name));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 16 || password.length() > 128
                || password.chars().anyMatch(character -> character < 33 || character > 126))
            throw new IllegalStateException("Secrets require 16 to 128 printable ASCII characters without whitespace");
    }

    static String scramVerifier(String password, byte[] salt) throws Exception {
        validatePassword(password);
        if (salt.length != 16) throw new IllegalArgumentException("SCRAM salt must be 16 bytes");
        var specification = new PBEKeySpec(password.toCharArray(), salt, 4096, 256);
        byte[] salted;
        try { salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded(); }
        finally { specification.clearPassword(); }
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salted, "HmacSHA256"));
        byte[] stored = MessageDigest.getInstance("SHA-256").digest(mac.doFinal("Client Key".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        byte[] server = mac.doFinal("Server Key".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var encode = Base64.getEncoder();
        java.util.Arrays.fill(salted, (byte) 0);
        return "SCRAM-SHA-256$4096:" + encode.encodeToString(salt) + "$" + encode.encodeToString(stored) + ":" + encode.encodeToString(server);
    }

    private static String verifier(String password) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return scramVerifier(password, salt);
    }

    public static void bootstrap(String url, String masterPassword, String appPassword, String migratorPassword) throws Exception {
        for (String password : new String[]{masterPassword, appPassword, migratorPassword}) validatePassword(password);
        try (var connection = DomainDatabaseConnections.boundedDataSource(url, MASTER, masterPassword).getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "SET LOCAL lock_timeout='3s'");
                execute(connection, "SET LOCAL statement_timeout='4s'");
                execute(connection, "SELECT pg_advisory_xact_lock(810,17)");
                boolean existing = inspect(connection);
                if (existing) {
                    authenticate(url, APP, appPassword);
                    authenticate(url, MIGRATOR, migratorPassword);
                    verifyRuntime(url, appPassword);
                } else {
                    execute(connection, "CREATE ROLE lookahead_platform_owner NOLOGIN NOINHERIT");
                    execute(connection, "CREATE ROLE lookahead_platform_app LOGIN NOINHERIT PASSWORD '" + verifier(appPassword) + "'");
                    execute(connection, "CREATE ROLE lookahead_platform_migrator LOGIN NOINHERIT PASSWORD '" + verifier(migratorPassword) + "'");
                    execute(connection, "GRANT lookahead_platform_owner,lookahead_platform_migrator TO lookahead_bootstrap WITH INHERIT TRUE,SET TRUE");
                    execute(connection, "ALTER DATABASE lookahead_platform OWNER TO lookahead_platform_owner");
                    execute(connection, "REVOKE ALL ON DATABASE lookahead_platform FROM PUBLIC");
                    execute(connection, "GRANT CONNECT ON DATABASE lookahead_platform TO lookahead_bootstrap,lookahead_platform_app,lookahead_platform_migrator");
                    execute(connection, "GRANT CREATE ON DATABASE lookahead_platform TO lookahead_platform_migrator");
                    execute(connection, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
                    execute(connection, "ALTER SCHEMA public OWNER TO lookahead_platform_migrator");
                    execute(connection, "REVOKE CREATE ON DATABASE lookahead_platform FROM lookahead_platform_migrator");
                    execute(connection, "GRANT USAGE ON SCHEMA public TO lookahead_platform_app");
                    execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE lookahead_platform_migrator REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
                    execute(connection, "REVOKE lookahead_platform_owner,lookahead_platform_migrator FROM lookahead_bootstrap GRANTED BY lookahead_bootstrap");
                    if (!inspect(connection)) throw new IllegalStateException("Bootstrap state missing");
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
        authenticate(url, APP, appPassword);
        authenticate(url, MIGRATOR, migratorPassword);
        verifyRuntime(url, appPassword);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(4); statement.execute(sql); }
    }

    private static boolean inspect(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(4);
            try (var result = statement.executeQuery("""
                SELECT current_database()='lookahead_platform' AND current_user='lookahead_bootstrap'
                 AND session_user=current_user AND current_setting('server_version_num')::int BETWEEN 170000 AND 179999
                 AND EXISTS(SELECT FROM pg_roles WHERE rolname=current_user AND NOT rolsuper AND rolcreatedb AND rolcreaterole AND NOT rolreplication AND NOT rolbypassrls),
                 (SELECT count(*) FROM pg_roles WHERE rolname IN ('lookahead_platform_owner','lookahead_platform_app','lookahead_platform_migrator')),
                 NOT EXISTS(SELECT FROM pg_roles r WHERE rolname IN ('lookahead_platform_owner','lookahead_platform_app','lookahead_platform_migrator') AND
                   (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit OR rolcanlogin<>(rolname<>'lookahead_platform_owner') OR EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid))),
                 pg_get_userbyid((SELECT datdba FROM pg_database WHERE datname=current_database())),
                 (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='public')
                """)) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException("Restricted PostgreSQL 17 master required");
                int count = result.getInt(2);
                if ((count != 0 && count != 3) || !result.getBoolean(3)
                    || !result.getString(4).equals(count == 3 ? "lookahead_platform_owner" : MASTER)
                    || !(count == 3 ? Set.of(MIGRATOR) : Set.of(MASTER, "pg_database_owner")).contains(result.getString(5)))
                    throw new IllegalStateException("Incompatible existing database state");
                if (count == 3) {
                    try (var policy = connection.createStatement()) {
                        policy.setQueryTimeout(4);
                        try (var state = policy.executeQuery("""
                            SELECT NOT has_database_privilege('lookahead_platform_migrator',current_database(),'CREATE')
                             AND NOT has_database_privilege('lookahead_platform_migrator',current_database(),'TEMP')
                             AND EXISTS(SELECT FROM pg_default_acl d WHERE defaclrole=(SELECT oid FROM pg_roles WHERE rolname='lookahead_platform_migrator')
                              AND defaclnamespace=0 AND defaclobjtype='f' AND NOT EXISTS(SELECT FROM aclexplode(d.defaclacl) WHERE grantee=0 AND privilege_type='EXECUTE'))
                            """)) {
                            if (!state.next() || !state.getBoolean(1)) throw new IllegalStateException("Migration or default privilege boundary failed");
                        }
                    }
                }
                return count == 3;
            }
        }
    }

    private static void authenticate(String url, String user, String password) throws SQLException {
        try (var connection = DomainDatabaseConnections.boundedDataSource(url, user, password).getConnection();
             var statement = connection.prepareStatement("SELECT current_user=? AND session_user=current_user AND current_database()='lookahead_platform'")) {
            statement.setQueryTimeout(4); statement.setString(1, user);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException("Unexpected authenticated identity");
            }
        }
    }

    private static void verifyRuntime(String url, String password) throws SQLException {
        try (var connection = DomainDatabaseConnections.boundedDataSource(url, APP, password).getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(4);
            try (var result = statement.executeQuery("""
                SELECT NOT has_database_privilege(current_user,current_database(),'CREATE')
                 AND NOT has_database_privilege(current_user,current_database(),'TEMP')
                 AND has_schema_privilege(current_user,'public','USAGE')
                 AND NOT EXISTS(SELECT FROM pg_namespace WHERE has_schema_privilege(current_user,oid,'CREATE'))
                 AND NOT EXISTS(SELECT FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user))
                """)) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException("Runtime privilege boundary failed");
            }
        }
    }
}
