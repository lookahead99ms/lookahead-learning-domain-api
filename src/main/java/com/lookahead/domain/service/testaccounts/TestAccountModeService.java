package com.lookahead.domain.service.testaccounts;

import com.lookahead.domain.util.TestAccountRules;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Two ways to maintain test accounts, never both at once:
 * local  - the fixture roster (seed/reset) plus created learners against the Local Identity service;
 * cloud  - DLV-969 S35 ({@code app.test-accounts.identity=dev}): database-only test accounts in DEV, with no sign-in of
 *          their own. Staff use them through a test session inside the DEV gate ({@code TestSessionService}); the owner
 *          creates the roster, staff create single accounts from the same scenarios.
 * PROD never has test accounts: cloud mode needs {@code app.deployment-environment=dev}.
 */
@Component
public class TestAccountModeService {
    private final Environment environment;
    public TestAccountModeService(Environment environment) {
        this.environment = environment;
        TestAccountRules.requireKnownIdentity(environment);
    }
    /** The Local fixture rule, unchanged: roster seeding, resets and the Local Identity operator ({@link TestAccountRules#local}). */
    public boolean local() { return TestAccountRules.local(environment); }
    /** DEV only: database-only test accounts, used through test sessions (DLV-969 S35). */
    public boolean cloud() {
        return "dev".equals(environment.getProperty("app.deployment-environment")) && "dev".equals(TestAccountRules.identity(environment));
    }
    public boolean enabled() { return local() || cloud(); }
    public void requireEnabled() { if (!enabled()) throw TestAccountRules.disabled(); }
    /** Roster fixtures and the Local Identity operator: refused in cloud mode as well as when disabled. */
    public void requireLocal() { if (!local()) throw TestAccountRules.disabled(); }
    /** DLV-969 S35: the DEV roster and test sessions. */
    public void requireCloud() { if (!cloud()) throw TestAccountRules.disabled(); }
}
