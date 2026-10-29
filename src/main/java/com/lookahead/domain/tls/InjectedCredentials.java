package com.lookahead.domain.tls;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/** Runs before context refresh, SSL bundle construction and any listening server. */
public final class InjectedCredentials implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        if (!environment.acceptsProfiles(Profiles.of("lower-cost-dev"))) return;
        try {
        var inputs = new LinkedHashMap<String, String>();
        for (String name : List.of("LOOKAHEAD_TLS_SCHEMA", "LOOKAHEAD_TLS_SERVER_CERTIFICATE", "LOOKAHEAD_TLS_SERVER_KEY",
                "LOOKAHEAD_TLS_TRUST", "LOOKAHEAD_TLS_SERVER_NAME", "LOOKAHEAD_TLS_CLIENT_CERTIFICATE", "LOOKAHEAD_TLS_CLIENT_KEY",
                "APP_OAUTH_CLIENT_ID", "LOOKAHEAD_GATEWAY_CLIENT_SECRET", "LOOKAHEAD_ADMISSION_CLIENT_ID",
                "LOOKAHEAD_ADMISSION_CLIENT_SECRET", "LOOKAHEAD_DOMAIN_GATEWAY_SECRET", "LOOKAHEAD_DOMAIN_DB_PASSWORD")) {
            inputs.put(name, environment.getProperty(name));
        }
        environment.getPropertySources().addFirst(new MapPropertySource("validatedInjectedCredentials",
                InjectedCredentialValidator.properties(inputs, "domain")));
        } catch (Exception rejected) {
            throw new IllegalStateException("Injected credential validation failed; application startup denied");
        }
    }
}
