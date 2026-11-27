package com.lookahead.domain.util;

import com.lookahead.domain.exception.AccountFailure;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The rules that say what a test-account registry row is and where test accounts are maintained. DLV-1016: the roster
 * rule was written out in the test-account store and in TestAccountService; the mode rules were written in
 * TestAccountModeService, which two start-up validators read (the policy bean now asks here too).
 */
public final class TestAccountRules {
    private static final Set<String> IDENTITIES = Set.of("local", "dev");
    private TestAccountRules() {}

    /** A roster row: an account somebody created as a test account ({@code creatorId} set) through the roster, not one made by Create account ({@code initialization} "created"). */
    public static boolean rosterRow(UUID creatorId, String initialization) { return creatorId != null && !"created".equals(initialization); }

    /** {@code app.test-accounts.identity}: {@code local} when it is not set. */
    public static String identity(Environment environment) { return environment.getProperty("app.test-accounts.identity", "local"); }

    /** Refuses the start when {@code app.test-accounts.identity} is neither {@code local} nor {@code dev}. */
    public static void requireKnownIdentity(Environment environment) {
        if (!IDENTITIES.contains(identity(environment)))
            throw new IllegalStateException("app.test-accounts.identity must be local or dev");
    }

    /** The Local fixture rule, unchanged: roster seeding, resets and the Local Identity operator. */
    public static boolean local(Environment environment) {
        // This maintenance service is local. DEV uses provider-owned identities and must
        // not bypass its admission contract by enabling the local fixture profile.
        return "local".equals(environment.getProperty("app.deployment-environment"))
                && environment.acceptsProfiles(Profiles.of("accounts & local-test"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production", "dev"))
                && environment.getProperty("app.test-accounts.enabled", Boolean.class, false);
    }

    /** Roster fixtures and the Local Identity operator: refused in cloud mode as well as when disabled. */
    public static void requireLocal(Environment environment) { if (!local(environment)) throw disabled(); }

    public static AccountFailure disabled() {
        return new AccountFailure(403, "TEST_ACCOUNTS_DISABLED", "Test account maintenance is disabled.");
    }
}
