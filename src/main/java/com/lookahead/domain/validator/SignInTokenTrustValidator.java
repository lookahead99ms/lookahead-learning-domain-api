package com.lookahead.domain.validator;

import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.security.config.SignInTokenTrust;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Startup check of the sign-in settings (DEV and PROD): the Cognito pool, the application client,
 * the scope prefix and the Gateway BFF's shared secret. DLV-969 (owner, 2026-11-04): a refusal
 * first names the setting and what is wrong ({@link com.lookahead.domain.log.EventLog}), never its
 * value. DLV-1016: these checks were {@code SignInTokenTrust.from} and moved here word for word;
 * everything is still read and checked in the one pass of {@link #validated}, so the same start-up
 * refusal comes first when several settings are wrong.
 */
public final class SignInTokenTrustValidator {
  public static final String INVALID =
      "Invalid cloud client, scope prefix or injected Gateway secret";

  private SignInTokenTrustValidator() {}

  /** The token trust and the Gateway BFF's secret, after the checks. */
  public static SignInTokenTrust.Checked validated(Environment env) {
    String mode = required(env, "app.deployment-environment");
    if (!Set.of("dev", "prod").contains(mode)
        || env.acceptsProfiles(Profiles.of("local", "local-test", "gateway", "oauth-server")))
      throw EventLog.startupRefuse(
          "app.deployment-environment (LOOKAHEAD_ENVIRONMENT)",
          "ENVIRONMENT_INVALID",
          "must be dev or prod, without the local, local-test, gateway or oauth-server profiles",
          "Cognito requires an explicit cloud Domain environment");
    String issuer = required(env, "app.cognito.issuer"),
        region = required(env, "app.cognito.region");
    if (!region.matches(CloudRuntimeValidator.AWS_REGION)
        || !issuer.matches(
            "https://cognito-idp\\."
                + Pattern.quote(region)
                + "\\.amazonaws\\.com/"
                + Pattern.quote(region)
                + "_[A-Za-z0-9]+"))
      throw EventLog.startupRefuse(
          setting("app.cognito.issuer") + " and " + setting("app.cognito.region"),
          "COGNITO_ISSUER_INVALID",
          "must be https://cognito-idp.<region>.amazonaws.com/<region>_<pool> for the same AWS"
              + " region",
          "Cognito issuer must identify the selected regional pool");
    String client = required(env, "app.cognito.client-id"),
        prefix = required(env, "app.cognito.scope-prefix"),
        secret = required(env, "app.cognito.gateway-secret");
    if (!client.matches("[A-Za-z0-9]{10,128}"))
      throw EventLog.startupRefuse(
          setting("app.cognito.client-id"),
          "SETTING_INVALID",
          "is not 10 to 128 letters and digits",
          INVALID);
    if (!prefix.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{1,200}") || prefix.endsWith("/"))
      throw EventLog.startupRefuse(
          setting("app.cognito.scope-prefix"),
          "SETTING_INVALID",
          "is not a resource server identifier (2 to 201 letters, digits and :._/- characters, no"
              + " trailing /)",
          INVALID);
    String problem =
        secret.startsWith("arn:")
            ? "is a secret ARN; the task definition must inject the secret's value"
            : secret.contains("${")
                ? "holds an unresolved ${...} placeholder"
                : secret.length() < 32
                    ? "is shorter than 32 characters"
                    : secret.length() > 4096
                        ? "is longer than 4096 characters"
                        : secret.codePoints().anyMatch(Character::isISOControl)
                            ? "contains control characters"
                            : null;
    if (problem != null)
      throw EventLog.startupRefuse(
          setting("app.cognito.gateway-secret"), "SECRET_INVALID", problem, INVALID);
    return new SignInTokenTrust.Checked(
        new SignInTokenTrust(issuer, region, client, prefix), new SignInBffSecret(secret));
  }

  private static String required(Environment env, String key) {
    String value;
    try {
      value = env.getProperty(key);
    } catch (RuntimeException unresolved) {
      EventLog.startupRefused(
          setting(key),
          "SETTING_UNRESOLVED",
          "could not be read: it holds a ${...} placeholder with no value",
          unresolved);
      throw new IllegalStateException(key + " requires a resolved value");
    }
    if (value == null || value.isBlank())
      throw EventLog.startupRefuse(
          setting(key), "SETTING_MISSING", "is not set", key + " is required");
    return value;
  }

  /** The property and the environment variable that sets it (application.yml). */
  static String setting(String key) {
    String variable =
        switch (key) {
          case "app.deployment-environment" -> "LOOKAHEAD_ENVIRONMENT";
          case "app.cognito.issuer" -> "LOOKAHEAD_COGNITO_ISSUER";
          case "app.cognito.region" -> "AWS_REGION";
          case "app.cognito.client-id" -> "APP_OAUTH_CLIENT_ID";
          case "app.cognito.scope-prefix" -> "LOOKAHEAD_COGNITO_SCOPE_PREFIX";
          case "app.cognito.gateway-secret" -> "LOOKAHEAD_DOMAIN_GATEWAY_SECRET";
          default -> null;
        };
    return variable == null ? key : key + " (" + variable + ")";
  }
}
