package com.lookahead.domain.validator;

import com.lookahead.domain.util.TestAccountRules;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Domain fixtures contain subject IDs and grants only, never authentication credentials. The
 * start-up check of the fixture settings: they are allowed only in a Local deployment under the
 * local-test profile. DLV-1016: the check is the static {@link #validate};
 * job.TestAccountFixtureSeedJob calls it as the first statement of its constructor (it used to take
 * this bean as a parameter it never read, to make the check run first).
 */
@Component
public class TestAccountFixtureValidator {
  // Exception, decided 2026-11-25 (DLV-1016): this class stays a bean whose constructor runs the
  // check. The check
  // refuses a start in every environment (PROD with app.local-test.author-enabled=true is refused
  // by it), but the one
  // class that calls validate(...) explicitly, job.TestAccountFixtureSeedJob, exists only under the
  // profiles accounts
  // and local-test with app.local-test.seed-enabled=true. Without this bean nothing would run the
  // check anywhere
  // else, and the refusal of every other environment would be lost.
  public TestAccountFixtureValidator(Environment environment) {
    validate(environment);
  }

  public static void validate(Environment environment) {
    boolean managed = environment.getProperty("app.test-accounts.enabled", Boolean.class, false);
    if (managed) {
      TestAccountRules.requireKnownIdentity(environment);
      TestAccountRules.requireLocal(environment);
    }
    boolean fixtureProfile = environment.acceptsProfiles(Profiles.of("local-test"));
    boolean seed = environment.getProperty("app.local-test.seed-enabled", Boolean.class, false);
    boolean author = environment.getProperty("app.local-test.author-enabled", Boolean.class, false);
    boolean local = "local".equals(environment.getProperty("app.deployment-environment"));
    boolean production = environment.acceptsProfiles(Profiles.of("prod", "production", "dev"));
    if ((fixtureProfile || seed || author) && (!local || production || !fixtureProfile))
      throw new IllegalStateException(
          "Domain fixtures require local-test and local deployment only");
    if (author && !seed && !managed)
      throw new IllegalStateException(
          "Local author access requires explicit local fixture seeding");
  }
}
