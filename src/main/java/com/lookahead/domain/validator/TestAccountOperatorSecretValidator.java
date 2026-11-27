package com.lookahead.domain.validator;

import com.lookahead.domain.util.TestAccountRules;
import org.springframework.core.env.Environment;

/**
 * Startup check of the test-account operator secret ({@code app.test-accounts.operator-secret}). With test accounts
 * enabled it must be Local, at least 32 characters, and different from the Identity verifier secret and the OAuth
 * client secret; otherwise startup is refused.
 */
public final class TestAccountOperatorSecretValidator {
    private TestAccountOperatorSecretValidator() { }
    /** The configured secret (empty when none is set), after the check. */
    public static String validated(Environment environment) {
        String secret = environment.getProperty("app.test-accounts.operator-secret", "");
        if (environment.getProperty("app.test-accounts.enabled", Boolean.class, false)) {
            TestAccountRules.requireLocal(environment);
            if (secret.length() < 32 || secret.equals(environment.getProperty("app.identity.verifier-secret"))
                    || secret.equals(environment.getProperty("app.oauth.client-secret")))
                throw new IllegalStateException("Test account maintenance requires a separate operator secret.");
        }
        return secret;
    }
}
