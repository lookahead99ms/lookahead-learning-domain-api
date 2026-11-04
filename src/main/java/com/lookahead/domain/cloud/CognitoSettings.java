package com.lookahead.domain.cloud;

import com.lookahead.domain.StartupChecks;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** DLV-969 (owner, 2026-11-04): a refusal first names the setting and what is wrong ({@link StartupChecks}), never its value. */
public record CognitoSettings(String issuer, String region, String clientId, String scopePrefix, String gatewaySecret) {
    static final String INVALID = "Invalid cloud client, scope prefix or injected Gateway secret";
    public static CognitoSettings from(Environment env) {
        String mode = required(env,"app.deployment-environment");
        if (!Set.of("dev","prod").contains(mode) || env.acceptsProfiles(Profiles.of("local","local-test","gateway","oauth-server")))
            throw StartupChecks.refuse("app.deployment-environment (LOOKAHEAD_ENVIRONMENT)", "ENVIRONMENT_INVALID",
                    "must be dev or prod, without the local, local-test, gateway or oauth-server profiles", "Cognito requires an explicit cloud Domain environment");
        String issuer=required(env,"app.cognito.issuer"), region=required(env,"app.cognito.region");
        if (!region.matches("[a-z]{2}-[a-z]+-\\d") || !issuer.matches("https://cognito-idp\\."+java.util.regex.Pattern.quote(region)+"\\.amazonaws\\.com/"+java.util.regex.Pattern.quote(region)+"_[A-Za-z0-9]+"))
            throw StartupChecks.refuse(setting("app.cognito.issuer")+" and "+setting("app.cognito.region"), "COGNITO_ISSUER_INVALID",
                    "must be https://cognito-idp.<region>.amazonaws.com/<region>_<pool> for the same AWS region", "Cognito issuer must identify the selected regional pool");
        String client=required(env,"app.cognito.client-id"), prefix=required(env,"app.cognito.scope-prefix"), secret=required(env,"app.cognito.gateway-secret");
        if (!client.matches("[A-Za-z0-9]{10,128}"))
            throw StartupChecks.refuse(setting("app.cognito.client-id"), "SETTING_INVALID", "is not 10 to 128 letters and digits", INVALID);
        if (!prefix.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{1,200}") || prefix.endsWith("/"))
            throw StartupChecks.refuse(setting("app.cognito.scope-prefix"), "SETTING_INVALID",
                    "is not a resource server identifier (2 to 201 letters, digits and :._/- characters, no trailing /)", INVALID);
        String problem = secret.startsWith("arn:") ? "is a secret ARN; the task definition must inject the secret's value"
                : secret.contains("${") ? "holds an unresolved ${...} placeholder"
                : secret.length()<32 ? "is shorter than 32 characters" : secret.length()>4096 ? "is longer than 4096 characters"
                : secret.codePoints().anyMatch(Character::isISOControl) ? "contains control characters" : null;
        if (problem != null) throw StartupChecks.refuse(setting("app.cognito.gateway-secret"), "SECRET_INVALID", problem, INVALID);
        return new CognitoSettings(issuer,region,client,prefix,secret);
    }
    private static String required(Environment env,String key) {
        String value;
        try { value=env.getProperty(key); }
        catch (RuntimeException unresolved) {
            StartupChecks.refused(setting(key), "SETTING_UNRESOLVED", "could not be read: it holds a ${...} placeholder with no value", unresolved);
            throw new IllegalStateException(key+" requires a resolved value");
        }
        if(value==null||value.isBlank())throw StartupChecks.refuse(setting(key), "SETTING_MISSING", "is not set", key+" is required");
        return value;
    }
    /** The property and the environment variable that sets it (application.yml). */
    static String setting(String key) {
        String variable = switch (key) {
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
    @Override public String toString(){return "CognitoSettings[redacted]";}
}
