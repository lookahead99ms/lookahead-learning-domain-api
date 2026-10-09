package com.lookahead.domain.cloud;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

public record CognitoSettings(String issuer, String region, String clientId, String scopePrefix, String gatewaySecret) {
    public static CognitoSettings from(Environment env) {
        String mode = required(env,"app.deployment-environment");
        if (!Set.of("dev","prod").contains(mode) || env.acceptsProfiles(Profiles.of("local","local-test","gateway","oauth-server")))
            throw new IllegalStateException("Cognito requires an explicit cloud Domain environment");
        String issuer=required(env,"app.cognito.issuer"), region=required(env,"app.cognito.region");
        if (!region.matches("[a-z]{2}-[a-z]+-\\d") || !issuer.matches("https://cognito-idp\\."+java.util.regex.Pattern.quote(region)+"\\.amazonaws\\.com/"+java.util.regex.Pattern.quote(region)+"_[A-Za-z0-9]+"))
            throw new IllegalStateException("Cognito issuer must identify the selected regional pool");
        String client=required(env,"app.cognito.client-id"), prefix=required(env,"app.cognito.scope-prefix"), secret=required(env,"app.cognito.gateway-secret");
        if (!client.matches("[A-Za-z0-9]{10,128}") || !prefix.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{1,200}") || prefix.endsWith("/")
                || secret.startsWith("arn:") || secret.contains("${") || secret.length()<32 || secret.length()>4096 || secret.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalStateException("Invalid cloud client, scope prefix or injected Gateway secret");
        return new CognitoSettings(issuer,region,client,prefix,secret);
    }
    private static String required(Environment env,String key) {
        String value;
        try { value=env.getProperty(key); }
        catch (RuntimeException unresolved) { throw new IllegalStateException(key+" requires a resolved value"); }
        if(value==null||value.isBlank())throw new IllegalStateException(key+" is required");return value;
    }
    @Override public String toString(){return "CognitoSettings[redacted]";}
}
