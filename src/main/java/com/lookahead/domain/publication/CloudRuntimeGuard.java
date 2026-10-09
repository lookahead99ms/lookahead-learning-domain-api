package com.lookahead.domain.publication;

import com.lookahead.domain.database.DomainDatabaseConnections;
import java.util.List;
import org.springframework.core.env.Environment;

/** Cloud startup requires injected values and verified RDS TLS; no Secrets Manager calls. */
public final class CloudRuntimeGuard {
    private CloudRuntimeGuard() {}
    public static void validate(Environment environment) {
        for (String name : List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_S3"))
            if (!environment.getProperty(name, "").isBlank()) throw new IllegalStateException("Cloud application requires ECS task credentials and AWS service endpoints");
        String password;
        try { password = environment.getProperty("spring.datasource.password", ""); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Cloud database requires an injected secret value"); }
        if (password.length() < 16 || password.length() > 4096 || password.startsWith("arn:") || password.contains("${")
                || password.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalStateException("Cloud database requires an injected secret value");
        if (!"127.0.0.1".equals(environment.getProperty("server.address", "")))
            throw new IllegalStateException("Cloud Domain HTTP must bind to 127.0.0.1 behind Service Connect TLS");
        DomainDatabaseConnections.validateTarget(environment.getRequiredProperty("app.deployment-environment"),
                environment.getRequiredProperty("app.publication.region"), environment.getRequiredProperty("spring.datasource.url"));
    }
}
