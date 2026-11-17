package com.lookahead.domain.publication;

import com.lookahead.domain.StartupChecks;
import java.util.List;
import org.springframework.core.env.Environment;

/**
 * Cloud startup requires the task role, the default AWS endpoints and the application mTLS settings; no Secrets Manager
 * calls and, since the PostgreSQL retirement (DLV-970, 2026-11-17), no database setting at all. DLV-969 (owner,
 * 2026-11-04): each refusal first names the setting and what is wrong with it ({@link StartupChecks}), never its value.
 */
public final class CloudRuntimeGuard {
    private CloudRuntimeGuard() {}
    public static void validate(Environment environment) {
        for (String name : List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_S3"))
            if (!environment.getProperty(name, "").isBlank())
                throw StartupChecks.refuse(name, "STATIC_AWS_SETTING", "is set; the cloud app uses only the ECS task role and the default AWS endpoints, so remove it from the task definition",
                        "Cloud application requires ECS task credentials and AWS service endpoints");
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("lower-cost-dev"))) {
            String setting = !"dev".equals(environment.getProperty("app.deployment-environment")) ? "app.deployment-environment (LOOKAHEAD_ENVIRONMENT) must be dev"
                    : !environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "server.ssl.enabled must be true"
                    : !"need".equals(environment.getProperty("server.ssl.client-auth")) ? "server.ssl.client-auth must be need"
                    : !"serving".equals(environment.getProperty("server.ssl.bundle")) ? "server.ssl.bundle must be serving" : null;
            if (setting != null) throw StartupChecks.refuse(setting, "MTLS_REQUIRED", "for the lower-cost-dev profile (application mTLS)",
                    "Lower-cost DEV requires application mTLS");
        } else if (!"127.0.0.1".equals(environment.getProperty("server.address", "")))
            throw StartupChecks.refuse("server.address (LOOKAHEAD_BIND_ADDRESS)", "BIND_ADDRESS_INVALID",
                    "must be 127.0.0.1: cloud Domain HTTP is reached only through Service Connect TLS",
                    "Cloud Domain HTTP must bind to 127.0.0.1 behind Service Connect TLS");
    }
}
