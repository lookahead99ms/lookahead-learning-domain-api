package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.exception.AccountFailure;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Two ways to maintain test accounts, never both at once:
 * local  - the fixture roster (seed/reset) plus created learners against the Local Identity service;
 * cloud  - DLV-969 phase C: created learners only, provisioned in the DEV Cognito pool
 *          ({@code app.test-accounts.identity=cognito} with {@code app.test-accounts.pool-id}).
 * PROD never has test accounts; {@link CloudTestAccountSettings} refuses to start there.
 */
@Component
public class TestAccountPolicy {
    private static final Set<String> IDENTITIES = Set.of("local", "cognito");
    private final Environment environment;
    public TestAccountPolicy(Environment environment) {
        this.environment = environment;
        if (!IDENTITIES.contains(identity()))
            throw new IllegalStateException("app.test-accounts.identity must be local or cognito");
    }
    private String identity() { return environment.getProperty("app.test-accounts.identity", "local"); }
    /** The Local fixture rule, unchanged: roster seeding, resets and the Local Identity operator. */
    public boolean local() {
        // This maintenance service is local. DEV uses provider-owned identities and must
        // not bypass its admission contract by enabling the local fixture profile.
        return "local".equals(environment.getProperty("app.deployment-environment"))
                && environment.acceptsProfiles(Profiles.of("accounts & local-test"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production", "dev"))
                && environment.getProperty("app.test-accounts.enabled", Boolean.class, false);
    }
    /** DEV only: created test learners live in the Cognito pool; the roster stays off. */
    public boolean cloud() {
        return "dev".equals(environment.getProperty("app.deployment-environment"))
                && "cognito".equals(identity())
                && !environment.getProperty("app.test-accounts.pool-id", "").isBlank();
    }
    public boolean enabled() { return local() || cloud(); }
    public void requireEnabled() { if (!enabled()) throw disabled(); }
    /** Roster fixtures and the Local Identity operator: refused in cloud mode as well as when disabled. */
    public void requireLocal() { if (!local()) throw disabled(); }
    private static AccountFailure disabled() {
        return new AccountFailure(403, "TEST_ACCOUNTS_DISABLED", "Test account maintenance is disabled.");
    }
}
