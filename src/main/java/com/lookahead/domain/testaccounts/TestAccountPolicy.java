package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.exception.AccountFailure;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class TestAccountPolicy {
    private final Environment environment;
    public TestAccountPolicy(Environment environment) { this.environment = environment; }
    public boolean enabled() {
        // This maintenance service is local. DEV uses provider-owned identities and must
        // not bypass its admission contract by enabling the local fixture profile.
        return "local".equals(environment.getProperty("app.deployment-environment"))
                && environment.acceptsProfiles(Profiles.of("accounts & local-test"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production", "dev"))
                && environment.getProperty("app.test-accounts.enabled", Boolean.class, false);
    }
    public void requireEnabled() {
        if (!enabled())
            throw new AccountFailure(403, "TEST_ACCOUNTS_DISABLED", "Test account maintenance is disabled.");
    }
}
