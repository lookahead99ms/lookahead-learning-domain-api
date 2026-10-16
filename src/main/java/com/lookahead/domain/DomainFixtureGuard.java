package com.lookahead.domain;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Domain fixtures contain subject IDs and grants only, never authentication credentials. */
@Component
public class DomainFixtureGuard {
    public DomainFixtureGuard(Environment environment) {
        boolean managed = environment.getProperty("app.test-accounts.enabled", Boolean.class, false);
        if (managed) new com.lookahead.domain.testaccounts.TestAccountPolicy(environment).requireEnabled();
        boolean fixtureProfile = environment.acceptsProfiles(Profiles.of("local-test"));
        boolean seed = environment.getProperty("app.local-test.seed-enabled", Boolean.class, false);
        boolean author = environment.getProperty("app.local-test.author-enabled", Boolean.class, false);
        boolean local = "local".equals(environment.getProperty("app.deployment-environment"));
        boolean production = environment.acceptsProfiles(Profiles.of("prod", "production", "dev"));
        if ((fixtureProfile || seed || author) && (!local || production || !fixtureProfile))
            throw new IllegalStateException("Domain fixtures require local-test and local deployment only");
        if (author && !seed && !managed) throw new IllegalStateException("Local author access requires explicit local fixture seeding");
    }
}
