package com.lookahead.domain.testaccounts;

import com.lookahead.learning.content.exception.AccountFailure;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Two ways to maintain test accounts, never both at once:
 * local  - the fixture roster (seed/reset) plus created learners against the Local Identity service;
 * cloud  - DLV-969 S35 ({@code app.test-accounts.identity=dev}): database-only test accounts in DEV, with no sign-in of
 *          their own. Staff use them through a test session inside the DEV gate ({@code DevTestSessions}); the owner
 *          creates the roster, staff create single accounts from the same scenarios.
 * PROD never has test accounts: cloud mode needs {@code app.deployment-environment=dev}.
 */
@Component
public class TestAccountPolicy {
    private static final Set<String> IDENTITIES = Set.of("local", "dev");
    private final Environment environment;
    public TestAccountPolicy(Environment environment) {
        this.environment = environment;
        if (!IDENTITIES.contains(identity()))
            throw new IllegalStateException("app.test-accounts.identity must be local or dev");
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
    /** DEV only: database-only test accounts, used through test sessions (DLV-969 S35). */
    public boolean cloud() {
        return "dev".equals(environment.getProperty("app.deployment-environment")) && "dev".equals(identity());
    }
    public boolean enabled() { return local() || cloud(); }
    public void requireEnabled() { if (!enabled()) throw disabled(); }
    /** Roster fixtures and the Local Identity operator: refused in cloud mode as well as when disabled. */
    public void requireLocal() { if (!local()) throw disabled(); }
    /** DLV-969 S35: the DEV roster and test sessions. */
    public void requireCloud() { if (!cloud()) throw disabled(); }
    private static AccountFailure disabled() {
        return new AccountFailure(403, "TEST_ACCOUNTS_DISABLED", "Test account maintenance is disabled.");
    }
}
