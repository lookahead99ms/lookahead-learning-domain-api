package com.lookahead.domain.security.tls;

import com.lookahead.domain.log.EventLog;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * Runs before context refresh, SSL bundle construction and any listening server; a refusal names
 * the input (DLV-969).
 */
public final class InjectedCredentialInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  @Override
  public void initialize(ConfigurableApplicationContext context) {
    var environment = context.getEnvironment();
    if (!environment.acceptsProfiles(Profiles.of("lower-cost-dev"))) return;
    String reading = null;
    try {
      var inputs = new LinkedHashMap<String, String>();
      // APP_OAUTH_CLIENT_ID is the Domain's own setting, checked later as app.cognito.client-id; it
      // is read here only so
      // that a ${...} placeholder with no value is refused by name, before anything can quote it.
      for (String name :
          List.of(
              "LOOKAHEAD_TLS_SCHEMA",
              "LOOKAHEAD_TLS_SERVER_CERTIFICATE",
              "LOOKAHEAD_TLS_SERVER_KEY",
              "LOOKAHEAD_TLS_TRUST",
              "LOOKAHEAD_TLS_SERVER_NAME",
              "APP_OAUTH_CLIENT_ID",
              "LOOKAHEAD_DOMAIN_GATEWAY_SECRET")) {
        reading = name;
        inputs.put(name, environment.getProperty(name));
      }
      reading = null;
      // The validator writes its own line naming the input it refused.
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "validatedInjectedCredentials", InjectedCredentialValidator.properties(inputs)));
    } catch (Exception rejected) {
      if (reading != null)
        EventLog.startupRefused(
            reading,
            "INJECTED_CREDENTIAL_UNRESOLVED",
            "could not be read: it holds a ${...} placeholder with no value",
            rejected);
      throw new IllegalStateException(InjectedCredentialValidator.DENIED);
    }
  }
}
