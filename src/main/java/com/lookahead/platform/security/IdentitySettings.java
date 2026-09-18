package com.lookahead.platform.security;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Deployment-owned destinations. No browser-controlled issuer, host, or credentials. */
public record IdentitySettings(String issuer, String upstream, String gatewayClientId, String verifierSecret,
                               Duration connectTimeout, Duration readTimeout) {
    public static IdentitySettings from(Environment environment) {
        String mode = required(environment, "app.deployment-environment");
        if (!Set.of("local", "dev", "prod").contains(mode)) throw new IllegalStateException("Choose local, dev, or prod deployment environment");
        if (environment.acceptsProfiles(Profiles.of("gateway", "oauth-server"))) throw new IllegalStateException("Platform cannot activate gateway or authorization-server roles");
        boolean local = "local".equals(mode);
        if (local && environment.acceptsProfiles(Profiles.of("dev", "prod", "production"))) throw new IllegalStateException("Local mode cannot activate DEV/PROD profiles");
        if (!local && environment.acceptsProfiles(Profiles.of("local", "local-test"))) throw new IllegalStateException("Local profiles require local mode");
        String issuer = required(environment, "app.identity.issuer");
        String upstream = required(environment, "app.identity.upstream");
        validateOrigin(issuer, local, false);
        validateOrigin(upstream, local, true);
        String client = required(environment, "app.identity.gateway-client-id");
        if (!client.matches("[a-z][a-z0-9-]{2,79}")) throw new IllegalStateException("Invalid expected gateway client ID");
        String secret = required(environment, "app.identity.verifier-secret");
        if (secret.length() < 32 || secret.length() > 4096 || secret.codePoints().anyMatch(Character::isISOControl)) throw new IllegalStateException("Identity verifier secret must contain 32 to 4096 non-control characters");
        return new IdentitySettings(issuer, upstream, client, secret, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
    private static void validateOrigin(String value, boolean local, boolean internal) {
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException error) { throw new IllegalStateException("Identity URL must be an explicit origin"); }
        String host = uri.getHost();
        boolean localHost = host != null && (Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host)
                || internal && Set.of("identity", "identity-api", "lookahead-identity").contains(host));
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().isEmpty() || !("https".equals(uri.getScheme()) || local && localHost && "http".equals(uri.getScheme())))
            throw new IllegalStateException("Identity origins require HTTPS except explicit local loopback/internal service origins");
    }
    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    @Override public String toString() { return "IdentitySettings[redacted]"; }
}
