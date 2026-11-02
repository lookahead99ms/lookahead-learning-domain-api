package com.lookahead.domain.testaccounts;

import com.lookahead.domain.cloud.CognitoSettings;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * DLV-969 phase C: the DEV pool that receives created test learners. It must be the
 * sign-in pool of {@code app.cognito.issuer}; test accounts never exist in PROD.
 */
public record CloudTestAccountSettings(String poolId, String issuer) {
    public static CloudTestAccountSettings from(Environment env, CognitoSettings cognito) {
        if (!"cognito".equals(env.getProperty("app.test-accounts.identity", "local")))
            throw new IllegalStateException("Cognito test accounts require app.test-accounts.identity=cognito");
        if (!"dev".equals(env.getProperty("app.deployment-environment", "")))
            throw new IllegalStateException("Test accounts never exist outside the dev Domain environment");
        if (cognito == null)
            throw new IllegalStateException("Cognito test accounts require the cloud sign-in configuration");
        String pool = env.getProperty("app.test-accounts.pool-id", "").strip();
        if (!pool.matches(Pattern.quote(cognito.region()) + "_[A-Za-z0-9]+"))
            throw new IllegalStateException("app.test-accounts.pool-id must identify a pool in the configured region");
        if (!cognito.issuer().equals("https://cognito-idp." + cognito.region() + ".amazonaws.com/" + pool))
            throw new IllegalStateException("app.test-accounts.pool-id must be the sign-in pool of app.cognito.issuer");
        return new CloudTestAccountSettings(pool, cognito.issuer());
    }
}
